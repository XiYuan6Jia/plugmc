# Economy

Paper 经济插件。当前版本使用**复式记账账本**：玩家余额、税收、财政和托管账户统一记录在
`state.json`，每笔资金变更先追加 `journal.jsonl` 并刷盘，再应用到内存；定时写快照并保留备份。
旧版的 `accounts.yml` 会在首次启动时自动迁移。

## 命令

| 命令 | 作用 | 权限 |
| --- | --- | --- |
| `/money`、`/balance`、`/bal` | 查看自己的余额 | 所有玩家 |
| `/money <玩家>` | 查看其他玩家余额 | `economy.balance.others`（默认 OP） |
| `/baltop` | 打开余额排行界面（控制台输出前 10 名） | `economy.balance.top`（默认所有玩家） |
| `/pay <玩家> <金额>` | 向已加入过服务器的玩家转账 | 所有玩家 |
| `/shop` | 打开系统商店 GUI | `economy.shop.use`（默认所有玩家） |
| `/shop buy <商品> [数量]` | 购买商品 | `economy.shop.use` |
| `/shop sell [数量\|all]` | 把主手物品卖给商店 | `economy.shop.use` |
| `/shop sellall` | 打开批量回收页：放入物品后一键全部出售 | `economy.shop.use` |
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
| `/mail give <玩家> <物品> <数量>` | 管理端把物品投递到玩家邮箱（补偿/发放） | `economy.mail.admin` |
| `/order` | 列出进行中的收购订单 | `economy.order.use`（默认所有玩家） |
| `/order create <单价> <数量\|all>` | 手持物品作为样本，托管资金发布收购订单 | `economy.order.use` |
| `/order fill <编号> [数量\|all]` | 用背包中的物品交付收购订单 | `economy.order.use` |
| `/order cancel <编号>` | 取消自己的收购订单，托管资金退回 | `economy.order.use` |
| `/order list [页]` / `/order mine` / `/order search <关键词>` | 浏览、查看自己的、搜索收购订单 | `economy.order.use` |
| `/order reload` | 重载收购订单配置 | `economy.order.admin` |
| `/auction` | 列出进行中的拍卖 | `economy.auction.use`（默认所有玩家） |
| `/auction create <起拍价> [一口价\|-] [数量\|all]` | 手持物品上拍，`-` 表示不设一口价 | `economy.auction.use` |
| `/auction bid <编号> <金额>` | 竞价，出价资金进入托管，被超过时自动退回 | `economy.auction.use` |
| `/auction buy <编号>` | 一口价拍下 | `economy.auction.use` |
| `/auction cancel <编号>` | 撤拍（仅限无人出价） | `economy.auction.use` |
| `/auction list [页]` / `mine` / `search <关键词>` | 浏览、查看自己的、搜索拍卖 | `economy.auction.use` |
| `/auction reload` | 重载拍卖行配置 | `economy.auction.admin` |
| `/trade <玩家>` | 发起直接交易请求（60 秒有效） | `economy.trade.use`（默认所有玩家） |
| `/trade accept <玩家>` | 接受交易请求并打开交易窗口 | `economy.trade.use` |
| `/trade deny [玩家]` | 拒绝交易请求 | `economy.trade.use` |
| `/trade cancel [玩家]` | 撤回自己发出的交易请求 | `economy.trade.use` |
| `/daily` | 领取每日签到奖励（连签递增） | `economy.reward.daily` |
| `/daily status` | 查看今日签到状态 | `economy.reward.daily` |
| `/daily reload` | 重载奖励配置 | `economy.reward.admin` |
| `/eco give <玩家> <金额>` | 增加余额 | `economy.admin`（默认 OP） |
| `/eco take <玩家> <金额>` | 扣除余额 | `economy.admin` |
| `/eco set <玩家> <金额>` | 设置余额 | `economy.admin` |
| `/eco stats` | 查看玩家余额、铸币、税收、财政、托管与事务序号 | `economy.admin` |
| `/eco audit <玩家> [页]` | 按时间倒序查看该玩家的审计流水（每页 10 笔） | `economy.admin` |
| `/eco treasury pay <玩家> <金额> <原因>` | 从服务器财政账户发放资金 | `economy.admin` |
| `/eco treasury fund <金额> [原因]` | 由管理员向财政账户注入预算（记入审计） | `economy.admin` |
| `/eco freeze <玩家>` / `/eco unfreeze <玩家>` | 冻结/解冻支出（入账不受影响） | `economy.admin` |
| `/eco doctor` | 检查账本是否平衡、玩家/托管账户是否为负、托管金额与未结束挂单是否一致 | `economy.admin` |
| `/eco selftest` | 用内存账本演练「出价→超价退款→结算抽税→超额扣款拒绝」，不触碰真实数据 | `economy.admin` |
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
auction:
  enabled: true
  slots: 5
  duration-hours: 48
  min-increment: "1.00"
  min-increment-rate: 0.05
  anti-snipe-seconds: 60
  max-extensions: 5
  listing-fee-rate: 0.02
  listing-fee-min: "10.00"
  tax-rate: 0.03
