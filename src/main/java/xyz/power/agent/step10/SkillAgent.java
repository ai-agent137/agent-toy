package xyz.power.agent.step10;

import xyz.power.agent.Agent;
import xyz.power.agent.step03.ReActAgent;
import xyz.power.agent.step09.McpAgent;
import xyz.power.llm.ChatModel;
import xyz.power.llm.schemas.ChatResponse;
import xyz.power.llm.schemas.Message;
import xyz.power.llm.schemas.ToolCall;
import xyz.power.utils.Json;
import xyz.power.utils.Models;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public interface SkillAgent extends Agent {

    /**
     * 一个可复用的技能：声明式定义，运行时被模型选中后展开执行。
     *
     * @param name        技能名（模型路由时使用）
     * @param level       抽象层级：L0 提示词 / L1 工具组合 / L2 子流程
     * @param description 给模型看的描述 —— 路由阶段唯一注入的内容
     * @param instructions 技能指令体 —— 选中后才注入 system prompt，指导模型怎么干
     * @param tools       该技能暴露的工具 JSON Schema 清单（L0 为空）
     */
    record Skill(String name, String level, String description,
                 String instructions, List<Map<String, Object>> tools) {
    }

    class Impl implements SkillAgent {
        private final ChatModel model;
        private final List<Map<String, Object>> lastTrace = new ArrayList<>();
        /** 本地工具实现：与 Step04 一致，协议回填时按名执行。 */
        private String execute(String tool, String argsJson) {
            try {
                Object args = Json.parse(argsJson == null ? "{}" : argsJson);
                return switch (tool) {
                    case "get_weather" -> ReActAgent.MockWeather.query(Json.str(args, "city"));
                    case "get_time" -> LocalDateTime.now()
                            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
                    default -> "未知工具: " + tool;
                };
            } catch (Exception e) {
                return "工具执行失败: " + e.getMessage();
            }
        }
        /** 按 OpenAI tools 协议构造一个工具的 JSON Schema。 */
        private static Map<String, Object> tool(String name, String desc, Map<String, Object> params) {
            Map<String, Object> fn = Json.obj();
            fn.put("name", name);
            fn.put("description", desc);
            fn.put("parameters", params);
            Map<String, Object> schema = Json.obj();
            schema.put("type", "function");
            schema.put("function", fn);
            return schema;
        }
        private static Map<String, Object> strParam(String name, String desc) {
            return Map.of("type", "object",
                    "properties", Map.of(name, Map.of("type", "string", "description", desc)),
                    "required", List.of(name));
        }
        // ---------------------------------------------------------------- 技能注册表
        // 声明式定义：加一个技能只需在此加一行，路由提示词自动生成，无需改任何 switch。

        private static final List<Skill> SKILLS = List.of(
                new Skill("travel_plan", "L1", "旅行攻略 —— 查天气与时间后生成出行建议",
                        """
                        你是旅行顾问。请先调用 get_weather 查询用户提到城市的天气，
                        再调用 get_time 获取当前时间，然后基于两项真实数据
                        给出 3 条以内的出行要点。""",
                        List.of(
                                tool("get_weather", "查询指定城市的实时天气", strParam("city", "城市名，如：北京")),
                                tool("get_time", "获取当前日期时间", Map.of("type", "object", "properties", Json.obj())))),
                new Skill("weather_report", "L1", "天气播报 —— 查询并播报某城市天气",
                        """
                        你是天气播报员。请调用 get_weather 查询用户提到城市的天气，
                        用一句话自然地播报结果。""",
                        List.of(tool("get_weather", "查询指定城市的实时天气", strParam("city", "城市名，如：北京")))),
                new Skill("free_chat", "L0", "自由聊天 —— 不用工具的日常对话",
                        "你是友好的中文助手，回答保持简洁。",
                        List.of()));
        /** 路由阶段唯一的工具：让模型结构化地选技能，而不是输出自由文本 JSON。 */
        private final List<Map<String, Object>> ROUTER_TOOLS = List.of(
                tool("choose_skill", "根据用户输入从已注册技能中选择一项",
                        Map.of("type", "object",
                                "properties", Map.of(
                                        "skill", Map.of("type", "string",
                                                "description", "技能名",
                                                "enum", SKILLS.stream().map(Skill::name).toList()),
                                        "reason", Map.of("type", "string", "description", "一句话理由")),
                                "required", List.of("skill"))));
        public Impl(ChatModel model) {
            this.model = model;
        }
        @Override
        public String name() {
            return "Step10 · 技能编排（Skills L0/L1/L2）";
        }

        @Override
        public String chat(String input) {
            lastTrace.clear();

            // ---- 阶段 1 · 技能选择：元数据注入 + choose_skill 结构化路由 ----
            StringBuilder catalog = new StringBuilder("你是技能路由器。当前已注册技能：\n");
            for (Skill s : SKILLS) {
                catalog.append("- ").append(s.name()).append('(').append(s.level()).append("): ")
                        .append(s.description()).append('\n');
            }
            catalog.append("根据用户输入，调用 choose_skill 选择一项技能。");

            ChatResponse routed = model.chat(
                    List.of(Message.system(catalog.toString()), Message.user(input)), ROUTER_TOOLS);

            Skill chosen = SKILLS.get(SKILLS.size() - 1); // 兜底：free_chat
            String reason = "模型未发起路由调用，回退 free_chat";
            if (routed.hasToolCalls()) {
                for (ToolCall call : routed.toolCalls()) {
                    if ("choose_skill".equals(call.name())) {
                        Object args = Json.parse(call.argumentsJson() == null ? "{}" : call.argumentsJson());
                        String want = Json.str(args, "skill");
                        for (Skill s : SKILLS) {
                            if (s.name().equals(want)) {
                                chosen = s;
                                reason = String.valueOf(Json.str(args, "reason"));
                            }
                        }
                    }
                }
            }
            lastTrace.add(Map.of("type", "skill", "label", "技能选择（choose_skill）",
                    "detail", chosen.name() + "（" + reason + "）"));

            // ---- 阶段 2 · 技能执行：注入指令体 + 工具清单，标准 tool_calls 循环 ----
            return runSkill(chosen, input);
        }
        /** 选中技能后"展开"：指令体进 system，工具进 tools 字段，模型自主驱动。 */
        private String runSkill(Skill skill, String input) {
            List<Message> messages = new ArrayList<>();
            messages.add(Message.system(skill.instructions()));
            messages.add(Message.user(input));

            for (int round = 1; round <= 5; round++) {
                ChatResponse reply = model.chat(messages, skill.tools());

                if (!reply.hasToolCalls()) {
                    lastTrace.add(Map.of("type", "final",
                            "label", skill.name() + " 完成",
                            "detail", skill.level() + " 技能执行完毕，模型给出最终回答"));
                    return reply.content();
                }

                messages.add(Message.assistantToolCalls(reply.toolCalls()));
                for (ToolCall call : reply.toolCalls()) {
                    lastTrace.add(Map.of("type", "tool", "label", "模型发起 tool_calls",
                            "detail", call.name() + " " + call.argumentsJson()));
                    String result = execute(call.name(), call.argumentsJson());
                    lastTrace.add(Map.of("type", "observation", "label", "工具结果回填", "detail", result));
                    messages.add(Message.tool(call.id(), result));
                }
            }
            return "已达最大工具轮数，为防失控提前终止。";
        }
    }
    public static void main(String[] args) {
        ChatModel model = Models.getModel();
        String input = "帮我做一份去深圳的出行攻略";
        System.out.println("[输入] " + input);
        System.out.println("[输出] " + new Impl(model).chat(input));
    }
}
