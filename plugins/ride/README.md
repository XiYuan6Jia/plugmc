# Ride

玩家骑乘：把自己挂到另一名玩家身上（原版乘客位，位置由服务端跟随，不做传送），
**暂时只开放给 OP**（`ride.use` 默认 `op`）。

## 命令

| 命令 | 作用 | 权限 |
| --- | --- | --- |
| `/ride <玩家>` | 骑到该玩家身上 | `ride.use`（默认 OP） |
| `/ride off` | 下来 | `ride.use` |
| `/ride info` | 查看自己骑着谁 / 谁骑着自己 / 冷却 | `ride.use` |
| `/ride list` | 列出当前可骑乘的在线玩家 | `ride.use` |
| `/ride force <玩家>` | 管理员把某人（及其身上的玩家）放下来 | `ride.admin` |
| `/ride reload` | 重载配置 | `ride.admin` |

别名：`/carry`、`/mount`。

## 行为

- 骑手潜行即可下车（可用 `allow-sneak-dismount: false` 关闭），也可以 `/ride off`；
- 骑乘中双方**不能互相伤害**（`deny-mutual-damage`）；
- 受伤、死亡、传送、换世界、进入载具、下线都会自动下车（各项可单独关闭）；
- 会被拒绝的情况：骑自己、跨世界、目标在载具/睡觉/死亡、目标有 `ride.immune` 权限、
  形成骑乘循环、冷却未结束；
- 冷却默认 5 秒（`cooldown-seconds`）；
- 可选 `max-carrier-speed`：坐骑速度超过阈值（格/秒）时把骑手甩下来，防止用骑乘穿墙/超速。

## 配置（`plugins/ride/config.yml`）

| 键 | 说明 |
| --- | --- |
| `enabled` | 总开关 |
| `cooldown-seconds` | 两次骑乘之间的冷却，默认 5 |
| `max-carrier-speed` | 坐骑速度上限（格/秒），`0` = 不限制 |
| `dismount-on-damage` / `-death` / `-teleport` / `-world-change` | 各类自动下车 |
| `deny-mutual-damage` | 骑乘中禁止互相伤害，默认 `true` |
| `allow-sneak-dismount` | 允许潜行下车，默认 `true` |

## 权限

| 权限 | 默认 | 说明 |
| --- | --- | --- |
| `ride.use` | `op` | 使用骑乘（**暂时只开放给 OP**） |
| `ride.admin` | `op` | 强制下车、重载配置 |
| `ride.immune` | `false` | 不能被其他人骑乘 |

## 实现要点

- `carrier.addPassenger(rider)` 由服务端维护乘客位置，插件只记录 `rider -> carrier` 关系；
- `EntityDismountEvent` 兜底清理被动下车（潜行、其它插件强拆载具）后的残留记录；
- 所有操作在主线程执行，不涉及异步与数据持久化。
