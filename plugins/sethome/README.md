# sethome — 家园与死亡点传送

给玩家提供 `sethome` / `home` / `delhome` / `homes`，以及死亡后一键传回死亡地点。

## 命令

| 命令 | 说明 |
| --- | --- |
| `/sethome [名称]` | 在当前位置设置家园，默认名称 `home`；同名会覆盖 |
| `/home [名称]` | 传送回家园，默认 `home` |
| `/delhome <名称>` | 删除家园 |
| `/homes` | 列出自己的家园，可点击 `[传送]` / `[删除]` |
| `/deathpoint`（别名 `/dp`、`/deathback`） | 传送到上次死亡地点 |

传送默认有 **3 秒读秒**，期间移动或受伤会取消；成功后进入 **10 秒冷却**。
死亡时聊天栏会出现可点击的 `[点击传送回死亡地点]` 按钮。

## 配置（`plugins/sethome/config.yml`）

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `max-homes` | `3` | 默认家园数量上限 |
| `teleport-warmup-seconds` | `3` | 传送读秒，`0` 表示立即传送 |
| `teleport-cooldown-seconds` | `10` | 传送成功后的冷却 |
| `deathpoint-enabled` | `true` | 是否允许死亡点传送 |

家园数量上限可用权限 `sethome.max.<数字>` 单独提高（取最高的一个）。

## 权限

| 权限 | 默认 | 说明 |
| --- | --- | --- |
| `sethome.use` | 所有玩家 | 使用家园与死亡点传送 |
| `sethome.max.<n>` | 无 | 把该玩家的家园上限提高到 `n` |
| `sethome.admin` | OP | 预留管理权限 |

## 数据

家园保存在 `plugins/sethome/homes.yml`，按玩家 UUID 记录世界名与坐标；
世界名称找不到时（例如维度被删除）该家园会被跳过并在日志中提示。

## 常用命令

```powershell
.\gradlew :plugins:sethome:build
python scripts/deploy_paper.py --plugin sethome
```
