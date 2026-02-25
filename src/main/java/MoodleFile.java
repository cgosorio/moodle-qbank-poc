/*
 * Copyright (c) 2026 César Ignacio García Osorio
 *
 * Este archivo forma parte de Moodle Question Bank Manager.
 *
 * Se distribuye bajo la licencia MIT. Consulta el archivo LICENSE
 * en la raíz del proyecto para más detalles.
 */

public class MoodleFile {
    public String name, path, encoding, content;
    public MoodleFile(String name, String path, String encoding, String content) {
        this.name = name; 
        this.path = path; 
        this.encoding = encoding; 
        this.content = content;
    }
}
