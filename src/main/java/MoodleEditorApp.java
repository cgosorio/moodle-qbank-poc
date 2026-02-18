import javafx.application.Application;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.web.WebView;
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
    
    // Almacén de preguntas indexadas por su ruta de categoría
    private Map<String, ObservableList<MoodleQuestion>> categoryData = new HashMap<>();

    @Override
    public void start(Stage primaryStage) {
        // 1. PANEL IZQUIERDO: Árbol de categorías
        TreeItem<String> rootItem = new TreeItem<>("Banco de Preguntas");
        rootItem.setExpanded(true);
        treeView = new TreeView<>(rootItem);
        treeView.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) updateTable(getFullPath(newVal));
        });

        // 2. PANEL DERECHO SUPERIOR: Tabla de preguntas
        tableView = new TableView<>();
        TableColumn<MoodleQuestion, String> colName = new TableColumn<>("Nombre");
        colName.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().getName()));
        TableColumn<MoodleQuestion, String> colType = new TableColumn<>("Tipo");
        colType.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().getType()));
        
        tableView.getColumns().addAll(colName, colType);
        tableView.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) updateDetail(newVal);
        });

        // 3. PANEL DERECHO INFERIOR: Detalle y Renderizado
        webView = new WebView();
        editorArea = new TextArea();
        editorArea.setPromptText("Código HTML de la pregunta...");
        VBox detailBox = new VBox(new Label("Previsualización:"), webView, new Label("Editor:"), editorArea);
        VBox.setVgrow(webView, Priority.ALWAYS);

        // --- DISTRIBUCIÓN (Layout) ---
        SplitPane rightSplit = new SplitPane(tableView, detailBox);
        rightSplit.setOrientation(javafx.geometry.Orientation.VERTICAL);
        
        SplitPane mainSplit = new SplitPane(treeView, rightSplit);
        mainSplit.setDividerPositions(0.3);

        // Cargar datos iniciales (Simulado o desde archivo)
        loadXML("TemplatePreguntas.xml");

        Scene scene = new Scene(mainSplit, 1100, 700);
        primaryStage.setTitle("Moodle XML Editor - JavaFX");
        primaryStage.show();
    }

    private void updateTable(String path) {
        tableView.setItems(categoryData.getOrDefault(path, FXCollections.observableArrayList()));
    }

    private void updateDetail(MoodleQuestion q) {
        webView.getEngine().loadContent(q.getQuestionText());
        editorArea.setText(q.getQuestionText());
    }

    private String getFullPath(TreeItem<String> item) {
        if (item == null || item.getParent() == null) return "";
        String parentPath = getFullPath(item.getParent());
        return (parentPath.isEmpty() ? "" : parentPath + "/") + item.getValue();
    }

    // --- LÓGICA DE CARGA XML ---
    private void loadXML(String fileName) {
        try {
            // Nota: En una app real, usarías un FileChooser
            File inputFile = new File(fileName);
            if (!inputFile.exists()) return;

            DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
            DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
            Document doc = dBuilder.parse(inputFile);
            doc.getDocumentElement().normalize();

            NodeList nList = doc.getElementsByTagName("question");
            String currentCategory = "Raíz";

            for (int i = 0; i < nList.getLength(); i++) {
                Element elem = (Element) nList.item(i);
                String type = elem.getAttribute("type");

                if (type.equals("category")) {
                    currentCategory = elem.getElementsByTagName("text").item(0).getTextContent();
                    addCategoryToTree(currentCategory);
                } else {
                    String name = elem.getElementsByTagName("name").item(0).getTextContent();
                    String text = elem.getElementsByTagName("questiontext").item(0).getTextContent();
                    MoodleQuestion q = new MoodleQuestion(type, name, text);
                    categoryData.computeIfAbsent(currentCategory, k -> FXCollections.observableArrayList()).add(q);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void addCategoryToTree(String moodlePath) {
        // Limpiar el prefijo de Moodle $module$/top/
        String cleanPath = moodlePath.replace("$module$/top/", "");
        String[] parts = cleanPath.split("/");
        TreeItem<String> current = treeView.getRoot();

        for (String part : parts) {
            TreeItem<String> next = null;
            for (TreeItem<String> child : current.getChildren()) {
                if (child.getValue().equals(part)) {
                    next = child;
                    break;
                }
            }
            if (next == null) {
                next = new TreeItem<>(part);
                current.getChildren().add(next);
            }
            current = next;
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
