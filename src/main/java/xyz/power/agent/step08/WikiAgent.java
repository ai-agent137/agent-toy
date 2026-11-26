package xyz.power.agent.step08;

import xyz.power.agent.Agent;
import xyz.power.llm.ChatModel;
import xyz.power.llm.schemas.Message;
import xyz.power.utils.Models;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public interface WikiAgent extends Agent {
    class Impl implements WikiAgent {
        private final ChatModel model;
        private final List<Map<String, Object>> lastTrace = new ArrayList<>();


        /** 基础 Wiki：项目知识，启动时编译一次（生产中来自仓库里的 AGENTS.md / CLAUDE.md）。 */
        private static final Map<String, String> BASE_WIKI = Map.of(
                "架构约定", "ToyAgent 是零依赖教学项目：一个接口类就是一个智能体场景（step01-26），全部实现放在 Impl 内部类里，禁止引入第三方依赖；模型层统一走 ChatModel 接口。",
                "代码规范", "Java 17 语法；类与核心方法必须有中文 Javadoc 说明教学意图；trace 事件用 type/label/detail 三段式；JSON 一律走自研 Json 工具类。",
                "部署流程", "javac -d target/classes 编译后 java -cp target/classes cn.xiaofuge.ai.Application 启动，默认端口 8099，可用环境变量 TOY_AGENT_PORT 覆盖；前端静态资源在 web/ 目录由服务直接托管。",
                "业务规则", "配置只保存在各人浏览器 localStorage 随请求生效，服务端 config.properties 仅作默认兜底；未配置 API Key 时所有场景回落 Mock 演示模型。");
        /** 用户编译的增量条目（重启后从 wiki/user-wiki.md 恢复）。 */
        private final Map<String, String> userWiki = new LinkedHashMap<>();

        private static final Path USER_WIKI_FILE = Path.of("wiki", "user-wiki.md");

        private static final String SYSTEM_PROMPT = """
                你是项目 Wiki 问答智能体。回答规则：
                1. 优先依据【项目 Wiki】的条目回答，并标注来源条目名，如（来源：架构约定）。
                2. Wiki 条目不足以回答时，如实说"项目 Wiki 中暂无相关条目"，并提示可以用「编译：主题：内容」沉淀该知识。禁止编造。
                """;

        public Impl(ChatModel model) {
            this.model = model;
            loadUserWiki();
        }
        private void loadUserWiki() {
            if (!Files.exists(USER_WIKI_FILE)) return;
            try {
                List<String> lines = Files.readAllLines(USER_WIKI_FILE, StandardCharsets.UTF_8);
                String topic = null;
                StringBuilder content = new StringBuilder();
                for (String line : lines) {
                    if (line.startsWith("## ")) {
                        if (topic != null) userWiki.put(topic, content.toString().trim());
                        topic = line.substring(3).trim();
                        content.setLength(0);
                    } else if (topic != null && !line.startsWith("#")) {
                        content.append(line).append("\n");
                    }
                }
                if (topic != null) userWiki.put(topic, content.toString().trim());
                if (!userWiki.isEmpty()) {
                    System.out.println("[ToyAgent] 已从 " + USER_WIKI_FILE + " 恢复 " + userWiki.size() + " 个编译条目");
                }
            } catch (IOException e) {
                System.err.println("[ToyAgent] 读取 user-wiki 失败: " + e.getMessage());
            }
        }
        @Override
        public String name() {
            return "Step08 · LLM-Wiki 知识编译";
        }

        @Override
        public String chat(String input) {
            lastTrace.clear();
            // ---------------- 编译指令：把对话中的知识沉淀进 Wiki ----------------
            if (input.startsWith("编译：") || input.startsWith("编译:") || input.startsWith("记住：") || input.startsWith("记住:")) {
                return compile(input);
            }
            // ---------------- 加载 Wiki（启动编译一次 + 增量条目） ----------------
            Map<String, String> wiki = currentWiki();
            lastTrace.add(Map.of(
                    "type", "load",
                    "label", "加载项目 Wiki",
                    "detail", "共 " + wiki.size() + " 个条目（内置 " + BASE_WIKI.size() + " + 已编译 " + userWiki.size() + "），无需每次重新检索原始文档"));
            System.out.println("[ToyAgent][Wiki] 加载 Wiki：内置 " + BASE_WIKI.size() + " + 已编译 " + userWiki.size() + " = " + wiki.size() + " 条");
            // ---------------- 相关条目选取（教学版 bigram 打分，生产换 Embedding） ----------------
            record Hit(String topic, String content, double score) {}
            List<Hit> ranked = wiki.entrySet().stream()
                    .map(e -> new Hit(e.getKey(), e.getValue(), score(e.getKey() + e.getValue(), input)))
                    .sorted(Comparator.comparingDouble(Hit::score).reversed())
                    .limit(2)
                    .toList();
            List<Hit> hits = ranked.stream().filter(h -> h.score() > 0).toList();
            lastTrace.add(Map.of(
                    "type", "retrieve",
                    "label", "条目匹配打分（bigram 重合率，取 top2）",
                    "detail", ranked.isEmpty()
                            ? "Wiki 为空"
                            : ranked.stream()
                            .map(h -> h.topic() + "（%.0f 分%s）".formatted(
                                    h.score() * 100,
                                    h.score() > 0 ? "，已注入" : "，低于阈值未注入"))
                            .reduce((a, b) -> a + " ｜ " + b).orElse("")));
            lastTrace.add(Map.of(
                    "type", "retrieve",
                    "label", "命中条目内容",
                    "detail", hits.isEmpty()
                            ? "无相关条目"
                            : hits.stream().map(h -> "◆ " + h.topic() + "：" + (h.content().length() > 40
                                    ? h.content().substring(0, 40) + "…"
                                    : h.content()))
                            .reduce((a, b) -> a + "\n" + b).orElse("")));

            System.out.println("[ToyAgent][Wiki] 匹配打分：" + (ranked.isEmpty() ? "Wiki 为空"
                    : ranked.stream().map(h -> h.topic() + "=%.0f%%".formatted(h.score() * 100))
                    .reduce((a, b) -> a + ", " + b).orElse("")));
            System.out.println("[ToyAgent][Wiki] 注入 " + hits.size() + " 条："
                    + (hits.isEmpty() ? "无" : hits.stream().map(Hit::topic).reduce((a, b) -> a + ", " + b).orElse("")));

            // ---------------- 增强生成 ----------------
            List<Message> messages = new ArrayList<>();
            messages.add(Message.system(SYSTEM_PROMPT));
            if (!hits.isEmpty()) {
                StringBuilder ctx = new StringBuilder("【项目 Wiki】\n");
                for (Hit h : hits) {
                    ctx.append("◆ ").append(h.topic()).append("：").append(h.content()).append("\n");
                }
                messages.add(Message.user(ctx.toString()));
            }
            messages.add(Message.user(input));

            String answer = model.chat(messages);
            lastTrace.add(Map.of(
                    "type", "final",
                    "label", "基于 Wiki 作答完成",
                    "detail", hits.isEmpty() ? "模型按约定提示可编译新条目" : "回答基于 " + hits.size() + " 个 Wiki 条目"));
            System.out.println("[ToyAgent][Wiki] 作答完成：" + (hits.isEmpty() ? "无条目命中，提示可编译" : "基于 " + hits.size() + " 条"));
            System.out.println("[ToyAgent][Wiki] 回答：" + answer.replaceAll("\\s+", " "));
            return answer;
        }

        /** 教学版相似度：字符 bigram 重合率。生产中替换为 Embedding 余弦相似度。 */
        private double score(String text, String query) {
            if (query.length() < 2) return 0;
            Set<String> grams = new java.util.HashSet<>();
            for (int i = 0; i < query.length() - 1; i++) grams.add(query.substring(i, i + 2));
            long hit = 0;
            for (String g : grams) {
                if (text.contains(g)) hit++;
            }
            return grams.isEmpty() ? 0 : (double) hit / grams.size();
        }
        private Map<String, String> currentWiki() {
            Map<String, String> all = new LinkedHashMap<>();
            all.putAll(BASE_WIKI);
            all.putAll(userWiki);
            return all;
        }
        /** 编译：解析「编译：主题：内容」，写入 Wiki 并持久化。 */
        private String compile(String input) {
            String body = input.substring(3).trim(); // 去掉前缀「编译：/记住：」（等宽 3 字符）
            int sep = body.indexOf('：');
            if (sep < 0) sep = body.indexOf(':');
            if (body.isBlank() || sep <= 0 || sep == body.length() - 1) {
                lastTrace.add(Map.of("type", "compile", "label", "编译失败", "detail", "格式：编译：主题：内容"));
                System.out.println("[ToyAgent][Wiki] 编译失败：格式错误，输入=" + input);
                return "编译格式不对哦。请用：「编译：主题：内容」，例如「编译：发布流程：每周三灰度，周五全量上线」。";
            }
            String topic = body.substring(0, sep).trim();
            String content = body.substring(sep + 1).trim();
            boolean update = userWiki.containsKey(topic);
            userWiki.put(topic, content);
            persistUserWiki();

            lastTrace.add(Map.of(
                    "type", "compile",
                    "label", update ? "更新条目" : "新增条目",
                    "detail", "◆ " + topic + "（已持久化到 " + USER_WIKI_FILE + "，重启不丢）"));
            System.out.println("[ToyAgent][Wiki] " + (update ? "更新" : "新增") + "条目：◆ " + topic + "（已持久化到 " + USER_WIKI_FILE + "）");
            lastTrace.add(Map.of(
                    "type", "final",
                    "label", "知识编译完成",
                    "detail", "Wiki 现有 " + currentWiki().size() + " 个条目，后续提问直接复用"));
            return "已编译进项目 Wiki：◆ " + topic + "\n\n" + content + "\n\n之后所有提问都会带上这条知识（已持久化，重启不丢）。试试问我相关内容。";
        }
        /** 持久化用户编译条目（教学版写本地 markdown；生产中提交到代码仓库）。 */
        private void persistUserWiki() {
            try {
                Files.createDirectories(USER_WIKI_FILE.getParent());
                StringBuilder sb = new StringBuilder("# ToyAgent 用户 Wiki（由页面编译生成）\n\n");
                userWiki.forEach((k, v) -> sb.append("## ").append(k).append("\n").append(v).append("\n\n"));
                Files.writeString(USER_WIKI_FILE, sb.toString(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                System.err.println("[ToyAgent] 写入 user-wiki 失败: " + e.getMessage());
                System.out.println("[ToyAgent][Wiki] 持久化失败：" + e.getMessage() + "（条目仅保存在内存，重启会丢）");
            }
        }

    }
    public static void main(String[] args) {
        ChatModel model = Models.getModel();
        WikiAgent agent = new Impl(model);
        String input = "编译：API Key 配置：具体配置入口/字段/保存方式」沉淀到项目 Wiki？";
        System.out.println(" [输入] " + input);
        System.out.println(" [输出] " + agent.chat(input));

    }
}
