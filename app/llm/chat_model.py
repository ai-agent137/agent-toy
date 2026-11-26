import abc
class ChatModel(abc.ABC):
    def chat(self,messages) -> str:
         """输入一组对话消息，输出模型回复文本。"""
         raise NotImplementedError
        