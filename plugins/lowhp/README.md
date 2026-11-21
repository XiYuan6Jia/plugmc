# LowHP

低血量提示音：玩家血量低于阈值时，**随机给该玩家自己**播放一段内置音频
（其他玩家听不到，也不占用原版物品、不写入存档）。

内置两段音频：

| ID | 内容 | 时长 |
| --- | --- | --- |
| `a` | 邦吧邦吧邦吧（雨爱·司凤版） | 2:20 |
| `b` | 你的呼吸像雨滴（雨爱 DJ 1.03x） | 3:17 |

> 音频只能由客户端播放，所以插件会把 ogg 打包成资源包，通过内置 HTTP 服务下发。
> 玩家没接受资源包就听不到声音（可在 `pack.required` 设为 `true` 强制）。

## 行为

- 每 0.5 秒检查一次在线玩家，血量 ≤ `trigger.threshold-percent`（默认 30%，20 点血即 6 点）时
  随机挑一段播放给**这名玩家**；
- 同一玩家两次提示之间至少间隔 `trigger.cooldown-seconds`（默认 120 秒），
  片段播放期间不会重复触发；
- 回血到 `trigger.heal-percent`（默认 45%）以上会立刻停止播放并重置冷却；
- 玩家死亡、下线、执行 `/lowhp stop` 都会停止播放；
- 玩家可用 `/lowhp toggle` 关闭自己的提示音（写入 `plugins/lowhp/muted.yml`，重启保留）。

## 命令

| 命令 | 作用 | 权限 |
| --- | --- | --- |
| `/lowhp` / `/lowhp status` | 查看阈值、冷却、曲目与自己的当前血量 | `lowhp.use` |
| `/lowhp test [a\|b]` | 试听（不填随机），只有自己听得到 | `lowhp.use` |
| `/lowhp toggle` | 开关自己的低血量提示音 | `lowhp.use` |
| `/lowhp stop` | 停止当前播放 | `lowhp.use` |
| `/lowhp pack [玩家]` | 重新下发资源包 | `lowhp.admin`（指定他人） |
| `/lowhp reload` | 重载配置并重建资源包 | `lowhp.admin` |

别名：`/lowhealth`、`/xuekuang`。

## 配置（`plugins/lowhp/config.yml`）

| 键 | 说明 |
| --- | --- |
| `enabled` | 总开关 |
| `trigger.threshold-percent` | 触发阈值（最大生命值比例），默认 `0.3` |
| `trigger.heal-percent` | 回血到该比例以上停止播放，默认 `0.45`（不得低于触发阈值） |
| `trigger.cooldown-seconds` | 同一玩家的最小间隔，默认 `120` |
| `playback.volume` / `playback.pitch` | 播放音量与音高 |
| `playback.stop-when-healed` | 回血后立刻停止，默认 `true` |
| `playback.notify-player` | 触发时聊天栏提示一行，默认 `true` |
| `alerts.enabled` | 参与随机抽取的曲目，默认 `[a, b]` |
| `pack.public-url` | 玩家下载资源包的地址（对外可访问） |
| `pack.port` / `pack.bind-address` | 内置 HTTP 服务监听（默认 `20103`） |
| `pack.min-format` / `pack.max-format` | 资源包格式范围 `[major, minor]`；26.3 为 `97.1`，默认 `[97,0]`–`[97,99]` |
| `pack.join-delay-ticks` | 进服后延迟多少 tick 下发资源包，默认 `60` |
| `pack.required` | 是否强制接受资源包 |
| `pack.prompt` | 资源包提示文字 |

## 资源包

内置 HTTP 服务只暴露三个路径：

| 路径 | 说明 |
| --- | --- |
| `/lowhp-alert.zip` | 资源包本体（约 2.5 MB） |
| `/lowhp-alert.sha1` | SHA-1 |
| `/` | 包信息 |

- 音频从插件 jar 里的 `pack/alert_a.ogg`、`pack/alert_b.ogg` 读取，启动时打包到
  `plugins/lowhp/pack/lowhp-alert.zip`（`/lowhp reload` 会重建）；
- 声音事件为 `lowhp:alert.a` / `lowhp:alert.b`，`sounds.json` 使用 `"stream": true`；
- `pack.mcmeta` 使用 `min_format` / `max_format`（26.3 的资源包格式是 **97.1**，
  可在服务端 jar 的 `version.json` → `pack_version.resource_major/resource_minor` 查到）；
- 播放调用 `Player#playSound(location, key, PLAYERS, volume, pitch)`，**只发给该玩家**。

## 实现要点

- 时长由 OGG 最后一个页的 granule position ÷ 采样率推算，用来安排自动停止任务；
- 阈值、回血线都用最大生命值（`Attribute.MAX_HEALTH`）计算，兼容自定义血量的玩家；
- 冷却与"正在播放"状态放在内存，重启后清零，不会残留；
- 静音名单单独存 `muted.yml`，避免和配置混在一起。
