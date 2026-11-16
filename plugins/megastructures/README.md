# megastructures — 星际巨构

给服务器加一个「巨构维度」：独立虚空维度 `plugmc:mega`，高度 **2048 格**（Y -512 ~ 1535），
里面漂浮着五座由插件程序生成的超级巨构。高度由 [`datapacks/plugmc-mega`](../../datapacks/plugmc-mega)
里的自定义维度类型提供，突破了原版 384 格的限制；巨构方块由区块 populator 在生成时补齐，不需要存档或结构模板。

## 巨构一览

| 巨构 | 位置 | 规模 |
| --- | --- | --- |
| 轨道环 | 圆心 `(0, 417, 0)` | 半径 380，桥面宽 19 格，顶部 Y=419；8 根 9×9 支撑柱垂到 Y=-400；出生点在环上 |
| 核心尖塔 | 圆心 `(0, 0)` | 半径 24，从 Y=0 升到 Y=1200；玻璃外壳 + 深板岩肋条 + 铜环带，中心贯穿发光能量核；Y=1150 观景台，Y=1213 钻石尖冠 |
| 戴森球 | `(-1600, 700, 0)` | 半径 180 的空心球壳，铜制肋条 + 染色玻璃；内部悬浮发光恒星与能量环，东侧半径 40 入口，外接栈桥和观景台 |
| 太空电梯 | `(1600, 0)` | 41×41 基座（Y=60），11×11 玻璃井道升到 Y=1400，每 160 格一层悬挑平台；顶端 31×31 平台 + 钻石晶体（Y=1406） |
| 星际之门 | `(0, 800, -1600)` | 竖立圆环，半径 220、管径 24；外层抛光黑石 + 海晶灯带，中层铜，内层发光；环内两道能量细环，下方 81×81 启航平台 |

## 观景点与实体交互

每个观景点都有：

- 一个**隐形 Interaction 实体**（右键命中框）；
- 上方一块**景观名称悬浮字**（如「核心尖塔观景台 · 右键打开巨构传送」）；
- 一个对应景观的 **ItemDisplay 图标**。

右键交互实体（或悬浮图标所在的命中框）会打开**巨构观景传送列表**，列表里每个按钮都带景观名称、
介绍和坐标，点击直接传送。`/mega menu` 也能打开同一个列表；服务器菜单「星际巨构」子页同样列出这六处景观。

| ID | 名称 | 坐标 |
| --- | --- | --- |
| `ring` | 轨道环（出生点） | `0.5, 420, 380.5` |
| `spire` | 核心尖塔观景台 | `0.5, 1151, 35.5` |
| `dyson` | 戴森球观景台 | `-1375.5, 700, 0.5` |
| `elevator` | 太空电梯基座 | `1618.5, 61, 0.5` |
| `top` | 太空电梯顶端 | `1600.5, 1402, 12.5` |
| `gate` | 星际之门 | `0.5, 61, -1560.5` |

## 命令

| 命令 | 说明 | 权限 |
| --- | --- | --- |
| `/mega` | 传送到出生点（轨道环） | `mega.use` |
| `/mega menu` | 打开观景传送列表界面 | `mega.use` |
| `/mega list` | 聊天栏列出全部观景点（可点击传送） | `mega.use` |
| `/mega tp <id>` | 传送到指定观景点，也可直接 `/mega <id>` | `mega.use` |
| `/mega back` | 回到进入巨构前的位置（记录在 `returns.yml`，重启后仍有效） | `mega.use` |
| `/mega info` | 查看维度、高度范围、出生点、在线人数与巨构规模 | `mega.use` |
| `/mega check` | 结构自检（逐点采样实际方块，首次读取会生成对应区块） | `mega.admin` |
| `/mega reload` | 重载配置并重新应用世界规则 | `mega.admin` |

## 权限

| 权限 | 默认 | 说明 |
| --- | --- | --- |
| `mega.use` | 所有玩家 | `/mega`、观景传送、返回、传送列表 |
| `mega.fly` | 所有玩家 | 在巨构维度里自动获得飞行能力 |
| `mega.build` | OP | 在巨构维度里放置/破坏方块 |
| `mega.admin` | OP | `/mega check`、`/mega reload` |

