package xyz.power.agent.step01;

import xyz.power.agent.Agent;
import xyz.power.llm.ChatModel;
import xyz.power.llm.schemas.Message;
import xyz.power.utils.Models;

import java.util.List;

public interface ChatAgent extends Agent {
    class Impl implements ChatAgent {
        private final ChatModel model;
        public Impl(ChatModel model) {
            this.model = model;
        }
        @Override
        public String name() {
            return "step01 - 对话智能体（最小MVP）";
        }

        @Override
        public String chat(String input)  {
            return model.chat(List.of(Message.user(input)));
        }
    }
    public static void main(String[] args) {
        ChatModel model = Models.getModel();
        ChatAgent chatAgent = new ChatAgent.Impl(model);
        String answer = chatAgent.chat("你是谁");
        System.out.println(answer);
    }
}
