package xyz.power.llm;

import xyz.power.utils.Json;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
    public String chat(List<Message> messages)  {
        LinkedHashMap<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages.stream()
                .map(m -> Map.of("role", m.role(), "content", m.content()))
                .toList()
        );
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
        Object first = ((List<?>) choices).get(0);
        Object message = ((Map<?, ?>) first).get("message");
        return String.valueOf(((Map<?, ?>) message).get("content"));
    }

}
