package xyz.power.llm;

import xyz.power.llm.schemas.ChatResponse;
import xyz.power.llm.schemas.Message;

import java.util.List;
import java.util.Map;

public interface  ChatModel {

    String chat(List<Message> messages);

    ChatResponse chat(List<Message> messages, List<Map<String, Object>> tools);
}
