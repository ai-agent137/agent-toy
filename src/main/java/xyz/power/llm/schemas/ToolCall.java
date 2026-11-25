package xyz.power.llm.schemas;

public record ToolCall(String id, String name, String argumentsJson) {
}
