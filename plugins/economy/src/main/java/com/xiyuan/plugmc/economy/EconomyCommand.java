package com.xiyuan.plugmc.economy;

import com.xiyuan.plugmc.economy.api.EconomyService;
import com.xiyuan.plugmc.economy.internal.EconomyConfig;
import com.xiyuan.plugmc.economy.internal.EconomyStore;
import com.xiyuan.plugmc.economy.internal.LedgerService;
import com.xiyuan.plugmc.economy.internal.Money;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class EconomyCommand implements CommandExecutor, TabCompleter {
	private static final List<String> ADMIN_ACTIONS = List.of(
		"give", "take", "set", "stats", "doctor", "save", "backup"
	);

	private final JavaPlugin plugin;
	private final EconomyService economy;
	private final LedgerService ledger;
	private final EconomyStore store;
	private final EconomyConfig config;

	public EconomyCommand(
		JavaPlugin plugin,
		EconomyService economy,
		LedgerService ledger,
		EconomyStore store,
		EconomyConfig config
	) {
		this.plugin = plugin;
		this.economy = economy;
		this.ledger = ledger;
		this.store = store;
		this.config = config;
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		switch (command.getName().toLowerCase()) {
			case "money" -> showBalance(sender, args);
			case "pay" -> pay(sender, args);
			case "eco" -> manageBalance(sender, args);
			default -> {
				return false;
			}
		}
		return true;
	}

	private void showBalance(CommandSender sender, String[] args) {
		if (args.length == 0 && sender instanceof Player player) {
			sender.sendMessage("你的余额：" + economy.format(economy.getBalance(player.getUniqueId())));
			return;
		}
		if (args.length != 1) {
			sender.sendMessage("用法：/money [玩家]");
			return;
		}
		if (!sender.hasPermission("economy.balance.others")) {
			sender.sendMessage("你没有权限查看其他玩家余额。");
			return;
		}

		OfflinePlayer target = findPlayer(args[0]);
		if (target == null) {
			sender.sendMessage("找不到该玩家；对方需要至少加入过一次服务器。");
			return;
		}
		sender.sendMessage(target.getName() + " 的余额：" + economy.format(economy.getBalance(target.getUniqueId())));
	}

	private void pay(CommandSender sender, String[] args) {
		if (!(sender instanceof Player player)) {
			sender.sendMessage("只有玩家可以转账。");
			return;
		}
		if (args.length != 2) {
			sender.sendMessage("用法：/pay <玩家> <金额>");
			return;
		}

		OfflinePlayer target = findPlayer(args[0]);
		if (target == null) {
			sender.sendMessage("找不到该玩家；对方需要至少加入过一次服务器。");
			return;
		}
		BigDecimal amount = parseAmount(sender, args[1]);
		if (amount == null) {
			return;
		}
		if (!ledger.transfer(player.getUniqueId(), target.getUniqueId(), amount, "PAY", player.getName())) {
			sender.sendMessage(target.getUniqueId().equals(player.getUniqueId())
				? "不能向自己转账。"
				: "余额不足，当前余额：" + economy.format(economy.getBalance(player.getUniqueId())));
			return;
		}

		sender.sendMessage("已向 " + target.getName() + " 转账 " + economy.format(amount) + "。");
		if (target.isOnline()) {
			target.getPlayer().sendMessage("你收到了来自 " + player.getName() + " 的 " + economy.format(amount) + "。");
		}
	}

	private void manageBalance(CommandSender sender, String[] args) {
		if (!sender.hasPermission("economy.admin")) {
			sender.sendMessage("你没有权限管理经济系统。");
			return;
		}
		if (args.length == 0) {
			usage(sender);
			return;
		}

		String action = args[0].toLowerCase();
		switch (action) {
			case "stats" -> {
				if (args.length != 1) {
					usage(sender);
					return;
				}
				stats(sender);
				return;
			}
			case "doctor" -> {
				if (args.length != 1) {
					usage(sender);
					return;
				}
				doctor(sender);
				return;
			}
			case "save" -> {
				if (args.length != 1) {
					usage(sender);
					return;
				}
				store.snapshot();
				sender.sendMessage("经济数据已保存，事务序号：" + store.lastSeq());
				return;
			}
			case "backup" -> {
				if (args.length != 1) {
					usage(sender);
					return;
				}
				Path backup = store.backupNow();
				sender.sendMessage(backup == null
					? "备份完成，但没有可复制的状态文件。"
					: "经济数据已备份：" + backup);
				return;
			}
			default -> {
			}
		}

		if (!List.of("give", "take", "set").contains(action) || args.length != 3) {
			usage(sender);
			return;
		}
		OfflinePlayer target = findPlayer(args[1]);
		if (target == null) {
			sender.sendMessage("找不到该玩家；对方需要至少加入过一次服务器。");
			return;
		}
		BigDecimal amount = parseAmount(sender, args[2], action.equals("set"));
		if (amount == null) {
			return;
		}

		switch (action) {
			case "give" -> ledger.deposit(target.getUniqueId(), amount, "ADMIN_GIVE", sender.getName());
			case "take" -> {
				if (!ledger.withdraw(target.getUniqueId(), amount, "ADMIN_TAKE", sender.getName())) {
					sender.sendMessage("余额不足，当前余额：" + economy.format(economy.getBalance(target.getUniqueId())));
					return;
				}
			}
			case "set" -> ledger.setBalance(target.getUniqueId(), amount, "ADMIN_SET", sender.getName());
			default -> {
			}
		}
		sender.sendMessage("已将 " + target.getName() + " 的余额" + switch (action) {
			case "give" -> "增加 " + economy.format(amount);
			case "take" -> "扣除 " + economy.format(amount);
			default -> "设为 " + economy.format(amount);
		} + "。");
	}

	private void stats(CommandSender sender) {
		Map<String, BigDecimal> balances = ledger.balances();
		long players = balances.keySet().stream().filter(key -> key.startsWith("player:")).count();
		sender.sendMessage("经济统计：");
		sender.sendMessage("- 玩家账户：" + players);
		sender.sendMessage("- 玩家余额合计：" + economy.format(ledger.playerTotal()));
		sender.sendMessage("- 铸币账户：" + ledger.accountBalance(LedgerService.MINT).toPlainString());
		sender.sendMessage("- 税收账户：" + economy.format(ledger.accountBalance(LedgerService.TAX)));
		sender.sendMessage("- 财政账户：" + economy.format(ledger.accountBalance(LedgerService.TREASURY)));
		BigDecimal escrow = ledger.accountBalance(LedgerService.ESCROW_MARKET)
			.add(ledger.accountBalance(LedgerService.ESCROW_AUCTION))
			.add(ledger.accountBalance(LedgerService.ESCROW_ORDER))
			.add(ledger.accountBalance(LedgerService.ESCROW_TRADE));
		sender.sendMessage("- 托管合计：" + economy.format(escrow));
		sender.sendMessage("- 事务序号：" + store.lastSeq());
	}

	private void doctor(CommandSender sender) {
		BigDecimal total = ledger.total();
		List<String> issues = new ArrayList<>();
		if (total.signum() != 0) {
			issues.add("账本合计为 " + total.toPlainString() + "，应为 0");
		}
		for (Map.Entry<String, BigDecimal> entry : ledger.balances().entrySet()) {
			if (entry.getKey().startsWith("player:") && entry.getValue().signum() < 0) {
				issues.add("玩家账户为负：" + entry.getKey());
			}
			if (entry.getKey().startsWith("escrow:") && entry.getValue().signum() < 0) {
				issues.add("托管账户为负：" + entry.getKey());
			}
		}
		if (issues.isEmpty()) {
			sender.sendMessage("经济自检通过：账本平衡，玩家/托管账户无负数。");
		} else {
			sender.sendMessage("经济自检发现 " + issues.size() + " 个问题：");
			for (String issue : issues) {
				sender.sendMessage("- " + issue);
			}
		}
		sender.sendMessage("事务序号：" + store.lastSeq() + "，数据目录：" + store.dataDir());
	}

	private void usage(CommandSender sender) {
		sender.sendMessage("用法：/eco <give|take|set> <玩家> <金额>");
		sender.sendMessage("管理：/eco stats | /eco doctor | /eco save | /eco backup");
	}

	private BigDecimal parseAmount(CommandSender sender, String raw) {
		return parseAmount(sender, raw, false);
	}

	private BigDecimal parseAmount(CommandSender sender, String raw, boolean allowZero) {
		try {
			return Money.parse(raw, config.decimalPlaces(), allowZero);
		} catch (IllegalArgumentException exception) {
			sender.sendMessage("金额无效：" + exception.getMessage());
			return null;
		}
	}

	private OfflinePlayer findPlayer(String name) {
		Player online = Bukkit.getPlayerExact(name);
		return online != null ? online : Bukkit.getOfflinePlayerIfCached(name);
	}

	@Override
	public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
		if (args.length == 1 && command.getName().equalsIgnoreCase("eco")) {
			return ADMIN_ACTIONS.stream()
				.filter(option -> option.startsWith(args[0].toLowerCase()))
				.toList();
		}
		if ((command.getName().equalsIgnoreCase("money") && args.length == 1 && sender.hasPermission("economy.balance.others"))
			|| (command.getName().equalsIgnoreCase("pay") && args.length == 1)
			|| (command.getName().equalsIgnoreCase("eco") && args.length == 2
				&& List.of("give", "take", "set").contains(args[0].toLowerCase())
				&& sender.hasPermission("economy.admin"))) {
			String prefix = args[args.length - 1].toLowerCase();
			List<String> names = new ArrayList<>();
			for (Player player : Bukkit.getOnlinePlayers()) {
				if (player.getName().toLowerCase().startsWith(prefix)) {
					names.add(player.getName());
				}
			}
			return names;
		}
		return List.of();
	}
}
