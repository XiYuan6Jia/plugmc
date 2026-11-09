# Economy

基础 Paper 经济插件，余额按玩家 UUID 保存在 `plugins/economy/accounts.yml`，每次余额变更立即原子写入磁盘。首次加入服务器的玩家会获得配置中的初始余额。

## 命令

| 命令 | 作用 | 权限 |
| --- | --- | --- |
| `/money`、`/balance`、`/bal` | 查看自己的余额 | 所有玩家 |
| `/money <玩家>` | 查看其他玩家余额 | `economy.balance.others`（默认 OP） |
| `/pay <玩家> <金额>` | 向已加入过服务器的玩家转账 | 所有玩家 |
| `/eco give <玩家> <金额>` | 增加余额 | `economy.admin`（默认 OP） |
| `/eco take <玩家> <金额>` | 扣除余额 | `economy.admin`（默认 OP） |
| `/eco set <玩家> <金额>` | 设置余额 | `economy.admin`（默认 OP） |

金额使用十进制精确计算，不允许负数或超过配置精度的小数；玩家转账在同一笔持久化事务中完成。

## 配置

首次启动会生成 `plugins/economy/config.yml`：

```yaml
currency:
  name: 金币
  symbol: $
  decimal-places: 2
starting-balance: "100.00"
```

`decimal-places` 范围为 0 到 6。已有账户数据不会因调整初始余额而改变。

## 扩展 API

插件通过 Bukkit `ServicesManager` 注册 [`EconomyService`](src/main/java/com/xiyuan/plugmc/economy/api/EconomyService.java)，其他插件可将 Economy 声明为软依赖后获取服务：

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

`deposit`、`withdraw`、`transfer` 的金额必须大于零且符合货币精度；`setBalance` 允许零。`withdraw` 和 `transfer` 在余额不足时返回 `false`，其他参数错误会抛出 `IllegalArgumentException`，持久化失败会抛出 `UncheckedIOException`。操作同步、线程安全并立即持久化，建议从服务器主线程调用，避免磁盘 I/O 阻塞游戏线程。
