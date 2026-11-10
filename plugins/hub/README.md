# hub — 主城大厅

给服务器加一个「主城维度」：独立虚空世界 + 由插件用代码生成的大厅建筑，玩家 `/hub` 进入、
`/hub back` 回原位置。整个大厅不依赖任何素材文件或存档，首次启动自动生成，之后按标记方块判断是否已生成。

## 功能

- **独立虚空维度**：首次启动用 `WorldCreator` 创建 `hub` 世界（虚空区块生成器，不生成任何地形）。
  Paper 会把它作为主世界的子维度保存到 `世界目录/dimensions/minecraft/hub/`，跟着主世界一起备份。
- **大厅建筑**（半径默认 24，即 48×48）：
  - 石英 + 抛光安山岩格纹地板，四周石砖包边
  - 四面石砖墙 + 石英柱 + 玻璃窗，四面各开一个 7 格宽大门
  - 石英屋顶，中央 13×13 玻璃天窗
  - 中央喷泉（石砖外圈 + 水源 + 石英柱 + 海晶灯），地板嵌海晶灯灯带
  - 四根内部石英柱，柱上灯笼照明
  - 两块悬浮文字（`TextDisplay`）：大厅标题与「/menu 打开菜单 · /hub back 返回」
- **世界规则**：和平难度、永昼（时间锁定 6000）、无天气、禁刷怪、禁火焰蔓延、禁生物破坏方块、
  主城内保留物品掉落（`keepInventory`）。
- **保护**：默认禁止在主城放置/破坏方块（`hub.build` 可放行）、禁止玩家互相伤害、
  禁止刷怪、摔落无伤害、掉出世界底部（Y < 0）自动拉回出生点。
- **重生**：在主城死亡时直接在主城出生点重生。
- **返回原位置**：进入主城前记录玩家位置（`plugins/hub/returns.yml`，重启后依然有效），
  `/hub back` 或聊天栏 `[返回原位置]` 按钮一键回去；`/hub`、菜单、其它插件传送进主城都会记录，
  记录缺失时退回主世界出生点。

## 命令

| 命令 | 说明 | 权限 |
| --- | --- | --- |
| `/hub` | 进入主城（已在主城则回出生点），别名 `/spawn`、`/lobby`、`/maincity` | `hub.use` |
| `/hub back` | 回到进入主城前的位置 | `hub.use` |
| `/hub info` | 查看维度名、出生点、在线人数、大厅与跑酷状态 | `hub.use` |
| `/hub check` | 结构自检（大厅 + 跑酷关键方块） | `hub.admin` |
| `/hub parkour` | 传送到跑酷起点并开始计时 | `hub.use` |
| `/hub parkour quit` | 退出跑酷，回到大厅出生点 | `hub.use` |
| `/hub parkour top` / `best` | 跑酷排行榜 / 自己的最佳成绩 | `hub.use` |
| `/hub setspawn` | 把当前位置设为主城出生点（同时写回配置） | `hub.admin` |
| `/hub build` | 强制清空并重建大厅与跑酷赛道 | `hub.admin` |
| `/hub reload` | 重载配置并重新应用世界规则（改世界名需重启） | `hub.admin` |

服务器菜单（`server-menu`）第三区有「主城大厅」按钮，进入子页后可进入大厅、返回原位置、
查看信息或开始跑酷。

## 跑酷赛道

- 位置：大厅**东门外**，从东门出来沿一条 3 格宽的连接走廊往东走即到起点台。
- 结构：起点 5×5 台（中心金色方块）→ 20 段 3×3 跳台（间隔 4 格、逐步升高、左右交错）→ 终点 7×7 台（中心钻石块）。
- 玩法：
  - 踩**起点金色方块**开始计时；`/hub parkour` 也会直接传送到起点并开始。
  - 每 5 段有一次**检查点**（更大的平台 + 金色方块），踩到即记录进度并显示当前用时。
  - 踩**终点钻石块**结束计时：必须踩满全部 3 个检查点才算成绩，否则提示还差几个。
  - 掉下赛道（Y 低于地板 8 格）自动送回最近检查点，计时继续。
  - 成绩写入 `plugins/hub/parkour.yml`，刷新个人纪录时全服播报（`parkour.announce-records`）。
- 悬浮字：起点「跑酷起点 · /hub parkour」、终点「跑酷终点」。
- 自检：`/hub check` 会列出起点、检查点 1–3、终点与关键平台的实际方块。

## 配置（`plugins/hub/config.yml`）

| 键 | 说明 |
| --- | --- |
| `world.name` | 世界（维度）名，默认 `hub`，创建后不建议改名 |
| `world.floor-y` | 地板所在 Y 层，默认 `64` |
| `world.radius` | 大厅半径（宽度 = 2×radius+1），默认 `24`，范围 8–48 |
| `world.auto-build` | 启动时大厅不存在则自动生成 |
| `spawn.*` | 出生点坐标与朝向（`/hub setspawn` 会自动写回） |
| `text.title` / `text.subtitle` | 大厅里两块悬浮文字，支持 `&` 颜色码 |
| `teleport.join-auto` | 进服自动送进主城，默认 `false` |
| `teleport.respawn-in-hub` | 主城内死亡原地重生，默认 `true` |
| `teleport.rescue-void` | 掉出世界底部拉回出生点，默认 `true` |
| `protect.deny-build` | 禁止非 `hub.build` 玩家建造，默认 `true` |
| `protect.deny-pvp` | 主城内禁止玩家互相伤害，默认 `true` |
| `protect.deny-mob-spawn` | 主城内禁止刷怪，默认 `true` |
| `protect.no-fall-damage` | 主城内无摔落伤害，默认 `true` |
| `parkour.enabled` | 是否生成并启用跑酷赛道，默认 `true` |
| `parkour.announce-records` | 有人刷新纪录时全服播报，默认 `true` |

## 权限

| 权限 | 默认 | 说明 |
| --- | --- | --- |
| `hub.use` | 所有玩家 | 使用 `/hub`、`/hub back`、`/hub info` |
| `hub.build` | OP | 在主城放置/破坏方块 |
| `hub.admin` | OP | `check` / `setspawn` / `build` / `reload` |

## 实现要点

- `VoidChunkGenerator`：关闭噪声/地表/洞穴/装饰/生物/结构生成，生物群系固定平原。
- `HubWorld`：加载或创建世界、设置世界规则与出生点、按标记方块决定是否生成大厅。
- `HubLobby`：纯坐标摆放方块（`applyPhysics=false`），并生成两块 `TextDisplay`；
  喷泉顶端的海晶灯同时作为「已生成」标记，`/hub build` 会先清空再重建并清理旧文字实体。
- `HubListener`：建造/PvP/刷怪/掉落物保护、虚空救援、主城重生、可选进服自动传送。
- `HubCommand`：`/hub` 子命令与 Tab 补全。

## 常用操作

```powershell
.\gradlew :plugins:hub:build
python scripts/deploy_paper.py --plugin hub      # 部署 jar 与 config.yml
```

> 首次部署后服务端会自动创建 `hub` 维度并生成大厅；日志出现
> `主城大厅 已启用（维度 hub，出生点 …）` 与 `主城大厅已生成，用时 N ms` 即表示成功。
