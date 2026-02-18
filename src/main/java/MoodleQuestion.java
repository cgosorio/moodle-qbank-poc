import java.util.ArrayList;
import java.util.List;

public class MoodleQuestion {
    private String type;
    private String name;
    private String questionText;
    private String categoryPath; // Solo para tipo "category"
    
    public MoodleQuestion(String type, String name, String text) {
        this.type = type;
        this.name = name;
        this.questionText = text;
    }

    // Getters y Setters
    public String getType() { return type; }
    public String getName() { return name; }
    public String getQuestionText() { return questionText; }
    public void setCategoryPath(String path) { this.categoryPath = path; }
    public String getCategoryPath() { return categoryPath; }
    @Override public String toString() { return name; }
}
