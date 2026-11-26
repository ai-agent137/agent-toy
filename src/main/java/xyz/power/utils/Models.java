package xyz.power.utils;

import xyz.power.llm.JevModel;
import xyz.power.llm.OpenAiChatModel;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class Models {
    private  static String baseUrl;
    private static String apiKey;
    private static String model;
    // Jev 决策模型（System One）配置：意图路由等结构化判断专用，见 JevModel
    private static String jevUrl;
    private static String jevApiKey;
    private static String jevModel;
    static {
        load();
    }
    private static void load()  {
        Properties properties = new Properties();
        Path conf = Path.of("config.properties");
        try {
            try (InputStream in = Files.newInputStream(conf)) {
                properties.load(in);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        baseUrl = properties.getProperty("base-url");
        apiKey = properties.getProperty("api-key");
        model = properties.getProperty("model");
        jevUrl =properties.getProperty("jev-url");
        jevApiKey = properties.getProperty("jev-model");
        jevModel = properties.getProperty("jev-model");
    }
    public static OpenAiChatModel getModel() {
        return new OpenAiChatModel(baseUrl, apiKey, model);
    }
    public static JevModel getJevModel() {
        return new JevModel(jevUrl, jevApiKey, jevModel);
    }
}
