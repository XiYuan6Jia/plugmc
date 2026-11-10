# Server Menu

测试服专用的可配置 Paper 功能菜单，使用原创界面和实现。依赖 `economy` 插件提供的 `EconomyService`。

## 玩家命令

| 命令 | 作用 |
| --- | --- |
| `/menu`、`/servermenu` | 打开服务器菜单 |
| `/moneyhud` | 开关右侧金币 HUD |

潜行时按副手交换键（默认 `Shift+F`）也可打开菜单。玩家入服提示仅发送给该玩家。

## 经济系统入口

默认菜单已联动经济插件：

| 按钮 | 命令 |
| --- | --- |
| 系统商店 | `/shop` |
| 玩家市场 | `/market`（界面内可上架、购买） |
| 拍卖行 | `/auction`（界面内可上拍、出价、一口价、撤拍） |
| 收购订单 | `/order`（界面内可发布、交付、取消） |
| 直接交易 | `/trade`（界面展示在线玩家头颅，点击申请/接受交易） |
| 申请传送 | `/tpa`（点击玩家头像发起传送申请，对方聊天栏一键接受） |
| 邮箱 | `/mail` |
| 每日签到 | `/daily`（按钮显示 `{daily_status}`） |
| 金币 HUD | 切换右侧余额侧栏 |
| 查看余额 | `/money`（显示 `{frozen_status}` 账户状态） |
| 余额排行 | `/baltop` |
| 经济管理 | 统计、自检、演练、财政注入/发放、冻结/解冻、流水查询（`server-menu.admin`，默认 OP） |
| 主城大厅 | 打开大厅子页：进入大厅、返回原位置、主城信息，管理员另有设置出生点/重建大厅/结构自检（`hub.use`，管理员按钮需 `hub.admin`） |

### 主城大厅子页

点击「主城大厅」进入 6 行子页（由 `hub` 插件提供具体功能，菜单只负责按钮）：

| 槽位 | 内容 | 操作 |
| --- | --- | --- |
| 20 | 进入主城大厅 | `/hub` |
| 22 | 返回原位置 | `/hub back` |
| 24 | 主城信息 | `/hub info` |
| 29 | 设置出生点（`hub.admin`） | `/hub setspawn` |
| 31 | 重建大厅（`hub.admin`） | `/hub build` |
| 33 | 结构自检（`hub.admin`） | `/hub check` |
| 45 / 49 | 返回主菜单 / 关闭 | — |

`hub` 插件未加载时子页会显示「主城插件未加载」。

经济按钮通过 `menu.items.<slot>.action: player_command` 调用对应命令；权限分别使用
`economy.shop.use`、`economy.market.use`、`economy.auction.use`、`economy.order.use`、
`economy.trade.use`、`economy.mail.use`、`economy.reward.daily`。

### 经济管理面板

点击「经济管理」进入 6 行管理界面（`server-menu.admin`）：

| 槽位 | 内容 | 操作 |
| --- | --- | --- |
| 4 | 经济统计 | 只读 |
| 11 | 财政账户余额 | 只读 |
| 13 | 财政注入 | 点击后聊天栏输入金额 |
| 15 | 财政发放 | 依次输入玩家名与金额 |
| 20 | 账本自检 | 只读 |
| 24 | 待处理事项 | 只读 |
| 29 / 33 | 冻结 / 解冻玩家 | 输入玩家名 |
| 31 | 资金演练 | 只读 |
| 37 | 查询流水 | 输入玩家名，输出最近账本流水 |
| 45 / 49 / 53 | 返回 / 关闭 / 重新检测 | 导航 |

输入类操作使用聊天栏提示（30 秒有效，输入 `cancel` 取消），执行完自动回到管理面板。

### 配置升级

`config.yml` 顶部带 `config-version`。升级插件时如果版本落后，插件会把旧配置备份为
`config-v<旧版本>.backup.yml`，然后把菜单布局更新为当前默认值；`hud` 段的自定义设置保留。

