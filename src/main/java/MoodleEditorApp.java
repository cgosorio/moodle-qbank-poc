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

import javax.xml.parsers.DocumentBuilder;
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
    private Map<String, ObservableList<MoodleQuestion>> categoryData = new HashMap<>();
    private TreeItem<String> draggedItem;

    @Override
    public void start(Stage primaryStage) {
        // --- Interfaz Superior ---
        Button btnLoad = new Button("Cargar XML");
        btnLoad.setOnAction(e -> openFileChooser(primaryStage));
        Button btnSave = new Button("Guardar XML");
        btnSave.setStyle("-fx-background-color: #d1e7dd;");
        btnSave.setOnAction(e -> saveFileChooser(primaryStage));
        ToolBar toolBar = new ToolBar(btnLoad, btnSave);

        // --- Árbol (Izquierda) ---
        TreeItem<String> rootItem = new TreeItem<>("Banco de Preguntas");
        rootItem.setExpanded(true);
        treeView = new TreeView<>(rootItem);
        setupTreeCellFactory();
        setupTreeContextMenu();
        treeView.getSelectionModel().selectedItemProperty().addListener((obs, ov, nv) -> {
            if (nv != null) updateTable(getFullPath(nv));
        });

        // --- Tabla (Derecha Arriba) ---
        tableView = new TableView<>();
        setupTableColumns();
        setupTableDragSource();

        // --- Detalle (Derecha Abajo) ---
        webView = new WebView();
        editorArea = new TextArea();
        VBox detailBox = new VBox(10, new Label("Vista Previa (Imágenes renderizadas):"), webView, new Label("Código HTML:"), editorArea);
        VBox.setVgrow(webView, Priority.ALWAYS);
        VBox.setVgrow(editorArea, Priority.ALWAYS);
        detailBox.setPadding(new Insets(10));

        // --- Layout Principal ---
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
                Dragboard db = event.getDragboard();
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
            // Obtenemos todas las preguntas seleccionadas (pueden ser 2, 10, o 500)
            ObservableList<MoodleQuestion> selected = tableView.getSelectionModel().getSelectedItems();
            
            if (!selected.isEmpty()) {
                draggedItem = null; // Confirmamos que no es una categoría del árbol
                
                Dragboard db = tableView.startDragAndDrop(TransferMode.MOVE);
                ClipboardContent content = new ClipboardContent();
                
                // Ponemos un identificador en el portapapeles
                content.putString("MULTIPLE_QUESTIONS"); 
                db.setContent(content);
                
                event.consume();
            }
        });
    }

    private void moveCategory(TreeItem<String> source, TreeItem<String> target) {
        if (source == null || target == null || source == target) return;
        TreeItem<String> temp = target;
        while (temp != null) { if (temp == source) return; temp = temp.getParent(); }

        String oldRootPath = getFullPath(source);
        source.getParent().getChildren().remove(source);
        target.getChildren().add(source);
        updateMapPaths(oldRootPath, getFullPath(source));
        target.setExpanded(true);
    }

    private void updateMapPaths(String oldPath, String newPath) {
        List<String> keysToMove = new ArrayList<>();
        for (String key : categoryData.keySet()) if (key.startsWith(oldPath)) keysToMove.add(key);
        for (String oldKey : keysToMove) {
            String newKey = oldKey.replaceFirst(java.util.regex.Pattern.quote(oldPath), newPath);
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
            // Copiamos a una lista temporal para evitar errores de modificación concurrente
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
            loadXML(f); 
        }
    }

    private void saveFileChooser(Stage stage) {
        FileChooser fc = new FileChooser();
        fc.setInitialFileName("Banco_Actualizado.xml");
        File f = fc.showSaveDialog(stage);
        if (f != null) exportToXML(f);
    }

