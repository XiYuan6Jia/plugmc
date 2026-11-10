# anti-xray — 反矿物透视

两条腿：

1. **隐藏矿挖掘预警**：记录玩家挖到的矿石，以及其中「六个面都没有空气/液体」的隐藏矿；
   在时间窗口内隐藏矿数量与占比同时超过阈值时，向有 `antixray.notify` 权限的在线玩家和
   控制台发出预警，并写入 `plugins/anti-xray/alerts.log`。
2. **Paper 内置防透视配置管理**：读取 `config/paper-world-defaults.yml` 的 `anti-xray` 段，
   可用 `/antixray apply` 一键写入 `enabled: true` 与指定 `engine-mode`（写入前自动备份
   `.bak-时间戳`，保留注释和其它配置，重启后生效）。

> 真正的「不让客户端看到矿」依赖 Paper 内置 anti-xray；本插件的检测用于发现已经发生的异常
> 挖掘行为，两者配合使用效果最好。

## 命令

| 命令 | 说明 | 权限 |
| --- | --- | --- |
| `/antixray status [玩家]` | 查看 Paper 防透视状态与玩家矿石统计 | `antixray.admin` |
| `/antixray apply` | 写入 `anti-xray.enabled: true` 与 `engine-mode`（重启生效） | `antixray.admin` |
| `/antixray reload` | 重载检测配置 | `antixray.admin` |
| `/antixray reset [玩家]` | 清空统计 | `antixray.admin` |

## 配置（`plugins/anti-xray/config.yml`）

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `detection.enabled` | `true` | 是否开启隐藏矿检测 |
| `detection.window-minutes` | `10` | 统计窗口（分钟） |
| `detection.min-hidden-ores` | `20` | 窗口内至少多少个隐藏矿才预警 |
| `detection.min-hidden-ratio` | `0.75` | 隐藏矿占矿石挖掘总数的比例阈值 |
| `detection.alert-cooldown-minutes` | `10` | 同一玩家两次预警的最小间隔 |
| `paper.engine-mode` | `1` | `/antixray apply` 写入的 Paper engine-mode（1 或 2） |

## 判定说明

- 矿石 = 材质名以 `_ORE` 结尾，或远古残骸（`ANCIENT_DEBRIS`）。
- 隐藏矿 = 挖掘时上下左右前后六个相邻方块都不是空气、水或岩浆，即玩家不是从洞穴里直接看到它。
- 创造/旁观模式下的挖掘不计入统计。

## 常用命令

```powershell
.\gradlew :plugins:anti-xray:build
python scripts/deploy_paper.py --plugin anti-xray
```
