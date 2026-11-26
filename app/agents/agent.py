import abc


class Agent(abc.ABC):
    """所有场景的基类。子类必须实现 name() / chat(input)。"""

    def name(self) -> str:
        """场景名称，用于展示。"""
        raise NotImplementedError

    def chat(self, input: str) -> str:
        """智能体最小契约：输入用户消息，返回回答。"""
        raise NotImplementedError

    def trace(self) -> list:
        """最近一次 chat 的执行轨迹（供渲染时间线），无轨迹则返回空列表。"""
        return []

    def reset(self) -> None:
        """清空场景内部状态（如记忆），无状态场景无需实现。"""
        # 默认无状态