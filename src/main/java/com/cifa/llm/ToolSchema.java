package com.cifa.llm;

import org.json.JSONObject;

public record ToolSchema(String name, String description, JSONObject parameters) {
    public ToolSchema {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("tool name is required");
        }
        description = description == null ? "" : description;
        parameters = parameters == null ? new JSONObject() : new JSONObject(parameters.toString());
    }

    public static ToolSchema object(String name, String description) {
        return new ToolSchema(name, description, new JSONObject().put("type", "object"));
    }

    public JSONObject toJson() {
        return new JSONObject()
                .put("name", name)
                .put("description", description)
                .put("input_schema", new JSONObject(parameters.toString()));
    }
}
