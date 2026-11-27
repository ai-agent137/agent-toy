package xyz.power.agent.step12;

import xyz.power.agent.Agent;
import xyz.power.agent.step10.SkillAgent;
import xyz.power.llm.ChatModel;
import xyz.power.llm.schemas.Message;
import xyz.power.utils.Json;
import xyz.power.utils.Models;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public interface MultiAgent extends Agent {
    class Impl implements MultiAgent{
        /**
         * 角色档案 —— 对应真实工具里的一个角色配置文件。
         * Codex 的 .toml 还带 model（按任务选模型）与 sandbox权限；教学版先只留最核心的提示词。
         */
        record AgentSpec(String key, String emoji, String system) {}
        /**
         * 角色注册表 —— 等价于 .codex/agents/ 或 .claude/agents/ 目录。
         * 加一个角色 = 加一行 put，协作流程零改动。
         */
        private static final Map<String, AgentSpec> ROLES = new LinkedHashMap<>();
        static {
            ROLES.put("planner", new AgentSpec("planner", "🧭", """
                    你是规划者（Planner）。把用户任务拆解为三个角色的执行计划，只输出 JSON：
                    {"plan": [{"role": "研究员", "task": "..."}, {"role": "写手", "task": "..."}, {"role": "审查员", "task": "..."}]}
                    """));
            ROLES.put("researcher", new AgentSpec("researcher", "🔬",
                    "你是研究员（Researcher）。围绕任务输出 3 条以内研究要点，每条一句话。"));
            ROLES.put("writer", new AgentSpec("writer", "✍️",
                    "你是写手（Writer）。根据任务与素材写一段 100 字以内的短文，直接输出短文。"));
            ROLES.put("reviewer", new AgentSpec("reviewer", "🔍", """
                    你是审查员（Reviewer）。审查短文，只输出 JSON：
                    {"pass": true/false, "comment": "一句话点评"}
                    """));
        }
        /** 循环护栏：审查最多 2 轮 —— 真实工具里的 max_iterations / kill criteria。 */
        private static final int MAX_REVIEW_ROUNDS = 2;

        private final ChatModel model;
        private final List<Map<String, Object>> lastTrace = new ArrayList<>();

        public Impl(ChatModel model) {
            this.model = model;
        }
        @Override
        public String name() {
            return "Step12 · 多智能体协作（注册表 · 流水线 · 护栏）";
        }

        @Override
        public String chat(String input) {
            lastTrace.clear();
            lastTrace.add(Map.of("type", "tool", "label", "🧩 加载角色注册表",
                    "detail", "已声明 " + ROLES.size() + " 个角色：" + String.join("、", ROLES.keySet())
                            + "（真实工具里这就是 .codex/agents/ 或 .claude/agents/ 下的配置文件）"));
            // 1. 规划者：拆解任务 —— 注意每次派遣都是全新上下文，角色之间互相看不到对方的过程
            Object planObj = tryParse(dispatch("planner", input));
            List<?> plan = planObj instanceof Map<?, ?> m && m.get("plan") instanceof List<?> l ? l : List.of();
            lastTrace.add(Map.of(
                    "type", "agent",
                    "label", "🧭 规划者",
                    "detail", "产出 " + plan.size() + " 步计划：" + describe(plan)));

            // 2. 研究员：收集素材 —— 搜索与整理的噪声留在自己的上下文里，回传的只是要点摘要
            String notes = dispatch("researcher", input);
            lastTrace.add(Map.of("type", "agent", "label", "🔬 研究员（独立上下文）",
                    "detail", clip(notes, 60)));
            // 3. 写手：基于素材成稿 —— 只拿到「研究笔记」这份摘要，而不是研究员的完整过程
            String draft = dispatch("writer", "任务: " + input + "\n研究笔记: " + notes);
            lastTrace.add(Map.of("type", "agent", "label", "✍️ 写手",
                    "detail", clip(draft, 60)));

            // 4. 审查员 + 循环护栏：不通过就打回写手重做，最多 MAX_REVIEW_ROUNDS 轮
            boolean pass = false;
            for (int round = 1; ; round++) {
                Object review = tryParse(dispatch("reviewer", draft));
                pass = Boolean.parseBoolean(String.valueOf(asMap(review).get("pass")));
                String c = Json.str(review, "comment");
                lastTrace.add(Map.of(
                        "type", pass ? "guard" : "warn",
                        "label", pass ? "✅ 审查员：通过（第 " + round + " 轮）" : "⚠️ 审查员：打回（第 " + round + " 轮）",
                        "detail", c == null ? "" : c));
                if (pass || round == MAX_REVIEW_ROUNDS) break;

                draft = dispatch("writer", "请按审查意见修改短文。\n原文: " + draft + "\n审查意见: " + Json.str(review, "comment"));
                lastTrace.add(Map.of("type", "agent", "label", "✍️ 写手修订",
                        "detail", "带着审查意见重写：" + clip(draft, 60)));
            }

            // 5. 主持者汇总：只把最终结论交回用户，子代理的全部中间过程对用户不可见
            lastTrace.add(Map.of("type", "final", "label", "🏁 主持者汇总",
                    "detail", (pass ? "一次过审" : "已达护栏上限，带审查意见交付")
                            + "；链路：规划 → 研究 → 写作 ⇄ 审查 → 交付"));
            return draft;
        }
        private String describe(List<?> plan) {
            StringBuilder sb = new StringBuilder();
            for (Object o : plan) {
                if (o instanceof Map<?, ?> m) {
                    sb.append(String.valueOf(m.get("role"))).append("→");
                }
            }
            if (!sb.isEmpty()) sb.setLength(sb.length() - 1);
            return sb.toString();
        }
        private Map<?, ?> asMap(Object o) {
            return o instanceof Map<?, ?> m ? m : Map.of();
        }

        private String clip(String s, int n) {
            String one = s.replaceAll("\\s+", " ").trim();
            return one.length() > n ? one.substring(0, n) + "…" : one;
        }
        private Object tryParse(String raw) {
            try {
                return Json.parse(raw.replaceFirst("^```\\w*\\s*", "").replaceFirst("```\\s*$", "").trim());
            } catch (Exception e) {
                return Json.obj();
            }
        }
        /**
         * 派遣一个角色 —— 多智能体的最小执行单元：
         * 全新消息列表 = 全新上下文（上下文隔离），只把文本结论带回主线（摘要回传）。
         * 真实工具在此基础上再加：工具白名单、按角色选模型、并行派遣。
         */
        private String dispatch(String key, String task) {
            AgentSpec spec = ROLES.get(key);
            return model.chat(List.of(Message.system(spec.system()), Message.user(task))).trim();
        }
    }
    public static void main(String[] args) {
        ChatModel model = Models.getModel();
        String input = "让多个子智能体协作回答：什么是 AI";
        System.out.println("[输入] " + input);
        System.out.println("[输出] " + new Impl(model).chat(input));
    }
}
