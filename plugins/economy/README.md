# Economy

Paper 经济插件。当前版本使用**复式记账账本**：玩家余额、税收、财政和托管账户统一记录在
`state.json`，每笔资金变更先追加 `journal.jsonl` 并刷盘，再应用到内存；定时写快照并保留备份。
旧版的 `accounts.yml` 会在首次启动时自动迁移。

## 命令

| 命令 | 作用 | 权限 |
| --- | --- | --- |
| `/money`、`/balance`、`/bal` | 查看自己的余额 | 所有玩家 |
| `/money <玩家>` | 查看其他玩家余额 | `economy.balance.others`（默认 OP） |
| `/pay <玩家> <金额>` | 向已加入过服务器的玩家转账 | 所有玩家 |
| `/shop` | 打开系统商店 GUI | `economy.shop.use`（默认所有玩家） |
| `/shop buy <商品> [数量]` | 购买商品 | `economy.shop.use` |
| `/shop sell [数量\|all]` | 把主手物品卖给商店 | `economy.shop.use` |
| `/shop price [商品]` | 查看手持或指定商品的买卖价 | `economy.shop.use` |
| `/shop search <关键词>` | 搜索商品并打开结果页 | `economy.shop.use` |
| `/shop reload` | 重载 `goods.yml` 与商店配置 | `economy.shop.admin` |
| `/market` | 打开玩家市场 GUI | `economy.market.use`（默认所有玩家） |
| `/market sell <单价> [数量\|all]` | 把手持物品挂到市场 | `economy.market.use` |
| `/market buy <编号> [数量]` | 购买市场挂单 | `economy.market.use` |
| `/market cancel <编号>` | 下架自己的挂单 | `economy.market.use` |
| `/market mine` | 查看自己的挂单 | `economy.market.use` |
| `/market search <关键词>` | 搜索市场挂单 | `economy.market.use` |
| `/market reload` | 重载市场配置 | `economy.market.admin` |
| `/mail` | 打开邮箱 GUI | `economy.mail.use`（默认所有玩家） |
| `/mail claim <id\|all>` | 领取邮件 | `economy.mail.use` |
| `/order` | 列出进行中的收购订单 | `economy.order.use`（默认所有玩家） |
| `/order create <单价> <数量\|all>` | 手持物品作为样本，托管资金发布收购订单 | `economy.order.use` |
| `/order fill <编号> [数量\|all]` | 用背包中的物品交付收购订单 | `economy.order.use` |
| `/order cancel <编号>` | 取消自己的收购订单，托管资金退回 | `economy.order.use` |
| `/order list [页]` / `/order mine` / `/order search <关键词>` | 浏览、查看自己的、搜索收购订单 | `economy.order.use` |
| `/order reload` | 重载收购订单配置 | `economy.order.admin` |
| `/eco give <玩家> <金额>` | 增加余额 | `economy.admin`（默认 OP） |
| `/eco take <玩家> <金额>` | 扣除余额 | `economy.admin` |
| `/eco set <玩家> <金额>` | 设置余额 | `economy.admin` |
| `/eco stats` | 查看玩家余额、铸币、税收、财政、托管与事务序号 | `economy.admin` |
| `/eco doctor` | 检查账本是否平衡、玩家/托管账户是否为负 | `economy.admin` |
| `/eco save` | 立即写快照并截断已应用的 journal | `economy.admin` |
| `/eco backup` | 立即保存并生成一份状态备份 | `economy.admin` |

金额使用十进制精确计算，不允许负数或超过配置精度的小数；转账在同一笔账本事务中完成。

## 配置

首次启动会生成 `plugins/economy/config.yml`：

```yaml
currency:
  name: 金币
  symbol: $
  decimal-places: 2
starting-balance: "100.00"
storage:
  snapshot-interval-seconds: 300
  backup-count: 14
shop:
  enabled: true
  daily-buy-limit: 256
  daily-sell-limit: 256
  allow-custom-items: false
market:
  enabled: true
  slots: 8
  duration-hours: 168
  listing-fee-rate: 0.01
  listing-fee-min: "5.00"
  tax-rate: 0.03
mail:
  capacity: 50
  expire-days: 30
orders:
  enabled: true
  slots: 8
  duration-hours: 168
  listing-fee-rate: 0.01
  listing-fee-min: "2.00"
  tax-rate: 0.03
```

`decimal-places` 范围为 0 到 6。已有账户数据时不能直接修改小数位，否则插件会拒绝启动；
`starting-balance` 只影响新创建的账户。

### 商品表 `goods.yml`

首次启动会生成 `plugins/economy/goods.yml`。分类与商品都可在文件中调整，改完执行
`/shop reload` 生效，无需重启。

```yaml
categories:
  building:
    display: "建筑"
    icon: BRICKS
    items: [stone, oak_log, glass]

items:
  stone:
    material: STONE
    buy: "2.00"
    sell: "1.00"
    daily-buy: 256
    daily-sell: 256
    aliases: [石头]
```

