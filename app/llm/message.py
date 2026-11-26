class Message:
    __slot__ = ("role", "content")
    
    def __init__(self, role: str, content: str):
        self.role = role
        self.content = content

    @staticmethod
    def system(content: str) -> "Message":
        return Message("system", content)
    def user(content: str) -> "Message":
        return Message("user", content)
    def assistant(content: str) -> "Message":
        return Message("assistant", content)