rewards:
  enabled: true
  daily:
    enabled: true
    base: "100.00"
    streak-bonus: "20.00"
    streak-max-days: 7
  playtime:
    enabled: true
    minutes: 10
    amount: "20.00"
    max-per-day: 6
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
- 在商品详情页点击出售按钮时，会从**背包（快捷栏 + 主背包）**扣除对应商品，而不是只取主手；
  详情页会显示背包中该商品的数量，出售后自动刷新。

### 商店批量回收

- 入口：`/shop sellall`，或在 `/shop` 主界面点击「批量回收」。
- 界面上方 27 格为输入区，可随意放入要出售的物品；点击「一键全部出售」一次结算。
- 商店不回收的物品、达到每日上限的剩余部分会留在格子里，关闭界面时自动退回背包（背包满则掉落在脚下）。
- 每次结算按商品分别扣除当日回收额度，并逐项写入 `SHOP_SELL` 账本。

### 玩家市场与邮箱

- 上架：`/market sell <单价> [数量|all]` 使用主手物品；默认上架整组。
  也可以在 `/market` 界面点击「上架主手物品」，在聊天栏输入单价后选择 1 / 16 / 64 / 全部数量上架。
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
- 界面发布有两种入口：
  - 「发布收购（主手物品）」：用主手物品作样本，输入单价后进入数量界面。
  - 「选择物品求购」：打开全部物品列表（按材质名排序、每页 45 个、可翻页），
    点击任意物品作为收购目标，再输入单价与数量。
- 数量界面使用 **增减按钮**（-64 / -16 / -1 / +1 / +16 / +64），实时显示托管总额、手续费、
  余额与「最大可买」；也可以一键设为按余额能买的最大数量。
- 交付：卖方用 `/order fill <编号> [数量|all]`，系统从背包按顺序扣除匹配物品
  （与原版 `isSimilar` 一致），买家托管资金即时结算给卖方，成交税从卖方所得扣除。
  物品与资金在同一次账本事务里提交：物品投递到买家邮箱（`ORDER_FILL`），
  买家在线且背包有空位时立即自动领取；买家邮箱已满时交付会被拒绝，物品不会丢失。
- 取消或过期时，未成交部分的托管资金原额退回买家余额；订单不会产生负数账户。
- 买家不能交付自己的订单；单个玩家同时进行的订单数受 `orders.slots` 限制。
- 托管资金的每一次进出都记入账本，`/eco doctor` 可校验 `escrow:ORDER` 与总账平衡。

### 拍卖行

- 上拍：主手物品作拍品，`/auction create <起拍价> [一口价|-] [数量|all]`，物品进入拍卖托管。
  也可以直接执行 `/auction` 打开拍卖行界面，点击「上拍主手物品」，按提示输入起拍价与一口价后选择数量。
  上拍费 = `max(auction.listing-fee-min, 起拍价 × listing-fee-rate)`，从上拍者余额扣入税收账户，不退还。
- 竞价：`/auction bid <编号> <金额>`，首次出价不低于起拍价；之后最小加价为
  `max(auction.min-increment, 当前价 × min-increment-rate)`，出价越高加价幅度越大。
  出价金额立即从买家余额转入 `escrow:AUCTION`；被更高价超过时原额退回，余额不足无法出价。
- 防狙击：截止前 `anti-snipe-seconds`（默认 60 秒）内出现新出价时，结束时间顺延到
  “出价时刻 + anti-snipe-seconds”，最多顺延 `max-extensions`（默认 5）次；出价者与卖家都会收到提示。
- 一口价：`/auction buy <编号>` 按一口价直接成交，当前最高出价者同时获得退款。
- 结算：默认 48 小时后由定时任务处理——有出价则物品发给最高出价者、成交款扣税后支付卖家；
  无人出价则物品退回卖家邮箱。邮箱已满时保留拍卖，下一轮重试。
