package xyz.power.agent.step03;

import xyz.power.agent.Agent;
import xyz.power.llm.ChatModel;

public interface ReActAgent extends Agent {
    class Impl implements ReActAgent{
        private final ChatModel model;
        public Impl(ChatModel model) {
            this.model = model;
        }
        @Override
        public String name() {
            return "";
        }

        @Override
        public String chat(String input) {
            return "";
        }
    }
}