## 配置（`plugins/megastructures/config.yml`）

| 键 | 说明 |
| --- | --- |
| `world.key` | 数据包维度键，默认 `plugmc:mega` |
| `world.border-size` | 世界边界直径，默认 `3000` |
| `world.spawn.*` | 出生点坐标与朝向 |
| `teleport.join-auto` | 进服时自动送进巨构，默认 `false` |
| `protect.deny-build` | 禁止非 `mega.build` 玩家建造，默认 `true` |
| `protect.deny-pvp` | 禁止玩家互相伤害，默认 `true` |
| `protect.deny-mob-spawn` | 禁止刷怪（插件自己的观景实体不受影响），默认 `true` |
| `protect.no-fall-damage` | 关闭摔落伤害，默认 `true` |
| `protect.rescue-void` | 掉出世界底部时拉回出生点，默认 `true` |
| `protect.void-y` | 触发救援的 Y 值，默认 `-460` |
| `protect.respawn-in-mega` | 在巨构维度死亡后回出生点重生，默认 `true` |
| `comfort.flight` | 在巨构维度里自动允许飞行，默认 `true` |
| `comfort.night-vision` | 在巨构维度里常驻夜视，默认 `true` |

世界规则固定为：难度 `NORMAL`、时间 `18000`（午夜、不流逝）、无天气、禁刷怪、禁火焰/生物破坏、
保留物品栏、关闭摔落伤害与 PvP、关闭随机刻。

## 高度限制如何突破

- [`datapacks/plugmc-mega`](../../datapacks/plugmc-mega) 定义自定义维度类型 `plugmc:mega`，
  `min_y=-512`、`height=2048`、`logical_height=2048`，并使用 `minecraft:flat` 空层生成器；
- 插件启动时通过 `Bukkit.getWorld(NamespacedKey("plugmc","mega"))` 接入这个维度；
- 数据包维度由 Paper 在插件 `onEnable` 之前加载，出生区区块可能已经生成。插件会记录当时的出生点，
  把出生区 11×11 区块强制补绘一遍；之后新生成的区块由 `MegaPopulator` 负责补上巨构方块。
- 所有方块写入使用 `Block#setBlockData(data, false)`，关闭物理与连锁邻居更新，避免几十万方块触发
  “Too many chained neighbor updates” 或看门狗超时。

## 实现要点

- `MegaPopulator extends BlockPopulator`：在数据包维度的新区块生成后，把落在该区块里的巨构部分写进去。
- 每座巨构实现 `MegaStructure`，只做纯坐标数学计算，**无状态、线程安全**；区块按 `intersects()`
  判断是否落在本区块，跨区块的巨构由相邻区块各自补齐。
- `/mega check` 的采样点与巨构常量共用同一套定义，构建时的单元测试会逐点验证生成结果，
  并检查所有观景点/实体交互点脚下有方块、站位和头顶是空气。
- 观景实体全部写入 PersistentDataContainer：`megastructures:role` 区分命中框/悬浮字/图标，
  `megastructures:viewpoint` 记录景观 ID；区块加载时自动补建，重复时自动清理。
- 飞行与夜视只撤销插件自己授予的状态：玩家原本就能飞或本来带夜视时不会记录、不会回收。

## 构建与部署

高度维度来自数据包，必须**先部署数据包再重启**：

```powershell
.\gradlew :plugins:megastructures:test
.\gradlew :plugins:megastructures:build
.\gradlew :datapacks:zip-plugmc-mega
python scripts/deploy_datapack.py --pack plugmc-mega
python scripts/deploy_paper.py --plugin megastructures
```

> 首次部署后服务端会自动创建 `plugmc:mega` 维度。日志出现
> `已接入数据包维度 plugmc:mega（高度 -512 ~ 1535）`、`出生区巨构补绘完成` 与
> `星际巨构 已启用（维度 plugmc:mega，观景点 6 个）` 即表示成功；
> 控制台执行 `mega check` 应全部为 `✔`。
>
> 如果只装插件不装数据包，插件会拒绝启用并在日志提示安装 `datapacks/plugmc-mega`，
> 不会退回 384 格的裁切世界。