金币 HUD 默认开启，在屏幕右侧记分板侧栏显示余额；玩家的开关状态写入 Minecraft 玩家数据，重连后仍保留。HUD 使用玩家专属记分板侧栏，关闭时会恢复进入 HUD 前的记分板；如果同时使用其他会修改记分板的插件，需要协调侧栏显示。

菜单中的「插件列表」会列出当前已启用的插件、插件描述和 `plugin.yml` 声明的命令用法，超过一页可翻页。

## 配置与扩展

编辑 `plugins/server-menu/config.yml` 后执行 `/menu reload`。菜单支持 1 到 6 行，按钮位置从 0 开始。每个按钮可配置材质、名称、描述、可选权限、动作和值：

`menu.filler` 可给空槽位铺背景板，避免界面空洞：

```yaml
menu:
  rows: 6
  filler:
    material: BLACK_STAINED_GLASS_PANE
    name: " "
```

默认布局（v7）按功能分为四块：

| 区域 | 槽位 | 内容 |
| --- | --- | --- |
| 头部 | 4 | 服务器菜单，显示玩家、余额、签到、账户状态 |
| 我的 | 11 / 13 / 15 / 20 / 22 | 余额、签到、邮箱、金币 HUD、余额排行 |
| 交易 | 28 / 30 / 32 / 34 | 系统商店、玩家市场、拍卖行、收购订单 |
| 社交与信息 | 37 / 39 / 41 / 43 | 申请传送、直接交易、插件列表、服务器帮助 |
| 底部 | 48 / 50 | 经济管理（仅管理员）、关闭菜单 |

| 动作 | 作用 |
| --- | --- |
| `balance` | 向玩家显示当前余额 |
| `toggle_hud` | 切换该玩家的金币 HUD |
| `message` | 向玩家发送配置的文字 |
| `player_command` | 以玩家身份执行命令 |
| `console_command` | 以控制台身份执行命令 |
| `plugin_list` | 显示已启用插件列表及其命令用法 |
| `daily` | 调用 `/daily` 签到并刷新菜单 |
| `prompt_command` | 关闭菜单后让玩家在聊天栏输入，再把 `{input}` 代入 `value` 执行命令 |
| `economy_admin` | 打开经济管理面板 |
| `hub_panel` | 打开主城大厅子页 |
| `none` | 无操作（用于标题、装饰） |
| `close` | 关闭菜单 |

名称、描述和文字支持 `&` 颜色代码，以及 `{player}`、`{balance}`、`{hud_status}`、
`{daily_status}`、`{frozen_status}`、`{treasury}` 占位符。命令动作支持相同占位符；
控制台命令只应由可信服务器管理员配置。

`prompt_command` 示例（管理员财政注入）：

```yaml
"13":
  material: DIAMOND
  name: "&a财政注入"
  action: prompt_command
  value: "eco treasury fund {input} menu"
  permission: server-menu.admin
```

```yaml
menu:
  title: "&8服务器菜单"
  rows: 3
  items:
    "11":
      material: GOLD_INGOT
      name: "&6查看余额"
      lore:
        - "&7当前余额：&e{balance}"
      action: balance
    "15":
      material: CLOCK
      name: "&a金币 HUD：{hud_status}"
      lore:
        - "&7在屏幕右侧显示当前余额"
        - "&e点击切换"
      action: toggle_hud
    "22":
      material: BOOKSHELF
      name: "&b插件列表"
      lore:
        - "&7浏览已启用插件的功能和用法"
      action: plugin_list
    "24":
      material: COMPASS
      name: "&b返回大厅"
      action: player_command
      value: "spawn"
      permission: server-menu.spawn
hud:
  enabled-by-default: true
  title: "&6&l经济"
  balance-label: "&7余额"
```

服务器可按需为自定义按钮授予对应权限，并继续添加 `menu.items` 条目。
