package xyz.power.llm;

import xyz.power.llm.schemas.ChatResponse;
import xyz.power.llm.schemas.Message;
import xyz.power.llm.schemas.ToolCall;
import xyz.power.utils.Json;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class OpenAiChatModel implements ChatModel{
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    public OpenAiChatModel(String baseUrl, String apiKey, String model) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
    }
    @Override
    public String chat(List<Message> messages) {
       return chat(messages, List.of()).content();
    }
    @Override
    public ChatResponse chat(List<Message> messages, List<Map<String, Object>> tools)  {
        LinkedHashMap<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages.stream()
                .map(OpenAiChatModel::toWire)
                .toList()
        );
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools);
        }
        body.put("temperature", 0.7);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)))
                .build();
        HttpResponse<String> res = null;
        try {
            res = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
        if( res.statusCode() != 200 ) {
            throw new IllegalStateException("模型调用失败: " + res.statusCode() + ":" + res.body());
        }
        // 解析响应
        Object resObj = Json.parse(res.body());
        Object choices = ((Map<?, ?>) resObj).get("choices");
        Object messagesRes = ((List<?>) choices).get(0);
        Object message = ((Map<?, ?>) messagesRes).get("message");
        Object contentObj = ((Map<?, ?>) message).get("content");
        String content = contentObj == null ? "" : contentObj.toString();

        // 解析tools
        List<ToolCall> calls = new ArrayList<>();
        Object rawCalls = ((Map<?,?> ) message).get("tool_calls");
        if (rawCalls instanceof List<?> list) {
            for(Object item :  list) {
                Map<?, ?> call = (Map<?, ?>) item;
                Map<?, ?> fn = (Map<?, ?>) call.get("function");
                calls.add(
                        new ToolCall(
                            String.valueOf(fn.get("id")),
                            Json.str(fn, "name"),
                            Json.str(fn, "arguments")
                        )
                );
            }

        }

        return new ChatResponse(content, List.copyOf(calls));
    }
    /** 按 OpenAI 消息协议序列化：tool_calls / role=tool + tool_call_id 都要走线。 */
    private static Map<String, Object> toWire(Message m) {
        Map<String, Object> wire = Json.obj();
        wire.put("role", m.role());
        if (m.toolCalls() != null && !m.toolCalls().isEmpty()) {
            wire.put("tool_calls", m.toolCalls().stream().map(tc -> {
                Map<String, Object> fn = Json.obj();
                fn.put("name", tc.name());
                fn.put("arguments", tc.argumentsJson());
                Map<String, Object> call = Json.obj();
                call.put("id", tc.id());
                call.put("type", "function");
                call.put("function", fn);
                return call;
            }).toList());
            if (m.content() != null && !m.content().isBlank()) {
                wire.put("content", m.content());
            }
        } else {
            wire.put("content", m.content() == null ? "" : m.content());
            if ("tool".equals(m.role())) {
                wire.put("tool_call_id", m.toolCallId());
            }
        }
        return wire;
    }

}
