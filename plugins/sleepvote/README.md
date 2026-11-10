# sleepvote — 入睡半径

只有**半径内**的玩家计入睡眠投票，达到配置比例后跳过夜晚。

## 工作方式

1. 玩家上床后，以该玩家为圆心、`radius` 为半径统计同世界内的玩家（旁观者不计）。
2. 需要入睡人数 = `ceil(半径内玩家数 × percentage%)`，至少 1 人。
3. 达到人数后：时间设为清晨、按需停雨停雷，并唤醒所有正在睡觉的玩家。
4. 未达到时，半径内玩家会收到 ActionBar 进度提示（`{sleeping}/{required}`）。

为避免原版全局睡眠规则抢先跳过夜晚，插件默认把每个世界的
`playersSleepingPercentage` 设为 `101`（永不自动跳过），由本插件接管。

## 配置（`plugins/sleepvote/config.yml`）

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `radius` | `100` | 统计半径（格） |
| `percentage` | `50` | 半径内需要入睡的比例（1-100） |
| `min-players` | `1` | 半径内至少多少名玩家才进行投票 |
| `disable-vanilla-sleep` | `true` | 关闭原版全局睡眠跳过 |
| `skip-weather` | `true` | 跳过夜晚时同时停雨停雷 |
| `messages.progress` | — | 进度提示，支持 `{sleeping}`、`{required}`、`{eligible}`、`{radius}` |
| `messages.skipped` | — | 跳过夜晚时的提示 |
| `messages.alone` | — | 半径内人数不足时的提示 |

## 命令

| 命令 | 说明 | 权限 |
| --- | --- | --- |
| `/sleepvote` / `/sleepvote status` | 查看当前半径、比例与周围睡眠人数 | `sleepvote.use` |
| `/sleepvote reload` | 重载配置 | `sleepvote.admin` |

## 常用命令

```powershell
.\gradlew :plugins:sleepvote:build
python scripts/deploy_paper.py --plugin sleepvote
```
