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
import java.util.HashMap;
import java.util.Map;

public class MoodleEditorApp extends Application {

    private TreeView<String> treeView;
    private TableView<MoodleQuestion> tableView;
    private WebView webView;
    private TextArea editorArea;
    private Map<String, ObservableList<MoodleQuestion>> categoryData = new HashMap<>();
    private static final DataFormat SERIALIZED_MIME_TYPE = new DataFormat("application/x-java-serialized-object");

    @Override
    public void start(Stage primaryStage) {
        // --- SELECCIÓN DE ARCHIVO ---
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Seleccionar archivo Moodle XML");
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("XML Files", "*.xml"));
        File selectedFile = fileChooser.showOpenDialog(primaryStage);

        // --- INTERFAZ ---
        TreeItem<String> rootItem = new TreeItem<>("Banco de Preguntas");
        rootItem.setExpanded(true);
        treeView = new TreeView<>(rootItem);
        setupTreeContextMenu();
        setupDragAndDrop();

        treeView.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) updateTable(getFullPath(newVal));
        });

        tableView = new TableView<>();
        setupTableColumns();
        setupTableDrag();

        webView = new WebView();
        editorArea = new TextArea();
        VBox detailBox = new VBox(10, new Label("Vista Previa:"), webView, new Label("Editor:"), editorArea);
        detailBox.setPadding(new Insets(10));
        VBox.setVgrow(webView, Priority.ALWAYS);

        SplitPane rightSplit = new SplitPane(tableView, detailBox);
        rightSplit.setOrientation(javafx.geometry.Orientation.VERTICAL);
        SplitPane mainSplit = new SplitPane(treeView, rightSplit);
        mainSplit.setDividerPositions(0.3);

        if (selectedFile != null) loadXML(selectedFile);

        primaryStage.setScene(new Scene(mainSplit, 1200, 800));
        primaryStage.setTitle("Moodle Editor - " + (selectedFile != null ? selectedFile.getName() : "Sin archivo"));
        primaryStage.show();
    }

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

    // --- LÓGICA DE EXPANSIÓN (Menú Contextual) ---
    private void setupTreeContextMenu() {
        ContextMenu menu = new ContextMenu();
        MenuItem expandAll = new MenuItem("Expandir todos los niveles");
        expandAll.setOnAction(e -> expandNode(treeView.getSelectionModel().getSelectedItem(), true));
        MenuItem collapseAll = new MenuItem("Colapsar todos los niveles");
        collapseAll.setOnAction(e -> expandNode(treeView.getSelectionModel().getSelectedItem(), false));
        menu.getItems().addAll(expandAll, collapseAll);
        treeView.setContextMenu(menu);
    }

    private void expandNode(TreeItem<String> item, boolean expand) {
        if (item != null) {
            item.setExpanded(expand);
            for (TreeItem<String> child : item.getChildren()) expandNode(child, expand);
        }
    }

    // --- DRAG AND DROP ---
    private void setupTableDrag() {
        tableView.setOnDragDetected(event -> {
            MoodleQuestion selected = tableView.getSelectionModel().getSelectedItem();
            if (selected != null) {
                Dragboard db = tableView.startDragAndDrop(TransferMode.MOVE);
                ClipboardContent content = new ClipboardContent();
                content.putString(selected.getName()); // Usamos el nombre como ID temporal
                db.setContent(content);
                event.consume();
            }
        });
    }

    private void setupDragAndDrop() {
        treeView.setCellFactory(tv -> {
            TreeCell<String> cell = new TreeCell<>() {
                @Override protected void updateItem(String item, boolean empty) {
                    super.updateItem(item, empty);
                    setText(empty ? null : item);
                }
            };

            cell.setOnDragOver(event -> {
                if (event.getDragboard().hasString()) event.acceptTransferModes(TransferMode.MOVE);
                event.consume();
            });

            cell.setOnDragDropped(event -> {
                Dragboard db = event.getDragboard();
                if (db.hasString() && !cell.isEmpty()) {
                    TreeItem<String> targetCategory = cell.getTreeItem();
                    moveQuestionToCategory(targetCategory);
                    event.setDropCompleted(true);
                }
                event.consume();
            });
            return cell;
        });
    }

    private void moveQuestionToCategory(TreeItem<String> targetItem) {
        MoodleQuestion q = tableView.getSelectionModel().getSelectedItem();
        if (q == null) return;

        // Remover de la categoría actual
        String oldPath = getFullPath(treeView.getSelectionModel().getSelectedItem());
        categoryData.get(oldPath).remove(q);

        // Añadir a la nueva
        String newPath = getFullPath(targetItem);
        categoryData.computeIfAbsent(newPath, k -> FXCollections.observableArrayList()).add(q);
        
        updateTable(oldPath);
        System.out.println("Movida: " + q.getName() + " -> " + newPath);
    }

    // --- MÉTODOS DE APOYO (XML y Rutas) ---
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
