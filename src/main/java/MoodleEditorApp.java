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
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MoodleEditorApp extends Application {

    private TreeView<String> treeView;
    private TableView<MoodleQuestion> tableView;
    private WebView webView;
    private TextArea editorArea;
    // El mapa ahora guarda las preguntas asociadas a la ruta de la categoría
    private Map<String, ObservableList<MoodleQuestion>> categoryData = new HashMap<>();
    private TreeItem<String> draggedItem;

    @Override
    public void start(Stage primaryStage) {
        // --- Componentes de la Interfaz ---
        TreeItem<String> rootItem = new TreeItem<>("Banco de Preguntas");
        rootItem.setExpanded(true);
        treeView = new TreeView<>(rootItem);
        setupTreeCellFactory();
        setupTreeContextMenu();

        treeView.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) updateTable(getFullPath(newVal));
        });

        tableView = new TableView<>();
        setupTableColumns();
        setupTableDragSource();

        webView = new WebView();
        editorArea = new TextArea();
        VBox detailBox = new VBox(10, new Label("Vista Previa:"), webView, new Label("Editor HTML:"), editorArea);
        detailBox.setPadding(new Insets(10));
        VBox.setVgrow(webView, Priority.ALWAYS);

        // --- Barra de Herramientas ---
        Button btnLoad = new Button("Cargar archivo Moodle XML");
        btnLoad.setOnAction(e -> openFileChooser(primaryStage));
        ToolBar toolBar = new ToolBar(btnLoad);

        // --- Layout ---
        SplitPane rightSplit = new SplitPane(tableView, detailBox);
        rightSplit.setOrientation(javafx.geometry.Orientation.VERTICAL);
        SplitPane mainSplit = new SplitPane(treeView, rightSplit);
        mainSplit.setDividerPositions(0.3);

        VBox rootLayout = new VBox(toolBar, mainSplit);
        VBox.setVgrow(mainSplit, Priority.ALWAYS);

        primaryStage.setScene(new Scene(rootLayout, 1200, 800));
        primaryStage.setTitle("Moodle Editor Offline - Gestión de Categorías");
        primaryStage.show();
    }

    private void openFileChooser(Stage stage) {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Seleccionar archivo Moodle XML");
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Archivos XML", "*.xml"));
        File file = fileChooser.showOpenDialog(stage);
        if (file != null) {
            // Limpiar datos previos antes de cargar nuevo archivo
            categoryData.clear();
            treeView.getRoot().getChildren().clear();
            loadXML(file);
        }
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
                if (event.getDragboard().hasString()) {
                    event.acceptTransferModes(TransferMode.MOVE);
                }
                event.consume();
            });

            cell.setOnDragDropped(event -> {
                Dragboard db = event.getDragboard();
                if (!cell.isEmpty()) {
                    TreeItem<String> target = cell.getTreeItem();
                    if (draggedItem != null) {
                        // Moviendo categoría
                        moveCategory(draggedItem, target);
                    } else {
                        // Moviendo pregunta
                        moveQuestionToCategory(target);
                    }
                    event.setDropCompleted(true);
                    draggedItem = null;
                    treeView.refresh();
                }
                event.consume();
            });
            return cell;
        });
    }

    private void moveCategory(TreeItem<String> source, TreeItem<String> target) {
        if (source == null || target == null || source == target) return;
        
        // Evitar bucles infinitos
        TreeItem<String> temp = target;
        while (temp != null) {
            if (temp == source) return;
            temp = temp.getParent();
        }

        String oldRootPath = getFullPath(source);
        source.getParent().getChildren().remove(source);
        target.getChildren().add(source);
        String newRootPath = getFullPath(source);

        // Actualizar el mapa de datos para esta categoría y todas sus subcategorías
        updateMapPaths(oldRootPath, newRootPath);
        target.setExpanded(true);
    }

    private void updateMapPaths(String oldPath, String newPath) {
        // Necesitamos una lista temporal para evitar ConcurrentModificationException
        List<String> keysToMove = new ArrayList<>();
        for (String key : categoryData.keySet()) {
            if (key.startsWith(oldPath)) {
                keysToMove.add(key);
            }
        }

        for (String oldKey : keysToMove) {
            String newKey = oldKey.replaceFirst(java.util.regex.Pattern.quote(oldPath), newPath);
            ObservableList<MoodleQuestion> data = categoryData.remove(oldKey);
            categoryData.put(newKey, data);
        }
    }

    private void moveQuestionToCategory(TreeItem<String> targetItem) {
        MoodleQuestion q = tableView.getSelectionModel().getSelectedItem();
        TreeItem<String> currentCategoryItem = treeView.getSelectionModel().getSelectedItem();
        if (q == null || currentCategoryItem == null) return;

        String oldPath = getFullPath(currentCategoryItem);
        String newPath = getFullPath(targetItem);

        if (!oldPath.equals(newPath)) {
            categoryData.get(oldPath).remove(q);
            categoryData.computeIfAbsent(newPath, k -> FXCollections.observableArrayList()).add(q);
            updateTable(oldPath);
        }
    }

    // --- Resto de lógica de soporte ---

    private void setupTableColumns() {
        TableColumn<MoodleQuestion, String> colName = new TableColumn<>("Nombre");
        colName.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().getName()));
        TableColumn<MoodleQuestion, String> colType = new TableColumn<>("Tipo");
        colType.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().getType()));
        tableView.getColumns().addAll(colName, colType);
        tableView.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                webView.getEngine().loadContent(newVal.getQuestionText());
                editorArea.setText(newVal.getQuestionText());
            }
        });
    }

    private void setupTableDragSource() {
        tableView.setOnDragDetected(event -> {
            if (tableView.getSelectionModel().getSelectedItem() != null) {
                draggedItem = null;
                Dragboard db = tableView.startDragAndDrop(TransferMode.MOVE);
                ClipboardContent content = new ClipboardContent();
                content.putString("QUESTION");
                db.setContent(content);
                event.consume();
            }
        });
    }

    private void setupTreeContextMenu() {
        ContextMenu menu = new ContextMenu();
        MenuItem expand = new MenuItem("Expandir Todo");
        expand.setOnAction(e -> expandRecursive(treeView.getSelectionModel().getSelectedItem(), true));
        MenuItem collapse = new MenuItem("Colapsar Todo");
        collapse.setOnAction(e -> expandRecursive(treeView.getSelectionModel().getSelectedItem(), false));
        menu.getItems().addAll(expand, collapse);
        treeView.setContextMenu(menu);
    }

    private void expandRecursive(TreeItem<String> item, boolean expand) {
        if (item == null) return;
        item.setExpanded(expand);
        for (TreeItem<String> child : item.getChildren()) expandRecursive(child, expand);
    }

    private void loadXML(File file) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(file);
            doc.getDocumentElement().normalize();
            NodeList nodes = doc.getElementsByTagName("question");
            String currentPath = "Banco de Preguntas";

            for (int i = 0; i < nodes.getLength(); i++) {
                Node node = nodes.item(i);
                if (node instanceof Element) {
                    Element el = (Element) node;
                    if ("category".equals(el.getAttribute("type"))) {
                        currentPath = buildTreeFromMoodlePath(getNestedTagValue(el, "category"));
                    } else {
                        MoodleQuestion q = new MoodleQuestion(el.getAttribute("type"), 
                                           getNestedTagValue(el, "name"), getNestedTagValue(el, "questiontext"));
                        categoryData.computeIfAbsent(currentPath, k -> FXCollections.observableArrayList()).add(q);
                    }
                }
            }
            treeView.refresh();
        } catch (Exception e) { e.printStackTrace(); }
    }

    private String getNestedTagValue(Element parent, String tagName) {
        try {
            NodeList list = parent.getElementsByTagName(tagName);
            if (list.getLength() > 0 && list.item(0) instanceof Element) {
                NodeList textList = ((Element) list.item(0)).getElementsByTagName("text");
                if (textList.getLength() > 0) return textList.item(0).getTextContent();
            }
        } catch (Exception e) { return ""; }
        return "";
    }

    private String buildTreeFromMoodlePath(String moodlePath) {
        String clean = moodlePath.replace("$module$/top/", "");
        String[] parts = clean.split("/");
        TreeItem<String> current = treeView.getRoot();
        StringBuilder fullPath = new StringBuilder("Banco de Preguntas");
        for (String p : parts) {
            if (p.isEmpty()) continue;
            fullPath.append("/").append(p);
            TreeItem<String> found = null;
            for (TreeItem<String> child : current.getChildren()) {
                if (child.getValue().equals(p)) { found = child; break; }
            }
            if (found == null) {
                found = new TreeItem<>(p);
                current.getChildren().add(found);
            }
            current = found;
        }
        return fullPath.toString();
    }

    private String getFullPath(TreeItem<String> item) {
        if (item == null) return "";
        if (item.getParent() == null) return item.getValue();
        return getFullPath(item.getParent()) + "/" + item.getValue();
    }

    private void updateTable(String path) {
        tableView.setItems(categoryData.getOrDefault(path, FXCollections.observableArrayList()));
    }

    public static void main(String[] args) { launch(args); }
}
