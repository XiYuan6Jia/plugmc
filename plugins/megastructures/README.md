# megastructures — 星际巨构

给服务器加一个「巨构维度」：独立虚空世界 `mega`，里面漂浮着五座由插件用代码生成的超级巨构。
不依赖任何素材文件、结构模板或存档，区块第一次生成时按数学几何直接摆出方块；玩家 `/mega` 进入，
`/mega tp <id>` 在巨构之间传送，`/mega back` 回原位置。

## 巨构一览

| 巨构 | 位置 | 规模 |
| --- | --- | --- |
| 轨道环 | 圆心 `(0, 170, 0)` | 半径 240，桥面宽 15 格，四层结构，内/外圈玻璃栏杆，8 根 9×9 支撑柱垂到 Y=60 |
| 核心尖塔 | 圆心 `(0, 0)` | 半径 18，从 Y=54 升到 Y=299；玻璃外壳 + 深板岩肋条 + 铜环带，中心贯穿发光能量核；Y=286 观景台，Y=312 钻石尖冠 |
| 戴森球 | `(-800, 180, 0)` | 半径 80 的空心球壳，铜制肋条 + 染色玻璃；内部悬浮发光恒星与能量环，东侧有半径 19 的入口，外接栈桥和观景台 |
| 太空电梯 | `(800, 0)` | 41×41 基座（Y=60），11×11 玻璃井道升到 Y=299，每 48 格一层悬挑平台，顶端 31×31 平台 + 钻石晶体 |
| 星际之门 | `(0, 175, -800)` | 竖立的圆环，半径 100、管径 14；外层抛光黑石 + 海晶灯带，中层铜，内层发光；环内两道能量细环，下方 61×61 启航平台 |

轨道环的桥面就是默认出生点：`(0.5, 172, 240.5)`，面向圆心的核心尖塔。

## 观景点

| ID | 名称 | 坐标 |
| --- | --- | --- |
| `ring` | 轨道环（出生点） | `0.5, 172, 240.5` |
| `spire` | 核心尖塔观景台 | `0.5, 287, 25.5` |
| `dyson` | 戴森球观景台 | `-675.5, 180, 0.5` |
| `elevator` | 太空电梯基座 | `818.5, 61, 0.5` |
| `top` | 太空电梯顶端 | `800.5, 301, 12.5` |
| `gate` | 星际之门 | `0.5, 61, -770.5` |

## 命令

| 命令 | 说明 | 权限 |
| --- | --- | --- |
| `/mega` | 传送到出生点（轨道环） | `mega.use` |
| `/mega list` | 列出全部观景点（可点击传送） | `mega.use` |
| `/mega tp <id>` | 传送到指定观景点，也可直接 `/mega <id>` | `mega.use` |
| `/mega back` | 回到进入巨构前的位置（记录在 `returns.yml`，重启后仍有效） | `mega.use` |
| `/mega info` | 查看维度、出生点、在线人数、巨构规模 | `mega.use` |
| `/mega check` | 结构自检（逐点采样实际方块，首次读取会生成对应区块） | `mega.admin` |
| `/mega reload` | 重载配置并重新应用世界规则 | `mega.admin` |

## 权限

| 权限 | 默认 | 说明 |
| --- | --- | --- |
| `mega.use` | 所有玩家 | `/mega`、观景点传送、返回 |
| `mega.fly` | 所有玩家 | 在巨构维度里自动获得飞行能力 |
| `mega.build` | OP | 在巨构维度里放置/破坏方块 |
| `mega.admin` | OP | `/mega check`、`/mega reload` |

## 配置（`plugins/megastructures/config.yml`）

| 键 | 说明 |
| --- | --- |
| `world.name` | 世界（维度）名，默认 `mega`，创建后不建议改名 |
| `world.border-size` | 世界边界直径，默认 `2000` |
| `world.spawn.*` | 出生点坐标与朝向 |
| `teleport.join-auto` | 进服时自动送进巨构，默认 `false` |
| `protect.deny-build` | 禁止非 `mega.build` 玩家建造，默认 `true` |
| `protect.deny-pvp` | 禁止玩家互相伤害，默认 `true` |
| `protect.deny-mob-spawn` | 禁止刷怪，默认 `true` |
| `protect.no-fall-damage` | 关闭摔落伤害，默认 `true` |
| `protect.rescue-void` | 掉出世界底部时拉回出生点，默认 `true` |
| `protect.void-y` | 触发救援的 Y 值，默认 `-20` |
| `protect.respawn-in-mega` | 在巨构维度死亡后回出生点重生，默认 `true` |
| `comfort.flight` | 在巨构维度里自动允许飞行，默认 `true` |
| `comfort.night-vision` | 在巨构维度里常驻夜视，默认 `true` |

世界规则固定为：难度 `NORMAL`、时间 `18000`（午夜、不流逝）、无天气、禁刷怪、禁火焰/生物破坏、
保留物品栏、关闭摔落伤害与 PvP、关闭随机刻。

## 实现要点

- `MegaGenerator extends ChunkGenerator`：只在自己的 `generateSurface` 里写方块，关闭原版噪声/地表/洞穴/
  装饰/生物/结构生成；区块尽可能并行生成（`isParallelCapable() = true`）。
- 每座巨构实现 `MegaStructure`，只做纯坐标数学计算，**无状态、线程安全**；区块生成时按
  `intersects()` 判断是否落在本区块，再把自己的方块写进 `ChunkData`。
- 巨构只在区块**首次生成**时出现；已经生成过的区块不会因为改配置或重载而重建。需要重置时停服删除
  维度目录（Paper 子维度位于 `世界目录/dimensions/minecraft/mega/`）后重启。
- `/mega check` 的采样点与生成器共用同一套常量，构建时的单元测试会逐点验证生成结果，
  并检查所有观景点脚下有方块、站位和头顶是空气。
- 飞行与夜视只撤销插件自己授予的状态：玩家原本就能飞或本来带夜视时不会记录、不会回收；
  退出服务器/离开维度前会清理，避免能力被写进玩家数据。

## 构建与部署

```powershell
.\gradlew :plugins:megastructures:test
.\gradlew :plugins:megastructures:build
python scripts/deploy_paper.py --plugin megastructures
```

> 首次部署后服务端会自动创建 `mega` 维度。日志出现
> `Enabling megastructures` 与 `星际巨构 已启用（维度 mega，观景点 6 个）` 即表示成功；
> 控制台执行 `mega check` 应全部为 `✔`。
