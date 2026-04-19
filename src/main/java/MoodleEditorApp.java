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
        // Si es una pregunta de tipo cloze, resaltamos los tokens de sintaxis
        if ("cloze".equals(q.getType())) {
            body = highlightCloze(body);
        }
        html.append("<div style='margin-bottom: 20px;'>").append(body).append("</div>");

        // Si es cloze, mostramos una leyenda de colores
        if ("cloze".equals(q.getType())) {
            html.append("<div style='font-size:0.8em; color:#555; margin-bottom:15px; " +
                        "border:1px solid #dee2e6; border-radius:4px; padding:6px 10px; " +
                        "background:#f8f9fa;'>");
            html.append("<strong>Leyenda de tokens cloze:</strong>&nbsp;");
            html.append("<span style='background:#d1ecf1;border:1px solid #aaa;border-radius:3px;" +
                        "padding:1px 6px;margin-right:6px;'>Respuesta corta</span>");
            html.append("<span style='background:#cce5ff;border:1px solid #aaa;border-radius:3px;" +
                        "padding:1px 6px;margin-right:6px;'>Resp. corta (sensible a mayúsc.)</span>");
            html.append("<span style='background:#d4edda;border:1px solid #aaa;border-radius:3px;" +
                        "padding:1px 6px;margin-right:6px;'>Opción múltiple</span>");
            html.append("<span style='background:#fff3cd;border:1px solid #aaa;border-radius:3px;" +
                        "padding:1px 6px;margin-right:6px;'>Numérica</span>");
            html.append("<span style='background:#e2d9f3;border:1px solid #aaa;border-radius:3px;" +
                        "padding:1px 6px;'>Respuesta múltiple</span>");
            html.append("</div>");
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
    // Soporte para sintaxis Cloze (multianswer)
    // Extraído y adaptado de MultiAnswerExtract.java
    // -----------------------------------------------------------------------

    /** Paleta de colores de fondo para los tokens cloze, por tipo de subpregunta. */
    private static final Map<String, String> CLOZE_COLORS = Map.ofEntries(
        Map.entry("NUMERICAL",              "#fff3cd"), // amarillo
        Map.entry("NM",                     "#fff3cd"),
        Map.entry("SHORTANSWER",            "#d1ecf1"), // azul claro
        Map.entry("SA",                     "#d1ecf1"),
        Map.entry("MW",                     "#d1ecf1"),
        Map.entry("SHORTANSWER_C",          "#cce5ff"), // azul medio
        Map.entry("SAC",                    "#cce5ff"),
        Map.entry("MWC",                    "#cce5ff"),
        Map.entry("MULTICHOICE",            "#d4edda"), // verde
        Map.entry("MC",                     "#d4edda"),
        Map.entry("MULTICHOICE_V",          "#d4edda"),
        Map.entry("MCV",                    "#d4edda"),
        Map.entry("MULTICHOICE_H",          "#d4edda"),
        Map.entry("MCH",                    "#d4edda"),
        Map.entry("MULTICHOICE_S",          "#d4edda"),
        Map.entry("MCS",                    "#d4edda"),
        Map.entry("MULTICHOICE_VS",         "#d4edda"),
        Map.entry("MCVS",                   "#d4edda"),
        Map.entry("MULTICHOICE_HS",         "#d4edda"),
        Map.entry("MCHS",                   "#d4edda"),
        Map.entry("MULTIRESPONSE",          "#e2d9f3"), // violeta
        Map.entry("MR",                     "#e2d9f3"),
        Map.entry("MULTIRESPONSE_H",        "#e2d9f3"),
        Map.entry("MRH",                    "#e2d9f3"),
        Map.entry("MULTIRESPONSE_S",        "#e2d9f3"),
        Map.entry("MRS",                    "#e2d9f3"),
        Map.entry("MULTIRESPONSE_HS",       "#e2d9f3"),
        Map.entry("MRHS",                   "#e2d9f3")
    );

    // Expresión regular que detecta tokens cloze completos en el enunciado.
    // Replica ANSWER_REGEX de MultiAnswerExtract, compilada una sola vez.
    private static final Pattern CLOZE_TOKEN_PAT = Pattern.compile(
        "\\{([0-9]*):" +
        "(NUMERICAL|NM" +
        "|MULTICHOICE_VS|MCVS|MULTICHOICE_HS|MCHS" +
        "|MULTICHOICE_V|MCV|MULTICHOICE_H|MCH" +
        "|MULTICHOICE_S|MCS|MULTICHOICE|MC" +
        "|SHORTANSWER_C|SAC|MWC|SHORTANSWER|SA|MW" +
        "|MULTIRESPONSE_HS|MRHS|MULTIRESPONSE_H|MRH" +
        "|MULTIRESPONSE_S|MRS|MULTIRESPONSE|MR)" +
        ":.*?(?<!\\\\)\\}",
        Pattern.DOTALL
    );

    /**
     * Recorre el texto HTML de un enunciado cloze y envuelve cada token
     * {@code {puntos:TIPO:alternativas}} en un {@code <span>} con fondo de
     * color según el tipo de subpregunta, para facilitar la lectura en el
     * panel de vista previa.
     *
     * @param html texto HTML del enunciado (tal como sale del XML de Moodle)
     * @return texto HTML con los tokens cloze resaltados
     */
    private String highlightCloze(String html) {
        Matcher m = CLOZE_TOKEN_PAT.matcher(html);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String token    = m.group(0);
            String typeKey  = m.group(2).toUpperCase();
            String bgColor  = CLOZE_COLORS.getOrDefault(typeKey, "#f8d7da");

            // Título del tooltip: muestra puntos y tipo
            String points = m.group(1).isEmpty() ? "1" : m.group(1);
            String title  = points + " pt · " + m.group(2);

            String span =
                "<span title='" + title + "' style='" +
                "background:" + bgColor + ";" +
                "border:1px solid #aaa;" +
                "border-radius:3px;" +
                "padding:1px 4px;" +
                "font-family:monospace;" +
                "font-size:0.85em;" +
                "white-space:nowrap;" +
                "'>" + escapeHtmlForSpan(token) + "</span>";

            m.appendReplacement(sb, Matcher.quoteReplacement(span));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Escapa los caracteres HTML dentro del texto literal del token cloze. */
    private static String escapeHtmlForSpan(String text) {
        return text
            .replace("&",  "&amp;")
            .replace("<",  "&lt;")
            .replace(">",  "&gt;")
            .replace("\"", "&quot;");
    }

    public static void main(String[] args) { launch(args); }
}
