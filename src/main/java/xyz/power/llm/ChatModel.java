package xyz.power.llm;

import java.util.List;

public interface  ChatModel {
    String chat(List<Message> messages);
}