- `buy` 是玩家购买价，`sell` 是商店回收价，必须 `buy > sell`。
- `daily-buy` / `daily-sell` 为单玩家每日限额，`0` 表示不限；未写时使用 `config.yml` 的全局默认值。
- `match: material`（默认）只匹配物品类型；`match: exact` 还会匹配名称/Lore 等组件。
- `allow-custom-items: false` 时，带自定义名称、Lore 或附魔的物品不能按普通材料回收。
- 商品 ID、材质名、别名都可用于 `/shop buy`、`/shop price` 和 Tab 补全。

### 玩家市场与邮箱

- 上架：`/market sell <单价> [数量|all]` 使用主手物品；默认上架整组。
- 上架费从卖家余额扣除，不退还；成交税从卖家所得中扣除。
- 市场挂单默认 7 天过期；过期或下架的商品进入邮箱。
- 买家购买后商品进入邮箱，背包有空间时自动领取；邮箱满时购买会被拒绝。
- 邮箱默认容量 50、30 天过期；`/mail` 打开分页邮件列表，支持单封领取和全部领取。
- 市场挂单、邮箱内容与每日限额都保存在 `state.json`，正常重启不会丢失。

### 界面导航

商店、市场、邮箱的每个页面都有「返回上一级」：子页面回到上一级页面，顶层页面回到服务器菜单
（未安装 `server-menu` 时等同关闭界面）；「关闭」按钮始终可直接退出。

### 收购订单

- 发布：主手拿一件目标物品作为样本，执行 `/order create <单价> <数量|all>`。
  托管金额 = 单价 × 数量，另收挂单费；资金立即从买家余额转入 `escrow:ORDER`。
- 交付：卖方用 `/order fill <编号> [数量|all]`，系统从背包按顺序扣除匹配物品
  （与原版 `isSimilar` 一致），买家托管资金即时结算给卖方，成交税从卖方所得扣除。
- 取消或过期时，未成交部分的托管资金原额退回买家余额；订单不会产生负数账户。
- 买家不能交付自己的订单；单个玩家同时进行的订单数受 `orders.slots` 限制。
- 托管资金的每一次进出都记入账本，`/eco doctor` 可校验 `escrow:ORDER` 与总账平衡。

## 数据文件

| 文件/目录 | 说明 |
| --- | --- |
| `state.json` | 最近一次快照：玩家/系统/托管余额、玩家名称与时间戳、商店每日限额、市场挂单、收购订单、邮箱、事务序号 |
| `journal.jsonl` | 已提交但尚未进入快照的事务；启动时自动回放 |
| `ledger/YYYY-MM.jsonl` | 按月切分的审计账本，只追加 |
| `backup/` | 自动状态备份，默认保留 14 份；迁移前也会备份旧账户文件 |
| `accounts.yml` | 旧版账户文件，仅用于迁移；迁移成功后保留作为回滚参考 |

不要手动编辑 `state.json` 或 `journal.jsonl`。升级或迁移前请停服并备份整个
`plugins/economy/` 目录。

## 从旧版本迁移

首次以新版本启动时：

1. 如果 `state.json` 不存在，读取 `accounts.yml` 中的 UUID 与余额。
2. 原样写入玩家余额，并把 `system:MINT` 设为玩家余额合计的相反数，保证账本合计为 0。
3. 备份 `accounts.yml` 到 `backup/accounts-<时间>.yml`。
4. 写入 `state.json`；之后启动优先使用 `state.json`，忽略旧的 `accounts.yml`。

迁移失败时插件会记录错误并禁用自身，不会带着空账户启动。

## 扩展 API

插件通过 Bukkit `ServicesManager` 注册 [`EconomyService`](src/main/java/com/xiyuan/plugmc/economy/api/EconomyService.java)，
其他插件可将 Economy 声明为软依赖后获取服务：

```yaml
softdepend: [economy]
```

```java
EconomyService economy = Bukkit.getServicesManager().load(EconomyService.class);
if (economy != null) {
	UUID playerId = player.getUniqueId();
	economy.deposit(playerId, new BigDecimal("25.00"));
	player.sendMessage("余额：" + economy.format(economy.getBalance(playerId)));
}
```

原有方法保持不变；新增的默认方法包括 `has(UUID, BigDecimal)`、
`transfer(UUID, UUID, BigDecimal, String reason)` 和 `getDecimalPlaces()`。
`deposit`、`withdraw`、`transfer` 的金额必须大于零且符合货币精度；`setBalance` 允许零。
`withdraw` 和 `transfer` 在余额不足时返回 `false`，参数错误抛出 `IllegalArgumentException`，
持久化失败抛出 `UncheckedIOException`。

操作同步、线程安全，并通过 journal 立即持久化；建议在主线程调用，避免磁盘 I/O 阻塞游戏线程。
`server-menu` 已依赖本插件，升级后请整体重启服务器，不要使用 `/reload`。

## 构建与部署

```powershell
.\gradlew :plugins:economy:build
python scripts/run_paper.py --build-plugins
python scripts/deploy_paper.py --plugin economy
```

部署后需要重启服务器；日志中出现 `经济系统 已启用` 表示加载成功。
