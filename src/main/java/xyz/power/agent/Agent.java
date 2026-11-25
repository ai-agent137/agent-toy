package xyz.power.agent;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public interface Agent {
    String name();
    String chat(String input);
    default List<Map<String, Object>> trace(){
        return List.of();
    }
    default void reset() {}
}
