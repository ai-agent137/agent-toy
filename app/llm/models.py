import os
import sys
from app.llm.openai_chat_model import OpenAIChatModel


def _read_properties(path: str) -> dict:
    """手写的 Java properties 解析。"""
    props = {}
    try:
        with open(path, encoding="utf-8") as f:
            for line in f:
                s = line.strip()
                if not s or s.startswith("#") or s.startswith("!"):
                    continue
                i_eq = s.find("=")
                if i_eq < 0 :
                    continue
                if i_eq > 0:
                   props[s[:i_eq].strip()] = s[i_eq + 1:].strip()

    except FileNotFoundError:
        return {}
    except (OSError, UnicodeError) as e:
        print(f"[ToyAgent] 读取 config.properties 失败: {e}", file=sys.stderr)
        return {}
    return props

def _load() -> tuple[str, str, str]:
    props = _read_properties("config.properties")
    base_url = props.get("base-url")
    api_key = props.get("api-key")
    model_name = props.get("model")
    key_src = props.get("api-key")
    key_from_file = key_src is not None and bool(key_src.strip())
    if api_key.strip():
        print("[ToyAgent] 默认模型: " + model_name + " @ " + base_url
              + ("（Key 来自 config.properties）" if key_from_file else "（Key 来自环境变量）"))
    return base_url, api_key, model_name


def get_model():
    base_url, api_key, modle_name = _load()
    return OpenAIChatModel(base_url, api_key, modle_name)
