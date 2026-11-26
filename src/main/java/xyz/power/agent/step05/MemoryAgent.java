package xyz.power.agent.step05;

import xyz.power.agent.Agent;
import xyz.power.llm.ChatModel;
import xyz.power.llm.schemas.Message;
import xyz.power.utils.Models;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public interface MemoryAgent extends Agent {
    class Impl implements MemoryAgent {
        private ChatModel model;
        private static final int MAX_SHORT_MESSAGES = 4;
        private final Path file;
        private final List<Message> shortMemory = new ArrayList<>();
        private final Map<String, String> database = new LinkedHashMap<>();
        private final List<Map<String, Object>> lastTrace = new ArrayList<>();
        private String summary = "";

        public Impl(ChatModel model, Path file) {
            this.model = model;
            this.file = file;
        }
        @Override
        public String name() {
            return "Step05 · 记忆实验台";
        }

        @Override
        public String chat(String input) {
            lastTrace.clear();
            shortMemory.add(Message.user(input));
            if(shortMemory.size() >= MAX_SHORT_MESSAGES) {
                compress();
            }
            String databaseMemory = recallDatabase(input);
            List<Message> messages = buildContext(databaseMemory);

            lastTrace.add(Map.of(
                    "type", "memory",
                    "label", "本次装进上下文",
                    "detail", "文件记忆 + 数据库记忆 + 压缩摘要 + " + shortMemory.size() + " 条短期记忆"));
            String answer = model.chat(messages);
            shortMemory.add(Message.assistant(answer));
            saveFile();
            lastTrace.add(Map.of(
                    "type", "final",
                    "label", "回答完成",
                    "detail", "短期记忆已更新，长期记忆已落盘"));
            return answer;
        }
        private String readMemoryText() {
            if (database.isEmpty()) {
                return " (空) ";
            }
            List<String> data = database.keySet().stream().map(k -> k + "=" + database.get(k)).toList();
            return String.join("\n", data);
        }
        private List<Message> buildContext(String databaseMemory) {
            List<Message> context = new ArrayList<>();
            context.add(Message.system("""
                    你是一个有记忆的助手。回答时优先使用“记忆区”里的事实；
                    如果记忆里没有，就明确说不知道，不要编造。
                    """));
            context.add(Message.system("【长期记忆/文件】\n" + readMemoryText()));
            context.add(Message.system("【结构记忆/数据库】\n" + databaseMemory));
            if (!summary.isEmpty()) {
                context.add(Message.system(summary));
            }
            context.add(Message.system("【短期记忆】"));
            context.addAll(shortMemory);
            System.out.println(context);
            return context;
        }
        private String recallDatabase(String input) {
            String key;
            if (input.contains("咖啡")) key = "coffee";
            else if (input.contains("项目")) key = "project";
            else if (input.contains("名字")) key = "name";
            else key = "";

            if (key.isEmpty() || !database.containsKey(key)) {
                return "（没有命中）";
            }
            return key + " = " + database.get(key);
        }
        private void compress() {
            Message question = shortMemory.remove(0);
            Message answer = shortMemory.remove(0);
            String digest = clip(question.content()) + "->" + clip(answer.content());
            summary = summary.isEmpty() ? digest : summary + ";" + digest;
            lastTrace.add(Map.of(
                    "type", "compress",
                    "label", "触发摘要压缩",
                    "detail", "最旧 1 轮移出短期窗口，当前摘要：" + summary));

        }
        public void rememberInDatabase(String key, String value) {
            database.put(key, value);
            saveFile();
        }
        public void saveFile(){
            try {
                Files.createDirectories(file.getParent());
                Properties properties = new Properties();
                database.forEach(properties::setProperty);
                try(OutputStream out = Files.newOutputStream(file)) {
                    properties.store(out, "Step05 long-term memory");
                }

            } catch (IOException e) {
                throw new RuntimeException(e);
            }

        }
        public void reset() {
            shortMemory.clear();
            summary = "";
        }
        public String clip(String text) {
            String value = text.replaceAll("\\s+", " ").trim();
            return value.length() > 28? value.substring(0, 28)+"..." : value;
        }
    }
    public static void main(String[] args) {
        ChatModel model = Models.getModel();
        Impl agent = new Impl(model, Path.of("runtime", "step05-memory.properties"));
        agent.rememberInDatabase("name", "小傅哥");
        agent.rememberInDatabase("coffee", "冰拿铁");
        agent.rememberInDatabase("project", "ToyAgent");
        String[][] script = {
                {"我叫小傅哥，记住我的名字。", "第一轮：名字写入数据库记忆"},
                {"我最近在做 ToyAgent。", "第二轮：项目事实进入短期记忆"},
                {"我想优化上下文压缩。", "第三轮：触发滑动窗口压缩"},
                {"新会话测试：我平时喝什么咖啡？", "第四轮：短期记忆已重置，仍查长期记忆"},
                {"那我叫什么名字？在做什么项目？", "第五轮：一次回答引用结构记忆"}
        };
        for(int i = 0; i < script.length; i++){
            if(i ==3) {
                agent.reset();
            }
            String[] step = script[i];
            System.out.println("\n[输入] " +step[0]);
            System.out.println("[输出] " + agent.chat(step[0]));
            System.out.println("[说明] " + step[1]);
        }
    }
}
