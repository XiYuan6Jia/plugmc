# llm-chat

常驻聊天栏 NPC 热插件：玩家在聊天栏以 `@npc` 开头发言，插件异步请求 OpenAI 兼容接口，
把模型回复广播回聊天栏。当前只做基础聊天（短期上下文、无工具调用、无实体）。

## 构建与加载

```powershell
.\gradlew :hotplugins:llm-chat:build
python scripts/deploy_hot.py --project llm-chat --config run/plugmc-hot/llm-chat.json
```

开发服：把 `hotplugins/llm-chat/build/libs/llm-chat-0.1.0.jar` 放进 `run/plugmc-hot/`，
配置文件放到 `run/plugmc-hot/llm-chat.json`，启动服务器或在控制台执行 `/plugmc reloadall`。

## 配置

配置路径固定为服务器根目录的 `plugmc-hot/llm-chat.json`（开发服为 `run/plugmc-hot/llm-chat.json`），
首次启动时若不存在会自动生成一份空密钥模板。参考 [llm-chat.example.json](llm-chat.example.json)。

| 字段 | 说明 |
| --- | --- |
| `api.baseUrl` | OpenAI 兼容接口根地址，默认 `https://api.siliconflow.cn/v1` |
| `api.model` | 模型名，默认 `Qwen/Qwen3.5-9B` |
| `api.apiKey` | API 密钥；留空时回退到环境变量 `SILICONFLOW_API_KEY` / `PLUGMC_LLM_API_KEY` |
| `api.temperature` / `api.maxTokens` / `api.timeoutSeconds` | 生成参数与超时 |
| `api.enableThinking` | Qwen 系模型是否开启思考（默认 `false`，关闭后响应明显更快） |
| `api.systemPrompt` | 系统提示词（NPC 人设） |
| `npc.name` | NPC 名称（仅展示用） |
| `npc.triggerPrefix` | 聊天触发前缀，默认 `@npc`，大小写不敏感 |
| `npc.replyPrefix` | 回复前缀，默认 `[小页] ` |
| `npc.historyTurns` | 保留的历史对话轮数（0 表示不带上下文） |
| `npc.broadcast` | `true` 广播回复；`false` 只回给提问者 |
| `welcome.enabled` | 是否在玩家进服时发送欢迎语 |
| `welcome.message` | 欢迎语模板，支持 `{player}`、`{online}` 占位符 |
| `welcome.broadcast` | `true` 全服广播；`false` 只发给进服玩家 |
| `banter.enabled` | 是否对公开聊天按概率触发 NPC 吐槽 |
| `banter.chance` | 每条公开消息触发吐槽的概率（0~1，默认 0.12） |
| `banter.cooldownSeconds` | 两次吐槽之间的全局冷却秒数（默认 45） |
| `banter.minLength` | 触发吐槽的最短消息长度（默认 3） |
| `banter.maxTokens` | 吐槽回复的最大 token 数（默认 80） |
| `banter.broadcast` | `true` 全服广播；`false` 只回给发言玩家 |
| `banter.systemPrompt` | 吐槽用系统提示词（人设与边界） |

**密钥不要写入仓库**。本地放在 `.local/` 或 `run/plugmc-hot/llm-chat.json`（均被 gitignore），
测试服通过 `scripts/deploy_hot.py --config <本地配置>` 上传到服务器。

## 命令

| 命令 | 作用 |
| --- | --- |
| `/npc status` | 查看接口、模型、密钥掩码、会话数 |
| `/npc reload` | 重新读取配置（管理员） |
| `/npc clear` | 清空自己的对话历史；控制台执行会清空全部 |
| `/npc chat <内容>` | 通过命令直接对话（便于控制台测试） |
| `/npc welcome <玩家名>` | 手动发送欢迎语（管理员；离线名称可用于预览模板） |
| `/npc banter <内容>` | 手动触发一次吐槽请求（管理员，便于测试） |

进服欢迎通过 tick 监测在线玩家变化实现：插件加载时已在线玩家不会重复欢迎，之后新加入的玩家
会收到 `welcome.message` 渲染后的广播。

随机吐槽只针对**公开聊天**（不含命令、私聊和 NPC 自身消息）：每条消息按 `banter.chance` 掷骰，
通过后还要满足全局冷却与“同时只有一个吐槽请求”。`@npc` 触发的正常对话不会触发吐槽。
启用后公开聊天内容会发送到配置的 LLM 接口，请在服务器上向玩家说明。

聊天栏用法：`@npc 你好，介绍一下你自己`。