- 撤拍：仅在无人出价时允许，物品退回卖家邮箱；上拍费不退。
- 成交与退款全部走账本事务，`/eco doctor` 可校验 `escrow:AUCTION` 与总账平衡。
- 到期提醒：拍卖结束前 10 分钟、收购订单到期前 60 分钟，若卖家/买家在线会收到一次提醒。

### 直接交易

- `/trade <玩家>` 发出请求，对方在 60 秒内执行 `/trade accept <对方>` 打开交易窗口。
  也可以直接执行 `/trade` 打开在线玩家列表：点击头颅发起请求，收到请求时对应头颅会高亮，点击即接受。
- 请求消息带可点击按钮：对方看到 `[点击接受]` / `[拒绝]`，发起者看到 `[取消申请]`；
  `/trade accept`、`/trade deny`、`/trade cancel` 均可省略玩家名，直接处理最近一条请求。
- 交易窗口 6 行：上方 2 行为自己可放置的物品区，中间 2 行实时展示对方物品（只读），
  下方为取消 / 确认 / 状态按钮。
- 双方都点击确认后才交换物品；任一方关闭窗口、点取消或掉线，双方物品立即退回各自背包。
- 交换前会校验双方背包空间，空间不足时自动重置确认状态，不会吞物品。
- 交易不涉及金钱，金钱往来请使用 `/pay`。

### 奖励、财政与冻结

菜单联动：主菜单「每日签到」按钮直接调用 `/daily` 并展示签到状态；
「查看余额」会显示账户是否被冻结；管理员在「经济管理」面板内可以完成财政注入、
财政发放、冻结/解冻与流水查询，无需手动输入命令。

- 签到：`/daily`（也可 `/money daily`），每日一次。连签奖励 =
  `base + streak-bonus × (连签天数 - 1)`，超过 `streak-max-days` 后封顶；
  中断一天后从第 1 天重新开始。
- 在线奖励：每在线 `playtime.minutes` 分钟发放一次 `playtime.amount`，
  每人每天最多 `playtime.max-per-day` 次；在线时长统计在内存中，重启后重新累计。
- 签到和在线奖励都写账本（`REWARD_DAILY` / `REWARD_PLAYTIME`），是货币投放的主要来源。
- `/eco treasury pay <玩家> <金额> <原因>`：从服务器财政账户（税收收入）发放活动奖励，
  资金不足会直接拒绝；发放记录写入审计账本。
- `/eco treasury fund <金额> [原因]`：管理员向财政注入预算（从铸币账户划入），用于活动或补贴；
  同样写入审计账本，`/eco stats` 可看到财政余额变化。
- `/eco freeze <玩家>`：冻结该玩家的所有支出（转账、购买、上架费、出价等），入账不受影响；
  `/eco unfreeze <玩家>` 恢复。管理员 `take`/`set` 仍可操作冻结账户。
- `/eco audit <玩家> [页]`：按时间倒序读取月度审计账本，每页 10 笔，显示事务号、原因、
  收支方向与关联单号。

## 数据文件

| 文件/目录 | 说明 |
| --- | --- |
| `state.json` | 最近一次快照：玩家/系统/托管余额、玩家名称与时间戳、商店每日限额、市场挂单、收购订单、拍卖、邮箱、事务序号 |
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

### 交易事件

每次资金事务提交成功后都会触发 `EconomyTransactionEvent`（同步，主线程），可用于成就、统计、日志等联动：

```java
@EventHandler
public void onEconomyTransaction(EconomyTransactionEvent event) {
	if ("MARKET_BUY".equals(event.reason())) {
		BigDecimal paid = event.playerDelta(playerId);
	}
}
```

事件包含 `reason`（如 `PAY`、`MARKET_BUY`、`AUCTION_SETTLE`）、`reference`（业务编号）、`sequence`（事务序号）
以及全部账本分录；另有 `/baltop` 排行用的 `EconomyService#topBalances(int)`。
不要在事件中再次发起经济操作，避免递归事务。

管理面板等只读展示可以加载 `EconomyAuditService`：

```java
EconomyAuditService audit = Bukkit.getServicesManager().load(EconomyAuditService.class);
for (EconomyAuditService.Check check : audit.doctorChecks()) {
	// check.passed() / check.name() / check.detail()
}
```

它提供 `statsLines()`、`doctorChecks()`、`reminders()`、`selfTestChecks()`，不修改任何数据。
`server-menu` 的「经济管理」页即基于该服务。

## 构建与部署

```powershell
.\gradlew :plugins:economy:build
python scripts/run_paper.py --build-plugins
python scripts/deploy_paper.py --plugin economy
```

部署后需要重启服务器；日志中出现 `经济系统 已启用` 表示加载成功。
