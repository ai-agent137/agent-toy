package xyz.power.agent.step04;

import xyz.power.agent.Agent;
import xyz.power.agent.step03.ReActAgent.MockWeather;
import xyz.power.agent.step03.ReActAgent;
import xyz.power.llm.ChatModel;
import xyz.power.llm.schemas.ChatResponse;
import xyz.power.llm.schemas.Message;
import xyz.power.llm.schemas.ToolCall;
import xyz.power.utils.Json;
import xyz.power.utils.Models;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public interface ToolCallAgent extends Agent {
    class Impl implements ToolCallAgent {
        private final ChatModel model;
        private final List<Map<String, Object>> lastTrace = new ArrayList<>();

        /** 提示词只负责人设与回答风格 —— 工具清单交给协议的 tools 字段，不再写进提示词。 */
        private static final String SYSTEM_PROMPT = """
                你是一个可以调用工具的智能体。需要实时数据或精确计算时调用工具，
                拿到工具结果后再用自然语言回答用户。
                """;
        public Impl(ChatModel model) {
            this.model = model;
        }
        private static Map<String, Object> tool(String name, String desc, Map<String, Object> params) {
            Map<String, Object> fn = Json.obj();
            fn.put("name", name);
            fn.put("desc", desc);
            fn.put("parameters", params);
            Map<String, Object> schema = Json.obj();
            schema.put("type", "function");
            schema.put("function", fn);
            return schema;
        }
        /** 工具清单：随每次请求发给模型 —— 模型能调用什么，完全由这份清单决定。 */

        private static final List<Map<String, Object>> TOOLS = List.of(
                tool("get_weather", "查询指定城市的实时天气",
                        Map.of("type", "object",
                                "properties", Map.of(
                                        "city", Map.of("type", "string", "description", "城市名，如：北京")),
                                "required", List.of("city"))),
                tool("calculator", "计算四则运算表达式",
                        Map.of("type", "object",
                                "properties", Map.of(
                                        "expression", Map.of("type", "string", "description", "四则运算表达式，如 12*8")),
                                "required", List.of("expression")))
        );


        @Override
        public String name() {
            return "Step04 · 工具调用（Function Calling）";
        }

        private static final int MAX_ROUNDS = 5;
        @Override
        public String chat(String input) {
            lastTrace.clear();
            List<Message> messages = new ArrayList<>();
            messages.add(Message.system(SYSTEM_PROMPT));
            messages.add(Message.user(input));
            lastTrace.add(Map.of("type", "prompt", "label", "请求携带 tools 字段",
                    "detail", "get_weather / calculator 以 JSON Schema 随请求发给模型"));
            for (int round = 0; round < MAX_ROUNDS; round++) {
                ChatResponse reply = model.chat(messages, TOOLS);
                if (!reply.hasToolCalls()) {
                    lastTrace.add(Map.of("type", "final", "label", "模型给出最终回答", "detail", "自然语言收尾，本轮无需更多工具"));
                    return reply.content();
                }
                // 协议保证的结构化调用请求：assistant 消息原样回传，tool 结果按 id 对号入座
                messages.add(Message.assistantToolCalls(reply.toolCalls()));
                for (ToolCall call : reply.toolCalls()) {
                    lastTrace.add(Map.of("type", "tool", "label", "模型发起 tool_calls",
                            "detail", call.name() + " " + call.argumentsJson()));
                    String result = execute(call.name(), call.argumentsJson());
                    lastTrace.add(Map.of("type", "observation", "label", "工具结果按 tool_call_id 回填", "detail", result));
                    messages.add(Message.tool(call.id(), result));
                }
            }

            return "";
        }
        private String execute(String tool, String argsJson) {
            try {
                Object args = Json.parse(argsJson == null ? "{}" : argsJson);
                return switch (tool) {
                    case "get_weather" -> MockWeather.query(Json.str(args, "city"));
                    case "calculator" -> {
                        Matcher m = Pattern.compile("\\s*(\\d+(?:\\.\\d+)?)\\s*([+\\-*/])\\s*(\\d+(?:\\.\\d+)?)\\s*")
                                .matcher(String.valueOf(Json.str(args, "expression")));
                        if (!m.matches()) yield "无法解析表达式";
                        double a = Double.parseDouble(m.group(1));
                        double b = Double.parseDouble(m.group(3));
                        yield switch (m.group(2)) {
                            case "+" -> String.valueOf(a + b);
                            case "-" -> String.valueOf(a - b);
                            case "*" -> String.valueOf(a * b);
                            case "/" -> b == 0 ? "除数不能为 0" : String.valueOf(a / b);
                            default -> "不支持的运算符";
                        };
                    }
                    default -> "未知工具: " + tool;
                };
            } catch (Exception e) {
                return "工具执行失败: " + e.getMessage();
            }
        }
    }
    static void main(String[] args) {
        ChatModel model = Models.getModel();
        ToolCallAgent agent = new Impl(model);
        System.out.println(agent.chat("帮我查一下北京天气，然后推荐穿搭"));
    }
}
