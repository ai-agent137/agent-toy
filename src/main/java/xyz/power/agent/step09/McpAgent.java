package xyz.power.agent.step09;

import xyz.power.agent.Agent;
import xyz.power.agent.step03.ReActAgent.MockWeather;
import xyz.power.llm.ChatModel;
import xyz.power.llm.schemas.Message;
import xyz.power.utils.Json;
import xyz.power.utils.Models;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;

public interface McpAgent extends Agent {
    public record ToolDescriptor(String name, String description, String parameters) {}
    interface McpServer {
        List<ToolDescriptor> tools();
        String call(String toolName, String argsJson);
    }
    final class WeatherTimeServer implements McpServer {
        @Override
        public List<ToolDescriptor> tools() {
            return List.of(
                    new ToolDescriptor("get_weather", "查询城市实时天气", "city"),
                    new ToolDescriptor("get_time", "获取当前时间", "")
            );
        }

        @Override
        public String call(String toolName, String argsJson) {
            Object args = Json.parse(argsJson == null ? "{}" : argsJson);
            return switch (toolName) {
                case "get_weather" -> MockWeather.query(Json.str(args, "city"));
                case "get_time" -> LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                default -> "未知工具";
            };
        }
    }
    /** MCP Client：负责 tools/list 与 tools/call 两个标准方法。 */
    record McpClient (McpServer mcpServer) {
        List<Map<String, String>> listTools() {
            return mcpServer.tools().stream()
                    .map(t -> {
                        Map<String, String> map = new LinkedHashMap<>();
                        map.put("name", t.name());
                        map.put("description", t.description());
                        map.put("parameters", t.parameters());
                        return map;
                    }).toList();
        }
        String callTool(String name, String argsJson) {
            boolean exists = mcpServer.tools().stream().anyMatch(t -> t.name.equals(name));
            if (!exists) return "MCP 错误: 工具不存在 " + name;
            return mcpServer.call(name, argsJson);
        }



    }

    class Impl implements McpAgent {
        private ChatModel model;
        private final List<Map<String, Object>> lastTrace = new ArrayList<>();
        private WeatherTimeServer server = new WeatherTimeServer();
        private McpClient mcpClient = new McpClient(server);
        public Impl(ChatModel model) {
            this.model = model;
        }
        @Override
        public String name() {
            return "Step09 · MCP 协议（工具标准化接口";
        }

        @Override
        public String chat(String input) {
            lastTrace.clear();
            // 1. 工具发现：从 MCP Server 拉取工具清单，拼进提示词
            List<Map<String, String>> tools = mcpClient.listTools();
            lastTrace.add(Map.of(
                    "type", "mcp",
                    "label", "tools/list 工具发现",
                    "detail", "从 MCP Server 动态获取 " + tools.size() + " 个工具: " +
                            tools.stream().map(t -> t.get("name")).reduce((a, b) -> a + ", " + b).orElse("")));
            // 系统提示词
            StringBuilder toolDocs = new StringBuilder();
            for (Map<String, String> t : tools) {
                toolDocs.append("- ").append(t.get("name")).append("(").append(t.get("parameters"))
                        .append("): ").append(t.get("description")).append("\n");
            }
            String decidePrompt = """
                你是可以调用 MCP 工具的智能体。可用工具：
                %s
                需要工具时只输出 JSON：{"method": "tools/call", "params": {"name": "工具名", "arguments": {"参数": "值"}}}
                否则直接自然语言回答。
                """.formatted(toolDocs);
            // 2. 模型决策
            String raw = model.chat(List.of(Message.system(decidePrompt), Message.user(input))).trim();
            Object decision = tryParse(raw);
            if (decision instanceof Map<?, ?> && "tools/call".equals(Json.str(decision, "method"))) {
                Map<?, ?> params = (Map<?, ?>) ((Map<?, ?>) decision).get("params");
                String name = String.valueOf(params.get("name"));
                String args = Json.write(params.get("arguments"));
                lastTrace.add(Map.of("type", "tool", "label", "tools/call 工具调用", "detail", name + " " + args));

                // 3. 经由 MCP Client 标准化调用
                String result = mcpClient.callTool(name, args);
                lastTrace.add(Map.of("type", "observation", "label", "Server 返回", "detail", result));

                String answer = model.chat(List.of(
                        Message.system("基于工具返回的事实回答，简洁准确。"),
                        Message.user(input + "\n工具结果: " + result)));
                lastTrace.add(Map.of("type", "final", "label", "生成回答", "detail", "工具与智能体已解耦：Server 换实现，智能体零改动"));
                return answer;
            }

            lastTrace.add(Map.of("type", "final", "label", "直接回答", "detail", "本次无需工具"));
            return raw;
        }
        private Object tryParse(String raw) {
            try {
                return Json.parse(raw.replaceFirst("^```\\w*\\s*", "").replaceFirst("```\\s*$", "").trim());
            } catch (Exception e) {
                return Json.obj();
            }
        }
    }
    public static void main(String[] args) {
        ChatModel model = Models.getModel();
        String input = "列出可用的 MCP 工具并调用其中一个";
        System.out.println("[输入] " + input);
        System.out.println("[输出] " + new Impl(model).chat(input));
    }
}
