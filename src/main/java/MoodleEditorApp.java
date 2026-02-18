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
        Button btnSave = new Button("Guardar XML con Imágenes");
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
            if (tableView.getSelectionModel().getSelectedItem() != null) {
                draggedItem = null; // Indica que movemos una pregunta, no una categoría
                Dragboard db = tableView.startDragAndDrop(TransferMode.MOVE);
                ClipboardContent content = new ClipboardContent();
                content.putString("QUESTION");
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
        MoodleQuestion q = tableView.getSelectionModel().getSelectedItem();
        TreeItem<String> current = treeView.getSelectionModel().getSelectedItem();
        if (q == null || current == null) return;
        String oldP = getFullPath(current), newP = getFullPath(targetItem);
        if (!oldP.equals(newP)) {
            categoryData.get(oldP).remove(q);
            categoryData.computeIfAbsent(newP, k -> FXCollections.observableArrayList()).add(q);
            updateTable(oldP);
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
        String processedHtml = q.getQuestionText();
        for (MoodleFile f : q.getFiles()) {
            String mime = f.name.toLowerCase().endsWith(".png") ? "image/png" : "image/jpeg";
            processedHtml = processedHtml.replace("@@PLUGINFILE@@/" + f.name, "data:" + mime + ";base64," + f.content);
        }
        webView.getEngine().loadContent(processedHtml);
        editorArea.setText(q.getQuestionText());
    }

    private void setupTableColumns() {
        TableColumn<MoodleQuestion, String> cN = new TableColumn<>("Nombre de la Pregunta");
        cN.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().getName()));
        tableView.getColumns().add(cN);
        tableView.getSelectionModel().selectedItemProperty().addListener((obs, ov, nv) -> {
            if (nv != null) updateDetail(nv);
        });
    }

    private void setupTreeContextMenu() {
        ContextMenu m = new ContextMenu();
        MenuItem ex = new MenuItem("Expandir Todo"); ex.setOnAction(e -> expandRecursive(treeView.getSelectionModel().getSelectedItem(), true));
        MenuItem co = new MenuItem("Colapsar Todo"); co.setOnAction(e -> expandRecursive(treeView.getSelectionModel().getSelectedItem(), false));
        m.getItems().addAll(ex, co);
        treeView.setContextMenu(m);
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

    public static void main(String[] args) { launch(args); }
}