// ... dentro de la clase MoodleEditorApp ...

    private void loadXML(File file) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            Document doc = dbf.newDocumentBuilder().parse(file);
            doc.getDocumentElement().normalize();
            NodeList nl = doc.getElementsByTagName("question");
            String curP = "Banco de Preguntas";

            for (int i = 0; i < nl.getLength(); i++) {
                Node node = nl.item(i);
                if (node instanceof Element el) {
                    if ("category".equals(el.getAttribute("type"))) {
                        curP = buildTreeFromMoodlePath(getVal(el, "category"));
                    } else {
                        // Pasamos el elemento 'el' completo al constructor
                        MoodleQuestion q = new MoodleQuestion(
                            el.getAttribute("type"), 
                            getVal(el, "name"), 
                            getVal(el, "questiontext"),
                            el 
                        );
                        
                        NodeList fileNodes = el.getElementsByTagName("file");
                        for (int j = 0; j < fileNodes.getLength(); j++) {
                            Element fEl = (Element) fileNodes.item(j);
                            q.getFiles().add(new MoodleFile(fEl.getAttribute("name"), fEl.getAttribute("path"), 
                                            fEl.getAttribute("encoding"), fEl.getTextContent()));
                        }
                        categoryData.computeIfAbsent(curP, k -> FXCollections.observableArrayList()).add(q);
                    }
                }
            }
            treeView.refresh();
        } catch (Exception e) { e.printStackTrace(); }
    }

    // EL MÉTODO QUE FALTABA
    private String getVal(Element p, String tag) {
        try {
            NodeList nl = p.getElementsByTagName(tag);
            if (nl.getLength() > 0 && nl.item(0) instanceof Element) {
                NodeList textList = ((Element) nl.item(0)).getElementsByTagName("text");
                if (textList.getLength() > 0) {
                    return textList.item(0).getTextContent();
                }
            }
        } catch (Exception e) {} 
        return "";
    }

    private void exportToXML(File file) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            Document doc = dbf.newDocumentBuilder().newDocument();
            Element root = doc.createElement("quiz");
            doc.appendChild(root);

            traverseAndExport(treeView.getRoot(), root, doc);

            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
            transformer.transform(new DOMSource(doc), new StreamResult(file));
            
            new Alert(Alert.AlertType.INFORMATION, "Archivo exportado correctamente para Moodle.").show();
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void traverseAndExport(TreeItem<String> item, Element root, Document doc) {
        if (item != treeView.getRoot()) {
            String path = getFullPath(item);
            
            // 1. Exportar Nodo de Categoría
            Element catQ = doc.createElement("question");
            catQ.setAttribute("type", "category");
            Element cat = doc.createElement("category");
            Element txt = doc.createElement("text");
            txt.setTextContent("$module$/top/" + path.replace("Banco de Preguntas/", ""));
            cat.appendChild(txt); 
            catQ.appendChild(cat); 
            root.appendChild(catQ);

            // 2. Exportar Preguntas (Copia profunda del XML original)
            List<MoodleQuestion> qs = categoryData.get(path);
            if (qs != null) {
                for (MoodleQuestion q : qs) {
                    // Esta línea es la que salva las respuestas y calificaciones
                    Node importedNode = doc.importNode(q.getOriginalElement(), true);
                    root.appendChild(importedNode);
                }
            }
        }
        for (TreeItem<String> child : item.getChildren()) {
            traverseAndExport(child, root, doc);
        }
    }

    private void addHtmlElement(Document doc, Element parent, String tag, String content) {
        Element el = doc.createElement(tag);
        el.setAttribute("format", "html");
        Element txt = doc.createElement("text");
        txt.appendChild(doc.createCDATASection(content));
        el.appendChild(txt);
        parent.appendChild(el);
    }
    
    private void addTextElement(Document doc, Element parent, String tag, String content) {
        Element el = doc.createElement(tag);
        Element txt = doc.createElement("text");
        txt.setTextContent(content);
        el.appendChild(txt);
        parent.appendChild(el);
    }

    private void updateDetail(MoodleQuestion q) {
        if (q == null) return;

        StringBuilder html = new StringBuilder();
        html.append("<html><head>");
    
        // 0. INYECTAR MATHJAX (El cambio mínimo)
        // 0.a Carga de la librería
        html.append("<script src='https://cdn.jsdelivr.net/npm/mathjax@3/es5/tex-mml-chtml.js'></script>");
        // 0.b Configuración de MathHax
        /* html.append("<script>MathJax = { tex: { inlineMath: [['$', '$'], ['\\\\(', '\\\\)']], displayMath: [['$$', '$$'], ['\\\\[', '\\\\]']] } };</script>"); */
        html.append("<script>");
        html.append("MathJax = {");
        html.append("  tex: {");
        html.append("    inlineMath: [['$', '$'], ['$$', '$$'], ['\\\\(', '\\\\)']],"); // Añadimos $$ a inline
        html.append("    displayMath: []"); // Vaciamos displayMath para que no cree bloques
        html.append("  },");
        html.append("  chtml: { displayAlign: 'left' }"); // Alinea a la izquierda por si acaso
        html.append("};");
        html.append("</script>");
        
        // 0.c CSS para eliminar saltos de línea residualtes
        html.append("<style>");
        html.append("  body { font-family: sans-serif; padding: 10px; line-height: 1.4; }");
        html.append("  mjx-container[display=\"true\"] { margin: 0 !important; display: inline-block !important; }");
        html.append("  .MathJax { white-space: nowrap; }");
        html.append("</style>");

        // 1. Cabecera (Nombre y Tipo) - Igual que antes
        html.append("<div style='background: #f8f9fa; padding: 10px; border-bottom: 2px solid #dee2e6; margin-bottom: 15px;'>");
        html.append("<strong>Nombre:</strong> ").append(q.getName()).append("<br>");
        html.append("<strong>Tipo:</strong> <span style='color: #0d6efd;'>").append(q.getType()).append("</span>");
        html.append("</div>");

        // 2. Enunciado (Con soporte para imágenes y resaltado de tokens cloze)
        String body = q.getQuestionText();
        for (MoodleFile f : q.getFiles()) {
            String mime = f.name.toLowerCase().endsWith(".png") ? "image/png" : "image/jpeg";
            body = body.replace("@@PLUGINFILE@@/" + f.name, "data:" + mime + ";base64," + f.content);
        }
        // Si es una pregunta de tipo cloze, renderizamos los tokens como widgets
        if ("cloze".equals(q.getType())) {
            body = renderCloze(body);
        }
        html.append("<div style='margin-bottom: 20px;'>").append(body).append("</div>");

        // Si es cloze, mostramos una leyenda de colores generada desde CLOZE_TYPE_COLORS
        if ("cloze".equals(q.getType())) {
            html.append(buildClozeColors());
        }

        // 3. Lógica de Respuestas según el tipo
        if ("matching".equals(q.getType())) {
            // --- CASO PREGUNTAS DE EMPAREJAMIENTO ---
            html.append("<h4>Pares de Emparejamiento:</h4>");
            html.append("<table border='1' style='border-collapse: collapse; width: 100%; font-size: 0.9em;'>");
            html.append("<tr style='background: #e9ecef;'><th style='padding: 5px;'>Pregunta / Estímulo</th><th style='padding: 5px;'>Respuesta Correcta</th></tr>");

            NodeList subquestions = q.getOriginalElement().getElementsByTagName("subquestion");
            for (int i = 0; i < subquestions.getLength(); i++) {
                Element sub = (Element) subquestions.item(i);
                String subText = getNestedText(sub); // Texto del estímulo
                
                // La respuesta correcta en emparejamiento está en un subnodo <answer><text>
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
            // --- CASO OPCIÓN MÚLTIPLE / VERDADERO-FALSO (Nodo <answer>) ---
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
                        if (f > 0) color = "green"; else if (f < 0) color = "red";
                    } catch (Exception e) {}

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

    // Método auxiliar para obtener valores de tags simples sin <text>
    private String getTagValue(Element parent, String tag) {
        NodeList nl = parent.getElementsByTagName(tag);
        if (nl.getLength() > 0) return nl.item(0).getTextContent();
        return "";
    }

    /**
    * Busca el primer nodo <text> dentro de un elemento y devuelve su contenido.
    * Útil para extraer el contenido de <answer>, <feedback>, <name>, etc.
    */
    private String getNestedText(Element parent) {
        if (parent == null) return "";
        NodeList nl = parent.getElementsByTagName("text");
        if (nl.getLength() > 0) {
            return nl.item(0).getTextContent();
        }
        // Si no tiene subnodo <text>, intentamos el contenido directo (por si acaso)
        return parent.getTextContent() != null ? parent.getTextContent().trim() : "";
    }

    private void setupTableColumns() {
        // Habilitar selección múltiple
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
        if (alert.showAndWait().get() == ButtonType.YES) {
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
        // Usamos una variable final para la lambda
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
                Alert alert = new Alert(Alert.AlertType.WARNING, "El nombre no puede estar vacío.");
                alert.show();
            }
        });
    }

    private void deleteCategory(TreeItem<String> item) {
        if (item == null || item == treeView.getRoot()) return;

        String path = getFullPath(item);
        
        // 1. Contar preguntas en esta categoría específica
        int numPreguntas = categoryData.getOrDefault(path, FXCollections.observableArrayList()).size();
        
        // 2. Contar subcategorías (descendientes directos e indirectos)
        // Restamos 1 porque el método cuenta también el item actual
        int numSubcategorias = countSubcategories(item) - 1;

        // 3. Preparar el mensaje dinámico
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
            // Limpiamos los datos del mapa para esta ruta y todas sus descendientes
            categoryData.keySet().removeIf(key -> key.startsWith(path));
            
            // Eliminamos del árbol
            item.getParent().getChildren().remove(item);
            
            // Refrescamos la tabla por si estábamos visualizando esa categoría
            tableView.setItems(FXCollections.observableArrayList());
            treeView.refresh();
        }
    }

    /**
    * Método auxiliar recursivo para contar todos los nodos hijos
    */
    private int countSubcategories(TreeItem<String> item) {
        int count = 1; // Se cuenta a sí mismo
        for (TreeItem<String> child : item.getChildren()) {
            count += countSubcategories(child);
        }
        return count;
    }

    private void expandRecursive(TreeItem<String> i, boolean e) {
        if (i == null) return; i.setExpanded(e);
        for (TreeItem<String> c : i.getChildren()) expandRecursive(c, e);
    }

    private String getNestedTagValue(Element p, String t) {
        try {
            NodeList nl = p.getElementsByTagName(t);
            if (nl.getLength() > 0 && nl.item(0) instanceof Element) {
                NodeList textList = ((Element) nl.item(0)).getElementsByTagName("text");
                if (textList.getLength() > 0) return textList.item(0).getTextContent();
            }
        } catch (Exception e) {} return "";
    }

    private String buildTreeFromMoodlePath(String mp) {
        String[] parts = mp.replace("$module$/top/", "").split("/");
        TreeItem<String> cur = treeView.getRoot();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            TreeItem<String> found = null;
            for (TreeItem<String> c : cur.getChildren()) if (c.getValue().equals(p)) { found = c; break; }
            if (found == null) { found = new TreeItem<>(p); cur.getChildren().add(found); }
            cur = found;
        }
        return getFullPath(cur);
    }

    private String getFullPath(TreeItem<String> i) {
        if (i == null || i.getParent() == null) return i != null ? i.getValue() : "";
        return getFullPath(i.getParent()) + "/" + i.getValue();
    }

    private void updateTable(String p) { tableView.setItems(categoryData.getOrDefault(p, FXCollections.observableArrayList())); }

    // -----------------------------------------------------------------------
    // Soporte para sintaxis Cloze (type="cloze")
    // Extraído y adaptado de MultiAnswerExtract.java
    // -----------------------------------------------------------------------

    /**
     * Color de fondo de la etiqueta de tipo para cada familia cloze.
     * Solo se usa en la etiqueta de identificación visible antes del widget,
     * no en el widget en sí (que se renderiza como HTML neutro).
     * Cada entrada: { color-de-fondo, color-de-texto }
     */
    private static final Map<String, String[]> CLOZE_TYPE_COLORS = Map.ofEntries(
        Map.entry("NUMERICAL",              new String[]{"#b45309", "#ffffff"}),
        Map.entry("NM",                     new String[]{"#b45309", "#ffffff"}),
        Map.entry("SHORTANSWER",            new String[]{"#1d4ed8", "#ffffff"}),
        Map.entry("SA",                     new String[]{"#1d4ed8", "#ffffff"}),
        Map.entry("MW",                     new String[]{"#1d4ed8", "#ffffff"}),
        Map.entry("SHORTANSWER_C",          new String[]{"#0e7490", "#ffffff"}),
        Map.entry("SAC",                    new String[]{"#0e7490", "#ffffff"}),
        Map.entry("MWC",                    new String[]{"#0e7490", "#ffffff"}),
        Map.entry("MULTICHOICE",            new String[]{"#15803d", "#ffffff"}),
        Map.entry("MC",                     new String[]{"#15803d", "#ffffff"}),
        Map.entry("MULTICHOICE_V",          new String[]{"#15803d", "#ffffff"}),
        Map.entry("MCV",                    new String[]{"#15803d", "#ffffff"}),
        Map.entry("MULTICHOICE_H",          new String[]{"#15803d", "#ffffff"}),
        Map.entry("MCH",                    new String[]{"#15803d", "#ffffff"}),
        Map.entry("MULTICHOICE_S",          new String[]{"#15803d", "#ffffff"}),
        Map.entry("MCS",                    new String[]{"#15803d", "#ffffff"}),
        Map.entry("MULTICHOICE_VS",         new String[]{"#15803d", "#ffffff"}),
        Map.entry("MCVS",                   new String[]{"#15803d", "#ffffff"}),
        Map.entry("MULTICHOICE_HS",         new String[]{"#15803d", "#ffffff"}),
        Map.entry("MCHS",                   new String[]{"#15803d", "#ffffff"}),
        Map.entry("MULTIRESPONSE",          new String[]{"#7e22ce", "#ffffff"}),
        Map.entry("MR",                     new String[]{"#7e22ce", "#ffffff"}),
        Map.entry("MULTIRESPONSE_H",        new String[]{"#7e22ce", "#ffffff"}),
        Map.entry("MRH",                    new String[]{"#7e22ce", "#ffffff"}),
        Map.entry("MULTIRESPONSE_S",        new String[]{"#7e22ce", "#ffffff"}),
        Map.entry("MRS",                    new String[]{"#7e22ce", "#ffffff"}),
        Map.entry("MULTIRESPONSE_HS",       new String[]{"#7e22ce", "#ffffff"}),
        Map.entry("MRHS",                   new String[]{"#7e22ce", "#ffffff"})
    );

    /**
     * Filas de la leyenda: { clave canónica, descripción, aliases }.
     * El orden aquí es el orden de aparición en la leyenda.
     */
    private static final String[][] CLOZE_LEGEND_ROWS = {
        { "SHORTANSWER",   "Respuesta corta (sin distinción mayúsc.)",  "SA, MW"                        },
        { "SHORTANSWER_C", "Respuesta corta (con distinción mayúsc.)",  "SAC, MWC"                      },
        { "NUMERICAL",     "Numérica",                                  "NM"                            },
        { "MULTICHOICE",   "Opción múltiple – desplegable",             "MC, MCS"                       },
        { "MULTICHOICE_V", "Opción múltiple – vertical (radio)",        "MCV, MCVS"                     },
        { "MULTICHOICE_H", "Opción múltiple – horizontal (radio)",      "MCH, MCHS"                     },
        { "MULTIRESPONSE", "Respuesta múltiple – vertical (checkbox)",  "MR, MRS"                       },
        { "MULTIRESPONSE_H","Respuesta múltiple – horizontal (checkbox)","MRH, MRHS"                    },
    };

    /** Genera el HTML de la leyenda leyendo colores de {@link #CLOZE_TYPE_COLORS}. */
    private String buildClozeColors() {
        StringBuilder sb = new StringBuilder();
        sb.append("<div style='font-size:0.8em;color:#555;margin-bottom:15px;")
          .append("border:1px solid #dee2e6;border-radius:4px;padding:8px 10px;background:#f8f9fa;'>");
        sb.append("<strong>Leyenda de subpreguntas cloze:</strong>");
        sb.append("<table style='margin-top:6px;border-collapse:collapse;width:100%;'>");
        for (String[] row : CLOZE_LEGEND_ROWS) {
            String[] c = CLOZE_TYPE_COLORS.getOrDefault(row[0], new String[]{"#888","#fff"});
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

    // Patrón principal: captura el token cloze completo.
    // grupo 1 = puntuación  |  grupo 2 = tipo  |  grupo 3 = alternativas en bruto
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

    // Patrón de una alternativa individual: fracción opcional + texto + feedback opcional.
    // El separador entre alternativas es '~' (sin escapar).
    private static final Pattern CLOZE_ALT_PAT = Pattern.compile(
        "(?:=|%(-?[0-9]+(?:[.,][0-9]*)?)%)?" +   // fracción: '=' ó '%n%'
        "(.+?)(?<!\\\\)" +                         // texto de la alternativa
        "(?:#(.*?)(?<!\\\\))?" +                   // feedback opcional tras '#'
        "(?=~|$)",                                 // hasta '~' o fin de cadena
        Pattern.DOTALL
    );

    /**
     * Representa una alternativa parseada de un token cloze.
     */
    private static class ClozeAlt {
        final double fraction; // 1.0 = correcta, 0.0 = incorrecta, negativo = penalización
        final String text;     // texto HTML de la opción (puede contener <img>)
        final String feedback; // puede estar vacío

        ClozeAlt(double fraction, String text, String feedback) {
            this.fraction = fraction;
            this.text     = text;
            this.feedback = feedback != null ? feedback : "";
        }
    }

    /**
     * Parsea la cadena de alternativas de un token cloze en una lista de {@link ClozeAlt}.
     * Las alternativas se separan por '~' sin escapar.
     */
    private static List<ClozeAlt> parseClozeAlternatives(String raw) {
        List<ClozeAlt> result = new ArrayList<>();
        // Dividimos por '~' que no estén escapados con '\'
        String[] parts = raw.split("(?<!\\\\)~");
        for (String part : parts) {
            part = part.trim();
            if (part.isEmpty()) continue;

            double fraction = 0.0;
            String text;
            String feedback = "";

            // Detectar prefijo de fracción
            if (part.startsWith("=")) {
                fraction = 1.0;
                part = part.substring(1);
            } else {
                java.util.regex.Matcher pm = Pattern.compile("^%(-?[0-9]+(?:[.,][0-9]*)?)%").matcher(part);
                if (pm.find()) {
                    fraction = Double.parseDouble(pm.group(1).replace(',', '.')) / 100.0;
                    part = part.substring(pm.end());
                }
            }

            // Separar feedback (tras '#' no escapado)
            int hashIdx = -1;
            for (int i = 0; i < part.length(); i++) {
                if (part.charAt(i) == '#' && (i == 0 || part.charAt(i-1) != '\\')) {
                    hashIdx = i; break;
                }
            }
            if (hashIdx >= 0) {
                feedback = part.substring(hashIdx + 1).replace("\\#", "#");
                text     = part.substring(0, hashIdx);
            } else {
                text = part;
            }
            // Unescape
            text = text.replace("\\}", "}").replace("\\~", "~").replace("\\=", "=");

            result.add(new ClozeAlt(fraction, text, feedback));
        }
        return result;
    }

    /**
     * Genera un contador HTML de subpregunta único para el uso como id/name de input.
     */
    private int clozeWidgetCounter = 0;

    /**
     * Genera la etiqueta de tipo (badge) con su color y el widget HTML
     * que simula el comportamiento de Moodle en un cuestionario.
     *
     * @param typeKey tipo en mayúsculas tal como aparece en el XML (ej. "MULTICHOICE_V")
     * @param points  puntuación del token
     * @param alts    alternativas ya parseadas
     * @return HTML del badge + widget, para insertar inline en el enunciado
     */
    private String buildClozeWidget(String typeKey, String points, List<ClozeAlt> alts) {
        clozeWidgetCounter++;
        String id = "cq" + clozeWidgetCounter;
        String[] colors = CLOZE_TYPE_COLORS.getOrDefault(typeKey, new String[]{"#888","#fff"});
        String bg = colors[0], fg = colors[1];

        // Badge con tipo y puntuación
        String badge =
            "<span title='" + points + " pt' style='" +
            "background:" + bg + ";color:" + fg + ";" +
            "border-radius:3px;padding:0 4px;font-size:0.75em;" +
            "vertical-align:middle;margin-right:2px;font-family:monospace;" +
            "'>" + escapeHtmlAttr(typeKey) + "</span>";

        StringBuilder widget = new StringBuilder();

        // ── SHORTANSWER / SHORTANSWER_C / NUMERICAL ─────────────────────────
        if (typeKey.matches("SHORTANSWER|SA|MW|SHORTANSWER_C|SAC|MWC|NUMERICAL|NM")) {
            // Calculamos el ancho aproximado en caracteres de la respuesta más larga
            int maxLen = alts.stream()
                             .mapToInt(a -> a.text.replaceAll("<[^>]+>","").length())
                             .max().orElse(10);
            int size = Math.max(10, Math.min(maxLen + 4, 40));
            widget.append("<input type='text' id='").append(id)
                  .append("' size='").append(size)
                  .append("' style='border:1px solid #999;border-radius:3px;padding:1px 3px;' />");
        }

        // ── MULTICHOICE / MC / MCS  → desplegable <select> ──────────────────
        else if (typeKey.matches("MULTICHOICE|MC|MULTICHOICE_S|MCS")) {
            widget.append("<select id='").append(id)
                  .append("' style='border:1px solid #999;border-radius:3px;padding:1px;'>")
                  .append("<option value=''>Selecciona...</option>");
            for (int i = 0; i < alts.size(); i++) {
                widget.append("<option value='").append(i).append("'>")
                      .append(alts.get(i).text)
                      .append("</option>");
            }
            widget.append("</select>");
        }

        // ── MULTICHOICE_V / MCV / MCVS → radio vertical ──────────────────────
        else if (typeKey.matches("MULTICHOICE_V|MCV|MULTICHOICE_VS|MCVS")) {
            widget.append("<span style='display:inline-block;vertical-align:top;'>");
            for (int i = 0; i < alts.size(); i++) {
                widget.append("<label style='display:block;'>")
                      .append("<input type='radio' name='").append(id).append("' value='").append(i).append("'> ")
                      .append(alts.get(i).text)
                      .append("</label>");
            }
            widget.append("</span>");
        }

        // ── MULTICHOICE_H / MCH / MCHS → radio horizontal ────────────────────
        else if (typeKey.matches("MULTICHOICE_H|MCH|MULTICHOICE_HS|MCHS")) {
            widget.append("<span style='display:inline-block;vertical-align:top;'>");
            for (int i = 0; i < alts.size(); i++) {
                widget.append("<label style='margin-right:10px;'>")
                      .append("<input type='radio' name='").append(id).append("' value='").append(i).append("'> ")
                      .append(alts.get(i).text)
                      .append("</label>");
            }
            widget.append("</span>");
        }

        // ── MULTIRESPONSE / MR / MRS → checkbox vertical ─────────────────────
        else if (typeKey.matches("MULTIRESPONSE|MR|MULTIRESPONSE_S|MRS")) {
            widget.append("<span style='display:inline-block;vertical-align:top;'>");
            for (int i = 0; i < alts.size(); i++) {
                widget.append("<label style='display:block;'>")
                      .append("<input type='checkbox' name='").append(id).append("' value='").append(i).append("'> ")
                      .append(alts.get(i).text)
                      .append("</label>");
            }
            widget.append("</span>");
        }

        // ── MULTIRESPONSE_H / MRH / MRHS → checkbox horizontal ───────────────
        else if (typeKey.matches("MULTIRESPONSE_H|MRH|MULTIRESPONSE_HS|MRHS")) {
            widget.append("<span style='display:inline-block;vertical-align:top;'>");
            for (int i = 0; i < alts.size(); i++) {
                widget.append("<label style='margin-right:10px;'>")
                      .append("<input type='checkbox' name='").append(id).append("' value='").append(i).append("'> ")
                      .append(alts.get(i).text)
                      .append("</label>");
            }
            widget.append("</span>");
        }

        return badge + widget.toString();
    }

    /**
     * Sustituye cada token cloze del enunciado HTML por su badge de tipo
     * más el widget HTML correspondiente (input, select, radios o checkboxes).
     */
    private String renderCloze(String html) {
        clozeWidgetCounter = 0; // reiniciamos para cada pregunta
        Matcher m = CLOZE_TOKEN_PAT.matcher(html);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String typeKey = m.group(2).toUpperCase();
            String points  = m.group(1).isEmpty() ? "1" : m.group(1);
            String rawAlts = m.group(3);

            List<ClozeAlt> alts = parseClozeAlternatives(rawAlts);
            String widget = buildClozeWidget(typeKey, points, alts);
            m.appendReplacement(sb, Matcher.quoteReplacement(widget));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Escapa caracteres especiales para uso en atributos HTML (title, value, etc.). */
    private static String escapeHtmlAttr(String text) {
        return text
            .replace("&",  "&amp;")
            .replace("'",  "&#39;")
            .replace("\"", "&quot;");
    }

    public static void main(String[] args) { launch(args); }
}
