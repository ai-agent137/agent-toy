package xyz.power.utils;

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
    }
    public static OpenAiChatModel getModel() {
        return new OpenAiChatModel(baseUrl, apiKey, model);
    }
}
