package xyz.power.llm.schemas;

import java.util.List;

public record ChatResponse(String content, List<ToolCall> toolCalls) {
    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}
