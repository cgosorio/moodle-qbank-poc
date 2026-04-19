/*
 * Copyright (c) 2026 César Ignacio García Osorio
 *
 * Este archivo forma parte de Moodle Question Bank Manager.
 *
 * Se distribuye bajo la licencia MIT. Consulta el archivo LICENSE
 * en la raíz del proyecto para más detalles.
 */

import javafx.application.Application;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.scene.web.WebView;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import org.w3c.dom.*;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import java.io.File;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MoodleEditorApp extends Application {

    private TreeView<String> treeView;
    private TableView<MoodleQuestion> tableView;
    private WebView webView;
    private TextArea editorArea;
    private CheckBox chkRenderCloze;
    private Label lblPreviewMode;
    private MoodleQuestion currentQuestion;

    private final Map<String, ObservableList<MoodleQuestion>> categoryData = new HashMap<>();
    private TreeItem<String> draggedItem;

    @Override
    public void start(Stage primaryStage) {
        Button btnLoad = new Button("Cargar XML");
        btnLoad.setOnAction(e -> openFileChooser(primaryStage));

        Button btnSave = new Button("Guardar XML");
        btnSave.setStyle("-fx-background-color: #d1e7dd;");
        btnSave.setOnAction(e -> saveFileChooser(primaryStage));

        ToolBar toolBar = new ToolBar(btnLoad, btnSave);

        TreeItem<String> rootItem = new TreeItem<>("Banco de Preguntas");
        rootItem.setExpanded(true);
        treeView = new TreeView<>(rootItem);
        setupTreeCellFactory();
        setupTreeContextMenu();
        treeView.getSelectionModel().selectedItemProperty().addListener((obs, ov, nv) -> {
            if (nv != null) updateTable(getFullPath(nv));
        });

        tableView = new TableView<>();
        setupTableColumns();
        setupTableDragSource();

        webView = new WebView();
        editorArea = new TextArea();

        chkRenderCloze = new CheckBox("renderizar clozes");
        chkRenderCloze.setSelected(false);
        chkRenderCloze.selectedProperty().addListener((obs, ov, nv) -> {
            updatePreviewModeLabel();
            if (currentQuestion != null) {
                updateDetail(currentQuestion);
            }
        });

        lblPreviewMode = new Label();
        updatePreviewModeLabel();

        VBox detailBox = new VBox(
            10,
            new Label("Vista previa de la pregunta:"),
            chkRenderCloze,
            /* lblPreviewMode, */
            webView,
            new Label("Código HTML:"),
            editorArea
        );
        VBox.setVgrow(webView, Priority.ALWAYS);
        VBox.setVgrow(editorArea, Priority.ALWAYS);
        detailBox.setPadding(new Insets(10));

        SplitPane rightSplit = new SplitPane(tableView, detailBox);
        rightSplit.setOrientation(javafx.geometry.Orientation.VERTICAL);
        rightSplit.setDividerPositions(0.4);

        SplitPane mainSplit = new SplitPane(treeView, rightSplit);
        mainSplit.setDividerPositions(0.3);

        VBox rootLayout = new VBox(toolBar, mainSplit);
        VBox.setVgrow(mainSplit, Priority.ALWAYS);

        primaryStage.setScene(new Scene(rootLayout, 1200, 800));
        primaryStage.setTitle("Moodle Editor Offline - Linux Mint");
        primaryStage.show();
    }

    private void updatePreviewModeLabel() {
        String mode = chkRenderCloze != null && chkRenderCloze.isSelected()
            ? "Modo actual: cloze renderizado"
            : "Modo actual: sintaxis cloze literal resaltada";
        lblPreviewMode.setText(mode);
        lblPreviewMode.setStyle("-fx-text-fill: #666;");
    }

    private void setupTreeCellFactory() {
        treeView.setCellFactory(tv -> {
            TreeCell<String> cell = new TreeCell<>() {
                @Override
                protected void updateItem(String item, boolean empty) {
                    super.updateItem(item, empty);
                    if (empty || item == null) {
                        setText(null);
                    } else {
                        String path = getFullPath(getTreeItem());
                        int count = categoryData.getOrDefault(path, FXCollections.observableArrayList()).size();
                        setText(item + " (" + count + ")");
                    }
                }
            };

            cell.setOnDragDetected(event -> {
                if (!cell.isEmpty() && cell.getTreeItem() != treeView.getRoot()) {
                    draggedItem = cell.getTreeItem();
                    Dragboard db = cell.startDragAndDrop(TransferMode.MOVE);
                    ClipboardContent content = new ClipboardContent();
                    content.putString("CATEGORY");
                    db.setContent(content);
                    event.consume();
                }
            });

            cell.setOnDragOver(event -> {
                if (event.getDragboard().hasString()) event.acceptTransferModes(TransferMode.MOVE);
                event.consume();
            });

            cell.setOnDragDropped(event -> {
                if (!cell.isEmpty()) {
                    TreeItem<String> target = cell.getTreeItem();
                    if (draggedItem != null) moveCategory(draggedItem, target);
                    else moveQuestionToCategory(target);
                    event.setDropCompleted(true);
                    draggedItem = null;
                    treeView.refresh();
                }
                event.consume();
            });
            return cell;
        });
    }

    private void setupTableDragSource() {
        tableView.setOnDragDetected(event -> {
            ObservableList<MoodleQuestion> selected = tableView.getSelectionModel().getSelectedItems();
            if (!selected.isEmpty()) {
                draggedItem = null;
                Dragboard db = tableView.startDragAndDrop(TransferMode.MOVE);
                ClipboardContent content = new ClipboardContent();
                content.putString("MULTIPLE_QUESTIONS");
                db.setContent(content);
                event.consume();
            }
        });
    }

    private void moveCategory(TreeItem<String> source, TreeItem<String> target) {
        if (source == null || target == null || source == target) return;
        TreeItem<String> temp = target;
        while (temp != null) {
            if (temp == source) return;
            temp = temp.getParent();
        }

        String oldRootPath = getFullPath(source);
        source.getParent().getChildren().remove(source);
        target.getChildren().add(source);
        updateMapPaths(oldRootPath, getFullPath(source));
        target.setExpanded(true);
    }

    private void updateMapPaths(String oldPath, String newPath) {
        List<String> keysToMove = new ArrayList<>();
        for (String key : categoryData.keySet()) {
            if (key.startsWith(oldPath)) keysToMove.add(key);
        }
        for (String oldKey : keysToMove) {
            String newKey = oldKey.replaceFirst(Pattern.quote(oldPath), newPath);
            categoryData.put(newKey, categoryData.remove(oldKey));
        }
    }

    private void moveQuestionToCategory(TreeItem<String> targetItem) {
        ObservableList<MoodleQuestion> selectedQuestions = tableView.getSelectionModel().getSelectedItems();
        TreeItem<String> currentCategoryItem = treeView.getSelectionModel().getSelectedItem();

        if (selectedQuestions.isEmpty() || currentCategoryItem == null || targetItem == null) return;

        String oldPath = getFullPath(currentCategoryItem);
        String newPath = getFullPath(targetItem);

        if (!oldPath.equals(newPath)) {
            List<MoodleQuestion> toMove = new ArrayList<>(selectedQuestions);
            categoryData.get(oldPath).removeAll(toMove);
            categoryData.computeIfAbsent(newPath, k -> FXCollections.observableArrayList()).addAll(toMove);
            updateTable(oldPath);
            treeView.refresh();
        }
    }

    private void openFileChooser(Stage stage) {
        FileChooser fc = new FileChooser();
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("XML", "*.xml"));
        File f = fc.showOpenDialog(stage);
        if (f != null) {
            categoryData.clear();
            treeView.getRoot().getChildren().clear();
            currentQuestion = null;
            loadXML(f);
        }
    }

    private void saveFileChooser(Stage stage) {
        FileChooser fc = new FileChooser();
        fc.setInitialFileName("Banco_Actualizado.xml");
        File f = fc.showSaveDialog(stage);
        if (f != null) exportToXML(f);
    }

    private void loadXML(File file) {
        try {
            Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file);
            doc.getDocumentElement().normalize();
            NodeList nl = doc.getElementsByTagName("question");
            String curP = "Banco de Preguntas";

            for (int i = 0; i < nl.getLength(); i++) {
                Node node = nl.item(i);
                if (node instanceof Element el) {
                    if ("category".equals(el.getAttribute("type"))) {
                        curP = buildTreeFromMoodlePath(getVal(el, "category"));
                    } else {
                        MoodleQuestion q = new MoodleQuestion(
                            el.getAttribute("type"),
                            getVal(el, "name"),
                            getVal(el, "questiontext"),
                            el
                        );

                        NodeList fileNodes = el.getElementsByTagName("file");
                        for (int j = 0; j < fileNodes.getLength(); j++) {
                            Element fEl = (Element) fileNodes.item(j);
                            q.getFiles().add(new MoodleFile(
                                fEl.getAttribute("name"),
                                fEl.getAttribute("path"),
                                fEl.getAttribute("encoding"),
                                fEl.getTextContent()
                            ));
                        }
                        categoryData.computeIfAbsent(curP, k -> FXCollections.observableArrayList()).add(q);
                    }
                }
            }
            treeView.refresh();
        } catch (Exception e) {
            e.printStackTrace();
            new Alert(Alert.AlertType.ERROR, "No se pudo cargar el XML: " + e.getMessage()).show();
        }
    }

    private String getVal(Element p, String tag) {
        try {
            NodeList nl = p.getElementsByTagName(tag);
            if (nl.getLength() > 0 && nl.item(0) instanceof Element) {
                NodeList textList = ((Element) nl.item(0)).getElementsByTagName("text");
                if (textList.getLength() > 0) {
                    return textList.item(0).getTextContent();
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private void exportToXML(File file) {
        try {
            Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
            Element root = doc.createElement("quiz");
            doc.appendChild(root);

            traverseAndExport(treeView.getRoot(), root, doc);

            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
            transformer.transform(new DOMSource(doc), new StreamResult(file));

            new Alert(Alert.AlertType.INFORMATION, "Archivo exportado correctamente para Moodle.").show();
        } catch (Exception e) {
            e.printStackTrace();
            new Alert(Alert.AlertType.ERROR, "No se pudo exportar el XML: " + e.getMessage()).show();
        }
    }

    private void traverseAndExport(TreeItem<String> item, Element root, Document doc) {
        if (item != treeView.getRoot()) {
            String path = getFullPath(item);

            Element catQ = doc.createElement("question");
            catQ.setAttribute("type", "category");
            Element cat = doc.createElement("category");
            Element txt = doc.createElement("text");
            txt.setTextContent("$module$/top/" + path.replace("Banco de Preguntas/", ""));
            cat.appendChild(txt);
            catQ.appendChild(cat);
            root.appendChild(catQ);

            List<MoodleQuestion> qs = categoryData.get(path);
            if (qs != null) {
                for (MoodleQuestion q : qs) {
                    Node importedNode = doc.importNode(q.getOriginalElement(), true);
                    root.appendChild(importedNode);
                }
            }
        }
        for (TreeItem<String> child : item.getChildren()) {
            traverseAndExport(child, root, doc);
        }
    }

    private void updateDetail(MoodleQuestion q) {
        if (q == null) return;
        currentQuestion = q;

        StringBuilder html = new StringBuilder();
        html.append("<html><head>");
        html.append("<script src='https://cdn.jsdelivr.net/npm/mathjax@3/es5/tex-mml-chtml.js'></script>");
        html.append("<script>");
        html.append("MathJax = {");
        html.append("  tex: {");
        html.append("    inlineMath: [['$', '$'], ['$$', '$$'], ['\\\\(', '\\\\)']],");
        html.append("    displayMath: []");
        html.append("  },");
        html.append("  chtml: { displayAlign: 'left' }");
        html.append("};");
        html.append("</script>");
        html.append("<style>");
        html.append("  body { font-family: sans-serif; padding: 10px; line-height: 1.4; }");
        html.append("  mjx-container[display=\"true\"] { margin: 0 !important; display: inline-block !important; }");
        html.append("  .MathJax { white-space: nowrap; }");
        html.append("  .mode-note { margin: 0 0 10px 0; padding: 8px 10px; border-radius: 4px; background: #eef5ff; color: #355070; font-size: 0.9em; }");
        html.append("</style>");

        html.append("<div style='background: #f8f9fa; padding: 10px; border-bottom: 2px solid #dee2e6; margin-bottom: 15px;'>");
        html.append("<strong>Nombre:</strong> ").append(q.getName()).append("<br>");
        html.append("<strong>Tipo:</strong> <span style='color: #0d6efd;'>").append(q.getType()).append("</span>");
        html.append("</div>");

        String body = resolveQuestionBody(q);

        if ("cloze".equals(q.getType())) {
            if (chkRenderCloze.isSelected()) {
                html.append("<div class='mode-note'>Mostrando subpreguntas cloze renderizadas como controles interactivos.</div>");
                body = renderCloze(body);
            } else {
                html.append("<div class='mode-note'>Mostrando la sintaxis cloze original, resaltada por colores.</div>");
                body = highlightCloze(body);
            }
        }

        html.append("<div style='margin-bottom: 20px;'>").append(body).append("</div>");

        if ("cloze".equals(q.getType())) {
            // html.append(buildClozeColors()); // this add to much 'noise'
        }

        if ("matching".equals(q.getType())) {
            html.append("<h4>Pares de Emparejamiento:</h4>");
            html.append("<table border='1' style='border-collapse: collapse; width: 100%; font-size: 0.9em;'>");
            html.append("<tr style='background: #e9ecef;'><th style='padding: 5px;'>Pregunta / Estímulo</th><th style='padding: 5px;'>Respuesta Correcta</th></tr>");

            NodeList subquestions = q.getOriginalElement().getElementsByTagName("subquestion");
            for (int i = 0; i < subquestions.getLength(); i++) {
                Element sub = (Element) subquestions.item(i);
                String subText = getNestedText(sub);
                String subAnswer = "";
                NodeList subAnsList = sub.getElementsByTagName("answer");
                if (subAnsList.getLength() > 0) {
                    subAnswer = getNestedText((Element) subAnsList.item(0));
                }

                if (!subText.isEmpty() || !subAnswer.isEmpty()) {
                    html.append("<tr>");
                    html.append("<td style='padding: 5px;'>").append(subText).append("</td>");
                    html.append("<td style='padding: 5px; font-weight: bold; color: green;'>").append(subAnswer).append("</td>");
                    html.append("</tr>");
                }
            }
            html.append("</table>");
        } else {
            NodeList answers = q.getOriginalElement().getElementsByTagName("answer");
            if (answers.getLength() > 0) {
                html.append("<h4>Opciones de Respuesta:</h4>");
                html.append("<table border='1' style='border-collapse: collapse; width: 100%; font-size: 0.9em;'>");
                html.append("<tr style='background: #e9ecef;'><th style='padding: 5px;'>%</th><th style='padding: 5px;'>Opción</th><th style='padding: 5px;'>Feedback</th></tr>");

                for (int i = 0; i < answers.getLength(); i++) {
                    Element ans = (Element) answers.item(i);
                    String fraction = ans.getAttribute("fraction");
                    String answerText = getNestedText(ans);

                    String feedbackText = "";
                    NodeList fbNodes = ans.getElementsByTagName("feedback");
                    if (fbNodes.getLength() > 0) {
                        feedbackText = getNestedText((Element) fbNodes.item(0));
                    }

                    String color = "black";
                    try {
                        double f = Double.parseDouble(fraction);
                        if (f > 0) color = "green";
                        else if (f < 0) color = "red";
                    } catch (Exception ignored) {
                    }

                    html.append("<tr>");
                    html.append("<td style='padding: 5px; text-align: center; color:").append(color).append(";'>").append(fraction).append("%</td>");
                    html.append("<td style='padding: 5px;'>").append(answerText).append("</td>");
                    html.append("<td style='padding: 5px; font-style: italic; color: #666;'>").append(feedbackText).append("</td>");
                    html.append("</tr>");
                }
                html.append("</table>");
            }
        }

        html.append("</body></html>");
        webView.getEngine().loadContent(html.toString());
        editorArea.setText(q.getQuestionText());
    }

    private String resolveQuestionBody(MoodleQuestion q) {
        String body = q.getQuestionText();
        for (MoodleFile f : q.getFiles()) {
            String lower = f.name.toLowerCase();
            String mime = lower.endsWith(".png") ? "image/png"
                        : lower.endsWith(".gif") ? "image/gif"
                        : lower.endsWith(".svg") ? "image/svg+xml"
                        : "image/jpeg";
            body = body.replace("@@PLUGINFILE@@/" + f.name, "data:" + mime + ";base64," + f.content);
        }
        return body;
    }

    private String getNestedText(Element parent) {
        if (parent == null) return "";
        NodeList nl = parent.getElementsByTagName("text");
        if (nl.getLength() > 0) {
            return nl.item(0).getTextContent();
        }
        return parent.getTextContent() != null ? parent.getTextContent().trim() : "";
    }

    private void setupTableColumns() {
        tableView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        TableColumn<MoodleQuestion, String> colName = new TableColumn<>("Nombre de la Pregunta");
        colName.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().getName()));
        colName.setPrefWidth(400);

        TableColumn<MoodleQuestion, String> colType = new TableColumn<>("Tipo");
        colType.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().getType()));
        colType.setPrefWidth(100);

        tableView.getColumns().setAll(colName, colType);

        tableView.getSelectionModel().selectedItemProperty().addListener((obs, ov, nv) -> {
            if (nv != null) updateDetail(nv);
        });

        ContextMenu tableMenu = new ContextMenu();
        MenuItem deleteQuest = new MenuItem("Borrar Pregunta(s) seleccionada(s)");
        deleteQuest.setOnAction(e -> deleteSelectedQuestions());
        tableMenu.getItems().add(deleteQuest);
        tableView.setContextMenu(tableMenu);
    }

    private void deleteSelectedQuestions() {
        ObservableList<MoodleQuestion> selected = tableView.getSelectionModel().getSelectedItems();
        if (selected.isEmpty()) return;

        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, "¿Borrar " + selected.size() + " pregunta(s)?", ButtonType.YES, ButtonType.NO);
        if (alert.showAndWait().orElse(ButtonType.NO) == ButtonType.YES) {
            String path = getFullPath(treeView.getSelectionModel().getSelectedItem());
            categoryData.get(path).removeAll(new ArrayList<>(selected));
            treeView.refresh();
        }
    }

    private void setupTreeContextMenu() {
        ContextMenu m = new ContextMenu();

        MenuItem deleteItem = new MenuItem("Borrar Categoría");
        deleteItem.setOnAction(e -> deleteCategory(treeView.getSelectionModel().getSelectedItem()));

        MenuItem addCategory = new MenuItem("Añadir subcategoría");
        addCategory.setOnAction(e -> addNewCategory(treeView.getSelectionModel().getSelectedItem()));

        MenuItem expandAll = new MenuItem("Expandir Todo");
        expandAll.setOnAction(e -> expandRecursive(treeView.getSelectionModel().getSelectedItem(), true));

        MenuItem collapseAll = new MenuItem("Colapsar Todo");
        collapseAll.setOnAction(e -> expandRecursive(treeView.getSelectionModel().getSelectedItem(), false));

        m.getItems().addAll(expandAll, collapseAll, new SeparatorMenuItem(), deleteItem, addCategory);
        treeView.setContextMenu(m);
    }

    private void addNewCategory(TreeItem<String> selectedItem) {
        final TreeItem<String> parent = (selectedItem == null) ? treeView.getRoot() : selectedItem;

        TextInputDialog dialog = new TextInputDialog("Nueva Categoría");
        dialog.setTitle("Crear Subcategoría");
        dialog.setHeaderText("Añadir una subcategoría dentro de: " + parent.getValue());
        dialog.setContentText("Nombre de la categoría:");

        Optional<String> result = dialog.showAndWait();
        result.ifPresent(name -> {
            if (!name.trim().isEmpty()) {
                TreeItem<String> newItem = new TreeItem<>(name);
                parent.getChildren().add(newItem);
                parent.setExpanded(true);

                String newPath = getFullPath(newItem);
                categoryData.putIfAbsent(newPath, FXCollections.observableArrayList());
                treeView.refresh();
            } else {
                new Alert(Alert.AlertType.WARNING, "El nombre no puede estar vacío.").show();
            }
        });
    }

    private void deleteCategory(TreeItem<String> item) {
        if (item == null || item == treeView.getRoot()) return;

        String path = getFullPath(item);
        int numPreguntas = categoryData.getOrDefault(path, FXCollections.observableArrayList()).size();
        int numSubcategorias = countSubcategories(item) - 1;

        StringBuilder sb = new StringBuilder();
        sb.append("Estás a punto de borrar la categoría: '").append(item.getValue()).append("'\n\n");

        if (numPreguntas > 0 || numSubcategorias > 0) {
            sb.append("Aviso: Esta categoría contiene:\n");
            if (numPreguntas > 0) sb.append("- ").append(numPreguntas).append(" pregunta(s)\n");
            if (numSubcategorias > 0) sb.append("- ").append(numSubcategorias).append(" subcategoría(s)\n");
            sb.append("\nSi continúas, se perderá todo este contenido.\n");
        }

        sb.append("¿Deseas proceder con el borrado?");

        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Confirmar borrado de categoría");
        alert.setHeaderText(null);
        alert.setContentText(sb.toString());

        ButtonType btnSi = new ButtonType("Sí, borrar todo", ButtonBar.ButtonData.OK_DONE);
        ButtonType btnNo = new ButtonType("Cancelar", ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(btnSi, btnNo);

        Optional<ButtonType> result = alert.showAndWait();
        if (result.isPresent() && result.get() == btnSi) {
            categoryData.keySet().removeIf(key -> key.startsWith(path));
            item.getParent().getChildren().remove(item);
            tableView.setItems(FXCollections.observableArrayList());
            treeView.refresh();
        }
    }

    private int countSubcategories(TreeItem<String> item) {
        int count = 1;
        for (TreeItem<String> child : item.getChildren()) {
            count += countSubcategories(child);
        }
        return count;
    }

    private void expandRecursive(TreeItem<String> i, boolean e) {
        if (i == null) return;
        i.setExpanded(e);
        for (TreeItem<String> c : i.getChildren()) expandRecursive(c, e);
    }

    private String buildTreeFromMoodlePath(String mp) {
        String[] parts = mp.replace("$module$/top/", "").split("/");
        TreeItem<String> cur = treeView.getRoot();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            TreeItem<String> found = null;
            for (TreeItem<String> c : cur.getChildren()) {
                if (c.getValue().equals(p)) {
                    found = c;
                    break;
                }
            }
            if (found == null) {
                found = new TreeItem<>(p);
                cur.getChildren().add(found);
            }
            cur = found;
        }
        return getFullPath(cur);
    }

    private String getFullPath(TreeItem<String> i) {
        if (i == null || i.getParent() == null) return i != null ? i.getValue() : "";
        return getFullPath(i.getParent()) + "/" + i.getValue();
    }

    private void updateTable(String p) {
        tableView.setItems(categoryData.getOrDefault(p, FXCollections.observableArrayList()));
    }

    private static final Map<String, String[]> CLOZE_TYPE_COLORS = Map.ofEntries(
        Map.entry("NUMERICAL", new String[]{"#b45309", "#ffffff"}),
        Map.entry("NM", new String[]{"#b45309", "#ffffff"}),

        Map.entry("SHORTANSWER", new String[]{"#1d4ed8", "#ffffff"}),
        Map.entry("SA", new String[]{"#1d4ed8", "#ffffff"}),
        Map.entry("MW", new String[]{"#1d4ed8", "#ffffff"}),

        Map.entry("SHORTANSWER_C", new String[]{"#0e7490", "#fffaaf"}),
        Map.entry("SAC", new String[]{"#0e7490", "#fffaaf"}),
        Map.entry("MWC", new String[]{"#0e7490", "#fffaaf"}),

        Map.entry("MULTICHOICE", new String[]{"#15803d", "#ffffff"}),
        Map.entry("MC", new String[]{"#15803d", "#ffffff"}),
        Map.entry("MULTICHOICE_V", new String[]{"#15803d", "#ffffff"}),
        Map.entry("MCV", new String[]{"#15803d", "#ffffff"}),
        Map.entry("MULTICHOICE_H", new String[]{"#15803d", "#fffa0f"}),
        Map.entry("MCH", new String[]{"#15803d", "#fffa0f"}),
        Map.entry("MULTICHOICE_S", new String[]{"#15803d", "#ffaa0f"}),
        Map.entry("MCS", new String[]{"#15803d", "#ffaa0f"}),
        Map.entry("MULTICHOICE_VS", new String[]{"#15803d", "#aaffff"}),
        Map.entry("MCVS", new String[]{"#15803d", "#ffff8f"}),
        Map.entry("MULTICHOICE_HS", new String[]{"#15803d", "#dddd4d"}),
        Map.entry("MCHS", new String[]{"#15803d", "#dddd4d"}),

        Map.entry("MULTIRESPONSE", new String[]{"#7e22ce", "#ffffff"}),
        Map.entry("MR", new String[]{"#7e22ce", "#ffffff"}),
        Map.entry("MULTIRESPONSE_H", new String[]{"#7e22ce", "#fffaaf"}),
        Map.entry("MRH", new String[]{"#7e22ce", "#fffaaf"}),
        Map.entry("MULTIRESPONSE_S", new String[]{"#7e22ce", "#ffaa0f"}),
        Map.entry("MRS", new String[]{"#7e22ce", "#ffaa0f"}),
        Map.entry("MULTIRESPONSE_HS", new String[]{"#7e22ce", "#aaff8f"}),
        Map.entry("MRHS", new String[]{"#7e22ce", "#aaff8f"})
    );

    private static final String[][] CLOZE_LEGEND_ROWS = {
        { "SHORTANSWER",    "Respuesta corta",                              "SA, MW" },
        { "SHORTANSWER_C",  "Respuesta corta (con distinción mayúsc.)",     "SAC, MWC" },
        { "NUMERICAL",      "Numérica",                                     "NM" },
        { "MULTICHOICE",    "Opción múltiple – desplegable",                "MC, MCS" },
        { "MULTICHOICE_V",  "Opción múltiple – vertical (radio)",           "MCV, MCVS" },
        { "MULTICHOICE_H",  "Opción múltiple – horizontal (radio)",         "MCH, MCHS" },
        { "MULTIRESPONSE",  "Respuesta múltiple – vertical (checkbox)",     "MR, MRS" },
        { "MULTIRESPONSE_H", "Respuesta múltiple – horizontal (checkbox)",  "MRH, MRHS" }
    };

    private String buildClozeColors() {
        StringBuilder sb = new StringBuilder();
        sb.append("<div style='font-size:0.8em; color:#555; margin-bottom:15px; ")
          .append("border:1px solid #dee2e6; border-radius:4px; padding:8px 10px; ")
          .append("background:#f8f9fa;'>");
        sb.append("<strong>Leyenda de subpreguntas cloze:</strong>");
        sb.append("<table style='margin-top:6px; border-collapse:collapse; width:100%;'>");
        for (String[] row : CLOZE_LEGEND_ROWS) {
            String[] c = CLOZE_TYPE_COLORS.getOrDefault(row[0], new String[]{"#888", "#fff"});
            sb.append("<tr>")
              .append("<td style='padding:2px 8px;white-space:nowrap;'>")
              .append("<span style='background:").append(c[0]).append(";color:").append(c[1])
              .append(";border-radius:3px;padding:1px 5px;font-size:0.9em;'>")
              .append(row[0]).append("</span></td>")
              .append("<td style='padding:2px 8px;'>").append(row[1]).append("</td>")
              .append("<td style='padding:2px 8px;color:#999;font-style:italic;'>").append(row[2]).append("</td>")
              .append("</tr>");
        }
        sb.append("</table></div>");
        return sb.toString();
    }

    private static final Pattern CLOZE_TOKEN_PAT = Pattern.compile(
        "\\{([0-9]*):" +
        "(NUMERICAL|NM" +
        "|MULTICHOICE_VS|MCVS|MULTICHOICE_HS|MCHS" +
        "|MULTICHOICE_V|MCV|MULTICHOICE_H|MCH" +
        "|MULTICHOICE_S|MCS|MULTICHOICE|MC" +
        "|SHORTANSWER_C|SAC|MWC|SHORTANSWER|SA|MW" +
        "|MULTIRESPONSE_HS|MRHS|MULTIRESPONSE_H|MRH" +
        "|MULTIRESPONSE_S|MRS|MULTIRESPONSE|MR)" +
        ":(.+?)(?<!\\\\)\\}",
        Pattern.DOTALL
    );

    private static class ClozeAlt {
        final double fraction;
        final String text;
        final String feedback;

        ClozeAlt(double fraction, String text, String feedback) {
            this.fraction = fraction;
            this.text = text;
            this.feedback = feedback != null ? feedback : "";
        }
    }

    private static List<ClozeAlt> parseClozeAlternatives(String raw) {
        List<ClozeAlt> result = new ArrayList<>();
        String[] parts = raw.split("(?<!\\\\)~");
        for (String part : parts) {
            part = part.trim();
            if (part.isEmpty()) continue;

            double fraction = 0.0;
            String text;
            String feedback = "";

            if (part.startsWith("=")) {
                fraction = 1.0;
                part = part.substring(1);
            } else {
                Matcher pm = Pattern.compile("^%(-?[0-9]+(?:[.,][0-9]*)?)%").matcher(part);
                if (pm.find()) {
                    fraction = Double.parseDouble(pm.group(1).replace(',', '.')) / 100.0;
                    part = part.substring(pm.end());
                }
            }

            int hashIdx = -1;
            for (int i = 0; i < part.length(); i++) {
                if (part.charAt(i) == '#' && (i == 0 || part.charAt(i - 1) != '\\')) {
                    hashIdx = i;
                    break;
                }
            }
            if (hashIdx >= 0) {
                feedback = part.substring(hashIdx + 1).replace("\\#", "#");
                text = part.substring(0, hashIdx);
            } else {
                text = part;
            }

            text = text.replace("\\}", "}").replace("\\~", "~").replace("\\=", "=");
            result.add(new ClozeAlt(fraction, text, feedback));
        }
        return result;
    }

    private int clozeWidgetCounter = 0;

    private String buildClozeWidget(String typeKey, String points, List<ClozeAlt> alts) {
        clozeWidgetCounter++;
        String id = "cq" + clozeWidgetCounter;
        String[] colors = CLOZE_TYPE_COLORS.getOrDefault(typeKey, new String[]{"#888", "#fff"});
        String bg = colors[0];
        String fg = colors[1];

        String badge =
            "<span title='" + points + " pt' style='" +
            "background:" + bg + ";color:" + fg + ";" +
            "border-radius:3px;padding:0 4px;font-size:0.75em;" +
            "vertical-align:middle;margin-right:2px;font-family:monospace;" +
            "'>" + escapeHtmlAttr(typeKey) + "</span>";

        StringBuilder widget = new StringBuilder();

        if (typeKey.matches("SHORTANSWER|SA|MW|SHORTANSWER_C|SAC|MWC|NUMERICAL|NM")) {
            String correct = alts.stream()
                .filter(a -> a.fraction == 1.0)
                .map(a -> a.text.replaceAll("<[^>]+>", ""))
                .findFirst()
                .orElseGet(() -> alts.stream()
                    .max(Comparator.comparingDouble(a -> a.fraction))
                    .map(a -> a.text.replaceAll("<[^>]+>", ""))
                    .orElse(""));

            int size = Math.max(10, Math.min(correct.length() + 6, 40));
            widget.append("<input type='text' id='").append(id)
                  .append("' size='").append(size)
                  .append("' placeholder='").append(escapeHtmlAttr(correct)).append("'")
                  .append(" style='border:1px solid #999;border-radius:3px;padding:1px 3px;")
                  .append("font-style:italic;color:#666;' />");
        } else if (typeKey.matches("MULTICHOICE|MC|MULTICHOICE_S|MCS")) {
            widget.append("<select id='").append(id)
                  .append("' style='border:1px solid #999;border-radius:3px;padding:1px;'>")
                  .append("<option value=''>Selecciona...</option>");
            for (int i = 0; i < alts.size(); i++) {
                ClozeAlt a = alts.get(i);
                boolean correct = a.fraction == 1.0;
                String pct = formatFraction(a.fraction);
                widget.append("<option value='").append(i).append("'")
                      .append(correct ? " selected" : "")
                      .append(" title='").append(pct).append("'")
                      .append(" style='")
                      .append(correct ? "color:#15803d;font-weight:bold;" : "color:#333;")
                      .append("'>")
                      .append(correct ? "✓ " : "")
                      .append(a.text)
                      .append("</option>");
            }
            widget.append("</select>");
        } else if (typeKey.matches("MULTICHOICE_V|MCV|MULTICHOICE_VS|MCVS")) {
            widget.append("<span style='display:inline-block;vertical-align:top;'>");
            for (int i = 0; i < alts.size(); i++) {
                ClozeAlt a = alts.get(i);
                boolean correct = a.fraction == 1.0;
                String pct = formatFraction(a.fraction);
                widget.append("<label style='display:block;'>")
                      .append("<input type='radio' name='").append(id).append("' value='").append(i).append("'")
                      .append(correct ? " checked" : "").append("> ")
                      .append("<small style='color:#c00;margin-right:3px;'>(").append(pct).append(")</small>")
                      .append(a.text)
                      .append("</label>");
            }
            widget.append("</span>");
        } else if (typeKey.matches("MULTICHOICE_H|MCH|MULTICHOICE_HS|MCHS")) {
            widget.append("<span style='display:inline-block;vertical-align:top;'>");
            for (int i = 0; i < alts.size(); i++) {
                ClozeAlt a = alts.get(i);
                boolean correct = a.fraction == 1.0;
                String pct = formatFraction(a.fraction);
                widget.append("<label style='margin-right:10px;'>")
                      .append("<input type='radio' name='").append(id).append("' value='").append(i).append("'")
                      .append(correct ? " checked" : "").append("> ")
                      .append("<small style='color:#c00;margin-right:3px;'>(").append(pct).append(")</small>")
                      .append(a.text)
                      .append("</label>");
            }
            widget.append("</span>");
        } else if (typeKey.matches("MULTIRESPONSE|MR|MULTIRESPONSE_S|MRS")) {
            widget.append("<span style='display:inline-block;vertical-align:top;'>");
            for (int i = 0; i < alts.size(); i++) {
                ClozeAlt a = alts.get(i);
                boolean correct = a.fraction > 0;
                String pct = formatFraction(a.fraction);
                widget.append("<label style='display:block;'>")
                      .append("<input type='checkbox' name='").append(id).append("' value='").append(i).append("'")
                      .append(correct ? " checked" : "").append("> ")
                      .append("<small style='color:#c00;margin-right:3px;'>(").append(pct).append(")</small>")
                      .append(a.text)
                      .append("</label>");
            }
            widget.append("</span>");
        } else if (typeKey.matches("MULTIRESPONSE_H|MRH|MULTIRESPONSE_HS|MRHS")) {
            widget.append("<span style='display:inline-block;vertical-align:top;'>");
            for (int i = 0; i < alts.size(); i++) {
                ClozeAlt a = alts.get(i);
                boolean correct = a.fraction > 0;
                String pct = formatFraction(a.fraction);
                widget.append("<label style='margin-right:10px;'>")
                      .append("<input type='checkbox' name='").append(id).append("' value='").append(i).append("'")
                      .append(correct ? " checked" : "").append("> ")
                      .append("<small style='color:#c00;margin-right:3px;'>(").append(pct).append(")</small>")
                      .append(a.text)
                      .append("</label>");
            }
            widget.append("</span>");
        }

        return badge + widget;
    }

    private static String formatFraction(double f) {
        double pct = f * 100.0;
        if (pct == Math.floor(pct)) {
            return (int) pct + "%";
        }
        return String.format(Locale.US, "%.1f%%", pct);
    }

    private String renderCloze(String html) {
        clozeWidgetCounter = 0;
        Matcher m = CLOZE_TOKEN_PAT.matcher(html);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String typeKey = m.group(2).toUpperCase();
            String points = m.group(1).isEmpty() ? "1" : m.group(1);
            String rawAlts = m.group(3);
            List<ClozeAlt> alts = parseClozeAlternatives(rawAlts);
            String widget = buildClozeWidget(typeKey, points, alts);
            m.appendReplacement(sb, Matcher.quoteReplacement(widget));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String highlightCloze(String html) {
        Matcher m = CLOZE_TOKEN_PAT.matcher(html);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String typeKey = m.group(2).toUpperCase();
            String[] colors = CLOZE_TYPE_COLORS.getOrDefault(typeKey, new String[]{"#9f1239", "#ffffff"});
            String bgColor = colors[0];
            String fgColor = colors[1];

            String points = m.group(1).isEmpty() ? "1" : m.group(1);
            String title = points + " pt · " + m.group(2);
            String header = escapeHtmlAttr("{" + points + ":" + m.group(2) + ":");
            String inner = m.group(0)
                .replaceFirst("\\{[0-9]*:[^:]+:", "")
                .replaceAll("\\}$", "");

            String span =
                "<span title='" + title + "' style='" +
                "background:" + bgColor + ";" +
                "color:" + fgColor + ";" +
                "border:1px solid rgba(0,0,0,0.25);" +
                "border-radius:3px;" +
                "padding:1px 4px;" +
                "font-family:monospace;" +
                "font-size:0.85em;" +
                "'>" +
                "<span style='opacity:0.75'>" + header + "</span>" +
                inner +
                "<span style='opacity:0.75'>}</span>" +
                "</span>";

            m.appendReplacement(sb, Matcher.quoteReplacement(span));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String escapeHtmlAttr(String text) {
        return text
            .replace("&", "&amp;")
            .replace("'", "&#39;")
            .replace("\"", "&quot;");
    }

    public static void main(String[] args) {
        launch(args);
    }
}
