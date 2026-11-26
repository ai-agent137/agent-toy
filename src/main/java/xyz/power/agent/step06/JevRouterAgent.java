package xyz.power.agent.step06;

import xyz.power.agent.Agent;
import xyz.power.agent.step03.ReActAgent;
import xyz.power.llm.ChatModel;
import xyz.power.llm.JevModel;
import xyz.power.llm.schemas.Message;
import xyz.power.utils.Json;
import xyz.power.utils.Models;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public interface JevRouterAgent extends Agent {
    class Impl implements JevRouterAgent {
        private final ChatModel model;
        private final JevModel jev;
        private final List<Map<String, Object>> lastTrace = new ArrayList<>();

        double CONFIDENCE_GATE = 0.75;
        /** LLM 兜底判断用的提示词——与 RouterAgent 相同口径，回落时判断结果口径一致。 */
        private static final String FALLBACK_PROMPT = """
                你是意图识别路由器。分析用户输入，只输出 JSON：
                {"intent": "weather|math|chat", "reason": "一句话理由"}
                - weather: 查询天气
                - math: 四则运算
                - chat: 其他一切对话
                """;
        public Impl(ChatModel model) {
            this(model, Models.getJevModel());
        }
        Impl(ChatModel model, JevModel jev) {
            this.model = model;
            this.jev = jev;
        }
        @Override
        public String name() {
            return "Step06+ · Jev 决策模型路由（System On";
        }

        @Override
        public String chat(String input) {
            lastTrace.clear();

            // 1. 意图判断：优先走 Jev 决策端口，失败或低置信时回落 LLM
            JevModel.Decision decision = null;
            String jevError = null;
            if (jev == null) {
                jevError = "未配置 jev-api-key";
            } else {
                try {
                    decision = jevDecide(input);
                } catch (Exception e) {
                    jevError = "Jev 调用失败：" + e.getMessage();
                }
            }
            String intent;
            if (decision != null) {
                // 2. 置信度门控：答案告诉你"是什么"，置信度告诉你"该不该动手"
                lastTrace.add(Map.of(
                        "type", "intent",
                        "label", "Jev 决策（System One）",
                        "detail", decision.choice()
                                + " · confidence " + String.format("%.2f", decision.confidence())
                                + " · probabilities " + decision.probabilities()));
                if (decision.confidence() >= CONFIDENCE_GATE) {
                    lastTrace.add(Map.of(
                            "type", "action",
                            "label", "置信度门控",
                            "detail", "confidence " + String.format("%.2f", decision.confidence())
                                    + " ≥ " + CONFIDENCE_GATE + " → 直接采信，跳过 LLM 推理"));
                    intent = decision.choice();
                } else {
                    lastTrace.add(Map.of(
                            "type", "action",
                            "label", "置信度门控",
                            "detail", "confidence " + String.format("%.2f", decision.confidence())
                                    + " < " + CONFIDENCE_GATE + " → 不硬猜，回落 LLM 判断"));
                    intent = llmJudge(input);
                }
            } else {
                // 决策端口故障/未配置 → 回落原路径，不新增失败面
                lastTrace.add(Map.of(
                        "type", "intent",
                        "label", "Jev 不可用",
                        "detail", (jevError == null ? "未知原因" : jevError) + " → 回落 LLM 意图识别"));
                intent = llmJudge(input);
            }

            // 3. 按意图路由到处理器 —— 与 RouterAgent 相同的分发骨架
            return switch (intent) {
                case "weather" -> {
                    String city = extractCity(input);
                    lastTrace.add(Map.of("type", "action", "label", "路由 → 天气工具", "detail", "get_weather(" + city + ")"));
                    String result = ReActAgent.MockWeather.query(city);
                    lastTrace.add(Map.of("type", "observation", "label", "工具返回", "detail", result));
                    yield "「" + city + "」现在" + result + "。";
                }
                case "math" -> {
                    lastTrace.add(Map.of("type", "action", "label", "路由 → 计算器", "detail", input));
                    yield "计算结果：" + calc(input) + "。";
                }
                default -> {
                    lastTrace.add(Map.of("type", "action", "label", "路由 → 对话模型", "detail", "无工具直答"));
                    yield model.chat(List.of(
                            Message.system("你是一个友好的中文聊天助手。"),
                            Message.user(input)));
                }
            };
        }

        private String extractCity(String text) {
            for (String city : List.of("北京", "上海", "广州", "深圳", "杭州", "成都")) {
                if (text.contains(city)) return city;
            }
            return "北京";
        }

        private String calc(String expr) {
            Matcher m = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*([+\\-*/])\\s*(\\d+(?:\\.\\d+)?)").matcher(expr);
            if (!m.find()) return "未识别的算式";
            double a = Double.parseDouble(m.group(1));
            double b = Double.parseDouble(m.group(3));
            double r = switch (m.group(2)) {
                case "+" -> a + b;
                case "-" -> a - b;
                case "*" -> a * b;
                case "/" -> b == 0 ? Double.NaN : a / b;
                default -> Double.NaN;
            };
            return Double.isNaN(r) ? "无法计算" : (r == Math.floor(r) ? String.valueOf((long) r) : String.valueOf(r));
        }
        /** LLM 兜底判断：口径与 RouterAgent 完全一致（猜 JSON + 解析失败落 chat）。 */
        private String llmJudge(String input) {
            String raw = model.chat(List.of(
                    Message.system(FALLBACK_PROMPT),
                    Message.user(input))).trim();
            String intent = null;
            try {
                Object parsed = Json.parse(raw
                        .replaceFirst("^```\\w*\\s*", "")
                        .replaceFirst("```\\s*$", "")
                        .trim());
                intent = Json.str(parsed, "intent");
            } catch (Exception ignored) {
            }
            lastTrace.add(Map.of(
                    "type", "intent",
                    "label", "LLM 兜底判断",
                    "detail", (intent == null ? "chat" : intent) + "（无概率、无置信度，靠祈祷）"));
            return intent == null ? "chat" : intent;
        }
        /**
         * 调 Jev 决策端口做意图判断：choice 题型，闭集三选一。
         * <p>
         * criteria 就是判据——"描述即路由"，改描述即可改善判断质量，零代码。
         * 实际生产里还可以在同一次请求里打包"要不要写权限（noul）、难度分档（score）"
         * 等更多问题（见 {@link JevModel#evaluate}），加问题几乎不增加耗时。
         */
        private JevModel.Decision jevDecide(String input) {
            Map<String, Object> criteria = Json.obj();
            criteria.put("weather", "查询天气");
            criteria.put("math", "四则运算或计算");
            criteria.put("chat", "其他一切对话");
            return jev.choice(input, "intent", "用户想让助手做什么？", criteria);
        }
    }
    public static void main(String[] args) {
        ChatModel model = Models.getModel();
        JevRouterAgent agent = new Impl(model);
        String input = args.length > 0 ? String.join(" ", args) : "帮我看看明天上海天气怎么样";
        System.out.println("[输入] " + input);
        System.out.println("[输出] " + agent.chat(input));
        System.out.println("[轨迹]");
        for (Map<String, Object> step : agent.trace()) {
            System.out.println("  · " + step.get("label") + " —— " + step.get("detail"));
        }
    }
}
