# Toy Agent

用 Java 17 实现的最小聊天智能体示例。当前的 `Step 01` 演示如何把一条用户消息发给兼容 OpenAI Chat Completions 的模型接口，并在终端打印回复。项目使用 JDK 自带的 `HttpClient` 和仓库内的 JSON 工具，运行时无需第三方 Java 库。

## 快速开始

准备 JDK 17、Maven 3，以及模型服务的 API 地址、密钥和模型 ID。以下命令在仓库根目录运行。

1. 复制配置模板。如果已有 `config.properties`，直接编辑现有文件。

   ```bash
   cp -n config.properties.example config.properties
   ```

2. 按模型服务提供的信息修改 `config.properties`。

   | 配置项 | 填写方式 |
   | --- | --- |
   | `base-url` | 填写 API 基础地址。保留服务商要求的路径前缀，例如 `/v1` 或 `/compatible-mode/v1`。程序会追加 `/chat/completions`，因此填到该路径之前即可。 |
   | `api-key` | 填写模型服务的 API 密钥。 |
   | `model` | 填写模型服务提供的模型 ID。 |

   程序从当前工作目录读取 `config.properties`。该文件已列入 `.gitignore`，请将真实密钥保存在本地配置中。

3. 打包并启动示例。

   ```bash
   mvn package
   java -cp target/agent-toy.jar xyz.power.agent.step01.ChatAgent
   ```

   示例会发送「你是谁」，并在终端打印模型回复。可以在 `ChatAgent.main` 中修改这句提示词，然后重新打包运行。

## 代码导览

| 入口 | 职责 |
| --- | --- |
| [`ChatAgent`](src/main/java/xyz/power/agent/step01/ChatAgent.java) | 将输入包装成一条 `user` 消息，每次调用发起一次独立请求。 |
| [`Models`](src/main/java/xyz/power/utils/Models.java) | 读取本地配置并创建模型客户端。 |
| [`ChatModel`](src/main/java/xyz/power/llm/ChatModel.java) 和 [`Message`](src/main/java/xyz/power/llm/Message.java) | 定义模型调用接口和消息结构。 |
| [`OpenAiChatModel`](src/main/java/xyz/power/llm/OpenAiChatModel.java) | 构造 Chat Completions 请求，读取回复正文 `choices[0].message.content`。 |
| [`Json`](src/main/java/xyz/power/utils/Json.java) | 编码请求 JSON，解析响应 JSON。发送请求前，客户端会先把每条 `Message` 转成 `Map`，让编码器把 `role` 和 `content` 写成 JSON 字段。 |

## 常见问题

- 找不到 `config.properties` 时，确认文件位于运行命令的目录，并从仓库根目录启动。
- 如果服务返回 `401` 或 `403`，检查密钥、服务地址和账号权限。如果返回 `404`，检查基础地址是否包含服务商要求的路径前缀，以及是否重复填写了 `/chat/completions`。
