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
| 邮箱 | `/mail` |
| 金币 HUD | 切换右侧余额侧栏 |
| 查看余额 | `/money` |

经济按钮通过 `menu.items.<slot>.action: player_command` 调用对应命令；权限分别使用
`economy.shop.use`、`economy.market.use`、`economy.auction.use`、`economy.order.use`、
`economy.trade.use`、`economy.mail.use`。

### 配置升级

`config.yml` 顶部带 `config-version`。升级插件时如果版本落后，插件会把旧配置备份为
`config-v<旧版本>.backup.yml`，然后把菜单布局更新为当前默认值；`hud` 段的自定义设置保留。

金币 HUD 默认开启，在屏幕右侧记分板侧栏显示余额；玩家的开关状态写入 Minecraft 玩家数据，重连后仍保留。HUD 使用玩家专属记分板侧栏，关闭时会恢复进入 HUD 前的记分板；如果同时使用其他会修改记分板的插件，需要协调侧栏显示。

菜单中的「插件列表」会列出当前已启用的插件、插件描述和 `plugin.yml` 声明的命令用法，超过一页可翻页。

## 配置与扩展

编辑 `plugins/server-menu/config.yml` 后执行 `/menu reload`。菜单支持 1 到 6 行，按钮位置从 0 开始。每个按钮可配置材质、名称、描述、可选权限、动作和值：

| 动作 | 作用 |
| --- | --- |
| `balance` | 向玩家显示当前余额 |
| `toggle_hud` | 切换该玩家的金币 HUD |
| `message` | 向玩家发送配置的文字 |
| `player_command` | 以玩家身份执行命令 |
| `console_command` | 以控制台身份执行命令 |
| `plugin_list` | 显示已启用插件列表及其命令用法 |

名称、描述和文字支持 `&` 颜色代码，以及 `{player}`、`{balance}`、`{hud_status}` 占位符。命令动作支持相同占位符；控制台命令只应由可信服务器管理员配置。

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
