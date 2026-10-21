package com.example.billing;

public class StatementRenderer {

    private final String template;

    public StatementRenderer(String template) {
        this.template = template;
    }

    public String header(String title) {
        return String.format(template, title);
    }

    public String separator() {
        return String.format(template, "-");
    }

    public String footer(int pages) {
        return String.format(template, pages);
    }

    public String row(Object[] details) {
        // passing the array raw matches both the varargs and the
        // single-Object overload, so this call site is ambiguous
        return String.format(template, details);
    }
}
