import org.w3c.dom.Element;
import java.util.ArrayList;
import java.util.List;

public class MoodleQuestion {
    private String type, name, questionText;
    private List<MoodleFile> files = new ArrayList<>();
    private Element originalElement; // Guardamos el XML original íntegro

    public MoodleQuestion(String type, String name, String text, Element el) {
        this.type = type;
        this.name = name;
        this.questionText = text;
        this.originalElement = el;
    }

    public String getType() { return type; }
    public String getName() { return name; }
    public String getQuestionText() { return questionText; }
    public List<MoodleFile> getFiles() { return files; }
    public Element getOriginalElement() { return originalElement; }
}
