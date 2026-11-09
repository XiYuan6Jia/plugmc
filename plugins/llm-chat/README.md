# llm-chat（Paper 版）

聊天栏常驻 NPC：玩家在聊天栏以 `@npc` 开头发言，插件异步请求 OpenAI 兼容接口，
把模型回复广播回聊天栏。玩家进服时发送欢迎语，并对公开聊天按概率触发吐槽。

> 这是 Fabric 时代 [hotplugins/llm-chat](../../hotplugins/llm-chat/README.md) 的 Paper 移植版，
> 配置字段完全兼容，可直接复制旧的 `plugmc-hot/llm-chat.json`。

## 与原版的差异

| 能力 | Fabric 原版 | Paper 版 |
| --- | --- | --- |
| 玩家聊天 | `ServerMessageEvents.CHAT_MESSAGE` | `io.papermc.paper.event.player.AsyncChatEvent` |
| 进服欢迎 | tick 轮询在线玩家 | `PlayerJoinEvent` |
| 系统消息吐槽 | `ServerMessageEvents.GAME_MESSAGE`（成就、公告、`/say`） | 仅 `BroadcastMessageEvent`（插件广播与部分 `/say`）；Paper 没有成就/公告事件 |
| 加载方式 | 热加载（`/plugmc reloadall`） | 普通插件，改代码需重启；改配置用 `/npc reload` |
| 配置文件 | 服务器根目录 `plugmc-hot/llm-chat.json` | 插件数据目录 `plugins/llm-chat/config.json` |

首次启动时，如果发现服务器根目录存在 `plugmc-hot/llm-chat.json` 且新配置不存在，会自动复制一份。

## 构建与运行

```powershell
.\gradlew :plugins:llm-chat:build      # 产物 plugins/llm-chat/build/libs/llm-chat-0.1.0.jar
python scripts/run_paper.py            # 本地开发服，自动装入已构建插件
python scripts/deploy_paper.py --plugin llm-chat --upload-file llm-chat=.local/llm-chat/config.json
```

配置放在服务器端 `plugins/llm-chat/config.json`；参考 [config.example.json](config.example.json)。
**密钥不要写入仓库**，本地放在 `.local/`（已被 gitignore）。

## 配置

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
| `banter.systemChat` | 是否把 `BroadcastMessageEvent`（插件广播/部分 `/say`）也纳入吐槽 |
| `banter.ignoreJoinLeave` | 跳过进服/退服消息，避免与欢迎重复（默认 `true`） |
| `banter.chance` | 每条公开消息触发吐槽的概率（0~1，默认 0.12） |
| `banter.cooldownSeconds` | 两次吐槽之间的全局冷却秒数（默认 45） |
| `banter.maxPerWindow` / `banter.windowSeconds` | 时间窗内最多吐槽次数，默认 300 秒内最多 3 次 |
| `banter.minLength` | 触发吐槽的最短消息长度（默认 3） |
| `banter.maxTokens` | 吐槽回复的最大 token 数（默认 80） |
| `banter.broadcast` | `true` 全服广播；`false` 只回给发言玩家 |
| `banter.systemPrompt` | 吐槽用系统提示词（人设与边界） |

## 命令

| 命令 | 权限 | 作用 |
| --- | --- | --- |
| `/npc status` | `plugmc.npc` | 查看接口、模型、密钥掩码、会话数 |
| `/npc clear` | `plugmc.npc` | 清空自己的对话历史；控制台执行会清空全部 |
| `/npc chat <内容>` | `plugmc.npc` | 通过命令直接对话（便于控制台测试） |
| `/npc welcome <玩家名>` | `plugmc.npc.admin` | 手动发送欢迎语（离线名称只渲染模板） |
| `/npc banter <内容>` | `plugmc.npc.admin` | 手动触发一次吐槽（跳过概率与冷却） |
| `/npc reload` | `plugmc.npc.admin` | 重新读取 `config.json` |

`/npc` 默认所有人可用，管理员子命令默认仅 OP（`plugmc.npc.admin`）。

## 行为说明

- `@npc` 触发的正常对话不会触发吐槽，NPC 自己的回复也不会递归触发。
- 随机吐槽针对公开信息：玩家公开聊天 + `BroadcastMessageEvent`；通过后还要满足全局冷却、
  时间窗上限与"同时只有一个吐槽请求"。
- 启用后公开聊天内容会发送到配置的 LLM 接口，请在服务器上向玩家说明。
- 回复通过 Adventure `Component` 逐玩家发送，不经过聊天事件，因此不会自触发。

聊天栏用法：`@npc 你好，介绍一下你自己`。
