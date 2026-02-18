import javafx.application.Application;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
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
    private Map<String, ObservableList<MoodleQuestion>> categoryData = new HashMap<>();

    @Override
    public void start(Stage primaryStage) {
        // --- PANEL IZQUIERDO ---
        TreeItem<String> rootItem = new TreeItem<>("Banco de Preguntas");
        rootItem.setExpanded(true);
        treeView = new TreeView<>(rootItem);
        treeView.setMinWidth(250);
        
        treeView.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) updateTable(getFullPath(newVal));
        });

        // --- PANEL DERECHO SUPERIOR ---
        tableView = new TableView<>();
        TableColumn<MoodleQuestion, String> colName = new TableColumn<>("Nombre");
        colName.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().getName()));
        colName.setPrefWidth(400);

        TableColumn<MoodleQuestion, String> colType = new TableColumn<>("Tipo");
        colType.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().getType()));

        tableView.getColumns().addAll(colName, colType);
        VBox.setVgrow(tableView, Priority.ALWAYS);

        tableView.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) updateDetail(newVal);
        });

        // --- PANEL DERECHO INFERIOR ---
        webView = new WebView();
        editorArea = new TextArea();
        VBox detailBox = new VBox(10, new Label("Vista Previa:"), webView, new Label("Editor:"), editorArea);
        detailBox.setPadding(new Insets(10));
        VBox.setVgrow(webView, Priority.ALWAYS);

        // --- LAYOUT ---
        SplitPane rightSplit = new SplitPane(tableView, detailBox);
        rightSplit.setOrientation(javafx.geometry.Orientation.VERTICAL);
        rightSplit.setDividerPositions(0.4);

        SplitPane mainSplit = new SplitPane(treeView, rightSplit);
        mainSplit.setDividerPositions(0.25);

        // --- CARGA DE DATOS ---
        File xmlFile = new File("TemplatePreguntas.xml");
        if (xmlFile.exists()) {
            loadXML(xmlFile);
        } else {
            createSampleData();
        }

        Scene scene = new Scene(mainSplit, 1200, 800);
        primaryStage.setTitle("Moodle Bank Editor - Offline");
        primaryStage.setScene(scene);
        primaryStage.show();
    }

    private void createSampleData() {
        TreeItem<String> cat = new TreeItem<>("Archivo no encontrado");
        treeView.getRoot().getChildren().add(cat);
        ObservableList<MoodleQuestion> samples = FXCollections.observableArrayList();
        samples.add(new MoodleQuestion("info", "Error", "Coloque TemplatePreguntas.xml en la raíz del proyecto."));
        categoryData.put("Banco de Preguntas/Archivo no encontrado", samples);
    }

    private void updateTable(String path) {
        tableView.setItems(categoryData.getOrDefault(path, FXCollections.observableArrayList()));
    }

    private void updateDetail(MoodleQuestion q) {
        webView.getEngine().loadContent(q.getQuestionText());
        editorArea.setText(q.getQuestionText());
    }

    private String getFullPath(TreeItem<String> item) {
        if (item == null) return "";
        if (item.getParent() == null) return item.getValue();
        return getFullPath(item.getParent()) + "/" + item.getValue();
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
                if (node.getNodeType() == Node.ELEMENT_NODE) {
                    Element el = (Element) node;
                    String type = el.getAttribute("type");

                    if ("category".equals(type)) {
                        String catStr = getNestedTagValue(el, "category");
                        if (!catStr.isEmpty()) {
                            currentPath = buildTreeFromMoodlePath(catStr);
                        }
                    } else {
                        String name = getNestedTagValue(el, "name");
                        String text = getNestedTagValue(el, "questiontext");
                        MoodleQuestion q = new MoodleQuestion(type, name, text);
                        categoryData.computeIfAbsent(currentPath, k -> FXCollections.observableArrayList()).add(q);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private String getNestedTagValue(Element parent, String tagName) {
        try {
            NodeList list = parent.getElementsByTagName(tagName);
            if (list.getLength() > 0) {
                Node node = list.item(0);
                if (node instanceof Element) {
                    Element tagElement = (Element) node;
                    NodeList textList = tagElement.getElementsByTagName("text");
                    if (textList.getLength() > 0) {
                        return textList.item(0).getTextContent();
                    }
                }
            }
        } catch (Exception e) {
            return "";
        }
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
                if (child.getValue().equals(p)) {
                    found = child;
                    break;
                }
            }
            if (found == null) {
                found = new TreeItem<>(p);
                current.getChildren().add(found);
            }
            current = found;
        }
        return fullPath.toString();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
