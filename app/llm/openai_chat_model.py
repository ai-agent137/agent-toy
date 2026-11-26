import json
import re
from urllib import request, error

from app.llm.chat_model import ChatModel

def _snippet(s: str) -> str:
    if s is None:
        return ""
    return s[:300] + "..." if len(s) > 300 else s
class OpenAIChatModel(ChatModel):
    def __init__(self, base_url: str, api_key:str, model: str) -> None:
        self._base_url = re.sub(r"/+$", "", base_url)
        self._api_key = api_key
        self._model = model
    def chat(self, messages) -> str:
        body = {
            "model": self._model,
            "messages": [{"role": m.role, "content": m.content} for m in messages],
            "temperature": 0.7
        }
        http_request = request.Request(
            self._base_url + "/chat/completions",
            data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
            headers={
                "Content-Type": "application/json",
                "Authorization": "Bearer " + self._api_key,
            },
            method="POST"
        )
        # 2. 调用模型
        try:
            with request.urlopen(http_request, timeout=120) as res:
                raw = res.read().decode("utf-8")
        except error.HTTPError as e:
              detail = e.read().decode("utf-8", "replace") if e.fp else ""
              raise RuntimeError(f"模型调用失败 HTTP {e.code}: {_snippet(detail)}") from None
        except Exception as e:
            raise RuntimeError(f"模型调用异常: {e}") from None   

        try:
            data = json.loads(raw)
            return str(data["choices"][0]["message"]["content"])
        except Exception as e:
            raise RuntimeError(f"模型调用异常: {e}") from None         
