package xyz.power.agent.step07;

import xyz.power.agent.Agent;
import xyz.power.llm.ChatModel;
import xyz.power.llm.schemas.Message;
import xyz.power.utils.Models;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public interface RagAgent extends Agent {
    class Impl implements RagAgent {
        private ChatModel model;
        private final List<Map<String, Object>> lastTrace = new ArrayList<>();
        /** 私有知识库：真实系统存向量库（每条文档一个 Embedding 向量 + HNSW 索引），这里用内存文档集合演示。 */
        private static final List<String> KNOWLEDGE = List.of(
                "ToyAgent 是小傅哥的智能体教学项目，用 12 个渐进式场景讲清楚智能体实现，每个场景只有一个接口类。",
                "智能体 = 大模型（大脑）+ 工具（手脚）+ 记忆（延续）+ 循环（节拍）。ReAct 循环是智能体的经典执行模式。",
                "MCP（Model Context Protocol）是 Anthropic 提出的工具标准化协议，核心方法为 tools/list 与 tools/call，实现工具与智能体解耦。",
                "RAG（检索增强生成）先从知识库检索相关片段，再把片段注入提示词让模型基于事实回答，能缓解幻觉与知识过期问题。",
                "Function Calling 让模型输出结构化工具调用请求（工具名 + 参数），由外部程序执行后将结果回填给模型。",
                "上下文窗口是模型一次能处理的最大 Token 数，超出时需要用滑动窗口、摘要压缩等上下文工程手段保护关键信息。");
        /**
         * 系统提示词：为生成阶段划定边界 —— 有据可依时带 [n] 引用，
         * 无据可依时明确拒答。拒答约定是抑制幻觉的关键手段：
         * 没有它，模型会拿相似但无关的知识"脑补"出一个看似合理的答案。
         */
        private static final String SYSTEM_PROMPT = """
                你是知识库问答智能体。回答规则：
                1. 优先依据【参考资料】回答，并标注来源编号如 [1]。
                2. 资料不足以回答时，如实说"知识库中暂无相关内容"，禁止编造。
                """;
        public Impl(ChatModel model) {
            this.model = model;
        }
        @Override
        public String name() {
            return "Step07 · RAG 检索增强生成";
        }

        @Override
        public String chat(String input) {
            lastTrace.clear();

            // 1. 检索：全量文档打分 → 按分降序 → Top2 → 过滤 0 分（无重叠 = 无证据，宁缺毋滥）
            record Hit(String text, double score) {}
            List<Hit> ranked = KNOWLEDGE.stream()
                    .map(text-> new Hit(text, score(text, input)))
                    .sorted(Comparator.comparingDouble(Hit::score).reversed())
                    .limit(2)
                    .toList();
            List<Hit> hits = ranked.stream().filter(h -> h.score > 0).toList();
            lastTrace.add(Map.of(
                    "type", "retrieve",
                    "label", "知识库检索",
                    "detail", hits.isEmpty()
                            ? "未命中任何片段（得分均为 0）"
                            : hits.stream()
                            .map(h -> "%.0f 分 · %s".formatted(h.score() * 100, clip(h.text(), 30)))
                            .reduce((a, b) -> a + " ｜ " + b).orElse("")));
            // 2. 增强：命中片段以 [1][2] 编号注入提示词，编号即回答中的引用出处；
            List<Message> messages = new ArrayList<>();
            messages.add(Message.system(SYSTEM_PROMPT));
            if (!hits.isEmpty()) {
                StringBuilder ctx = new StringBuilder("【参考资料】\n");
                for (int i = 0; i < hits.size(); i++) {
                    ctx.append("[").append(i + 1).append("] ").append(hits.get(i).text()).append("\n");
                }
                messages.add(Message.user(ctx.toString()));
            }
            messages.add(Message.user(input));
            // 3. 生成：模型基于注入的资料作答（或按约定拒答）
            String answer = model.chat(messages);
            lastTrace.add(Map.of(
                    "type", "final",
                    "label", "增强生成完成",
                    "detail", hits.isEmpty() ? "模型按约定拒答" : "回答基于 " + hits.size() + " 个知识片段"));
            return answer;
        }
        /**
         * 教学版相似度：字符 bigram 重合率（查询的所有相邻二字组合，
         * 有多少能在文档中找到）。可理解为把两段文本各自表示成
         * "bigram 是否出现"的 0/1 稀疏向量，再算重合比例 —— 与向量
         * 相似度思想同源（重合率 ≈ Jaccard 近似）。
         * <p>
         * 局限：纯字面匹配，"退货" 搜不到 "退换货流程" 里的同义改写。
         * 生产实现：两端各调一次 Embedding 模型得到稠密向量，改算
         * 余弦相似度：
         * <pre>cos(A, B) = Σ(aᵢ·bᵢ) / (√Σaᵢ² × √Σbᵢ²)</pre>
         * 并将本方法的逐文档比对替换为向量库 ANN 查询（如 HNSW）。
         *
         * @param text  知识库文档
         * @param query 用户问题
         * @return [0, 1] 相似度分，0 表示无任何 bigram 重合
         */
        private double score(String text, String query) {
            // 问题太短（单字符无 bigram）直接判 0 分
            if (query.length() < 2) return 0;
            // 构造查询的 bigram 集合（Set 去重，避免重复组合稀释分母）
            java.util.Set<String> grams = new java.util.HashSet<>();
            for (int i = 0; i < query.length() - 1; i++) grams.add(query.substring(i, i + 2));
            // 统计能在文档中命中的 bigram 个数
            long hit = 0;
            for (String g : grams) {
                if (text.contains(g)) hit++;
            }
            return grams.isEmpty() ? 0 : (double) hit / grams.size();
        }

        /** trace 展示用的截断：超长片段取前 n 个字符加省略号，避免前端明细过长。 */
        private String clip(String s, int n) {
            return s.length() > n ? s.substring(0, n) + "…" : s;
        }
    }
    public static void main(String[] args) {
        ChatModel model = Models.getModel();
        String input = args.length > 0 ? String.join(" ", args) : "ToyAgent 是什么？";
        System.out.println("[输入] " + input);
        System.out.println("[输出] " + new Impl(model).chat(input));
    }
}
