# Cosmetic（装备外观）

把**任意物品**挂到玩家身上显示：头顶、肩侧、背后、腰间、环绕五个槽位。
**不改客户端、不用资源包**——外观由服务端的 `ItemDisplay` 实体渲染，玩家用原版客户端就能看到。

## 命令

| 命令 | 作用 | 权限 |
| --- | --- | --- |
| `/cosmetic` | 打开装备外观界面 | `cosmetic.use` |
| `/cosmetic gui [玩家]` | 为某人打开界面（控制台用） | `cosmetic.admin` |
| `/cosmetic wear <槽位> [玩家]` | 把主手物品放进槽位 | `cosmetic.use` |
| `/cosmetic unwear <槽位> [玩家]` | 取下槽位物品，还回背包 | `cosmetic.use` |
| `/cosmetic clear [玩家]` | 清空全部槽位 | 自己 / `cosmetic.admin` |
| `/cosmetic toggle [玩家]` | 隐藏或显示装饰（不动属性） | 自己 / `cosmetic.admin` |
| `/cosmetic slots` | 列出可用槽位 | `cosmetic.use` |
| `/cosmetic debug` | 诊断：槽位数、活动实体数、透传修饰符数 | `cosmetic.admin` |
| `/cosmetic reload` | 重载配置 | `cosmetic.admin` |

别名：`/cos`、`/zhuangbei`。

`wear` / `unwear` / `clear` / `toggle` / `gui` 都带**可选玩家参数**，所以运维可以从控制台直接给某个玩家挂外观：
`cosmetic wear head GuiProbe`。不带参数时作用于命令执行者自己。

## 槽位

| 槽位 | 默认名称 | 位置（相对玩家，单位格） | 默认缩放 | 变换 |
| --- | --- | --- | --- | --- |
| `head` | 头顶 | `[0.0, 2.15, 0.0]` | 0.55 | `GROUND` |
| `shoulder` | 肩侧 | `[-0.05, 1.35, 0.36]` | 0.42 | `GROUND` |
| `back` | 背后 | `[-0.32, 1.05, 0.0]` | 0.62 | `FIXED` |
| `waist` | 腰间 | `[0.0, 0.95, 0.32]` | 0.38 | `GROUND` |
| `orbit` | 环绕 | `[0.0, 1.20, 0.0]` | 0.32 | `GROUND`，绕圈半径 0.85 |

`offset` 的三个分量依次是**前方 / 上方 / 右方**；`head` 的 `pitch-follow: true` 让头顶物品随视角俯仰微调，
`orbit` 的 `orbit: true` + `spin: 90` 让物品绕玩家转圈。

## 行为

- 放入时**从主手扣 1 个**（不是整叠）；取下时物品原样还回背包，背包满了掉在脚下；
- 强制存的是原始 `ItemStack`，渲染用的是 `clone()`，所以外观不会改变原物品的任何数据；
- 被当外观的物品**不能被吃、不能放置、不能交互**（`block-use.*`，防止拿装饰当消耗品使）；
- 属性透传默认开：槽位里的物品属性（攻击力、护甲、韧性等）加成到玩家身上，
  `attributes.blacklist` 默认屏蔽 `MAX_HEALTH`（避免多槽叠加把血上限搞乱）；
- `attributes.stack: false` 时同一种属性只取绝对值最大的那个，不做叠加；
- 隐藏（`toggle`）只隐藏实体，属性照常生效；
- 玩家登录、重生、换世界、传送、下线都会重建或清理装饰实体；
- 数据落在 `plugins/cosmetic/wardrobe.json`（Base64 编码的 `ItemStack.serializeAsBytes()`，带回环校验）。

## 配置（`plugins/cosmetic/config.yml`）

| 键 | 说明 |
| --- | --- |
| `enabled` | 总开关 |
| `sync.interval-ticks` | 位置同步周期，默认 1 tick |
| `sync.move-threshold` | 位移超过多少格才更新，默认 0.05 |
| `sync.teleport-duration` | `setTeleportDuration`，越大越省带宽但越飘 |
| `view.range` | 装饰实体的可见距离，默认 48 |
| `view.self-only` | 只有自己看得见（默认 `false`） |
| `slots.<id>.*` | 槽位的名称、偏移、缩放、变换、是否跟随俯仰、是否绕圈、权限 |
| `attributes.enabled` | 是否把槽位物品的属性透传给玩家 |
| `attributes.whitelist` / `blacklist` | 属性白/黑名单，黑名单默认 `[MAX_HEALTH]` |
| `attributes.stack` | 同属性叠加（`true`）还是取最大（`false`） |
| `block-use.consume` / `block-place` / `interact` | 拦掉装饰物品的吃/放/交互 |
| `gui.title` / `rows` / `slot-index` / `info-index` / `clear-index` / `close-index` | 界面布局 |

## 权限

| 权限 | 默认 | 说明 |
| --- | --- | --- |
| `cosmetic.use` | `true` | 使用界面、穿戴、取下、隐藏 |
| `cosmetic.admin` | `op` | 重载、诊断、操作他人 |

## 实现要点

- 外观用原版 `ItemDisplay` 实体渲染，`setPersistent(false)` + scoreboard tag `plugmc_cosmetic`，
  启动时 `sweepOrphans()` 清扫上次崩溃遗留的孤儿实体；
- **不做玩家乘客**：26.3 的 `Entity.canAddPassenger` 是 `return getPassengers().isEmpty()`，
  玩家身上只允许一个直接乘客，而 `ride` 插件已经占用了这个位置（`carrier.addPassenger(rider)`）。
  所以外观实体一律独立生成、由服务端每 tick 计算位置并 `teleport` 过去，客户端靠
  `setTeleportDuration` 做插值，看不出卡顿；
- 朝向用 `LivingEntity.getBodyYaw()` 而不是 `getLocation().getYaw()`，否则转头时躯干朝向不准；
- 属性透传用 `AttributeInstance.addTransientModifier(...)`（不写 NBT），
  每个修饰符的 key 是 `cos_<槽位>_<原属性名>`，更换前先按记录的 (属性, key) 逐个 `removeModifier`；
- 26.3 的物品属性在 `DataComponentTypes.ATTRIBUTE_MODIFIERS` 里，`ItemMeta.getAttributeModifiers()`
  对钻石剑这类物品返回空，所以取属性有三条路径：数据组件 → `ItemType` 默认值 → `ItemMeta` 兜底。
