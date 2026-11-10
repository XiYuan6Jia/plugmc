package com.xiyuan.plugmc.economy;

import com.xiyuan.plugmc.economy.api.EconomyAuditService;
import com.xiyuan.plugmc.economy.api.EconomyService;
import com.xiyuan.plugmc.economy.gui.LeaderboardGui;
import com.xiyuan.plugmc.economy.internal.EconomyConfig;
import com.xiyuan.plugmc.economy.internal.EconomyStore;
import com.xiyuan.plugmc.economy.internal.EscrowAudit;
import com.xiyuan.plugmc.economy.internal.LedgerQuery;
import com.xiyuan.plugmc.economy.internal.LedgerService;
import com.xiyuan.plugmc.economy.internal.Money;
import com.xiyuan.plugmc.economy.reward.RewardService;
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
		"give", "take", "set", "stats", "doctor", "save", "backup", "selftest",
		"audit", "treasury", "freeze", "unfreeze"
	);

	private final JavaPlugin plugin;
	private final EconomyService economy;
	private final LedgerService ledger;
	private final EconomyStore store;
	private final EconomyConfig config;
	private final EconomyAuditService audit;
	private final RewardService rewards;

	public EconomyCommand(
		JavaPlugin plugin,
		EconomyService economy,
		LedgerService ledger,
		EconomyStore store,
		EconomyConfig config,
		EconomyAuditService audit,
		RewardService rewards
	) {
		this.plugin = plugin;
		this.economy = economy;
		this.ledger = ledger;
		this.store = store;
		this.config = config;
		this.audit = audit;
		this.rewards = rewards;
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		switch (command.getName().toLowerCase()) {
			case "money" -> showBalance(sender, args);
			case "pay" -> pay(sender, args);
			case "eco" -> manageBalance(sender, args);
			case "baltop" -> showTop(sender);
			default -> {
				return false;
			}
		}
		return true;
	}

	private void showTop(CommandSender sender) {
		if (!sender.hasPermission("economy.balance.top")) {
			sender.sendMessage("你没有权限查看余额排行。");
			return;
		}
		if (sender instanceof Player player) {
			new LeaderboardGui(economy).open(player, 0);
			return;
		}
		List<EconomyService.BalanceEntry> entries = economy.topBalances(10);
		if (entries.isEmpty()) {
			sender.sendMessage("暂无排行数据。");
			return;
		}
		sender.sendMessage("余额排行（前 " + entries.size() + " 名）：");
		for (int index = 0; index < entries.size(); index++) {
			EconomyService.BalanceEntry entry = entries.get(index);
			sender.sendMessage("#" + (index + 1) + " " + entry.name()
				+ "：" + economy.format(entry.balance()));
		}
	}

	private void showBalance(CommandSender sender, String[] args) {
		if (args.length == 1 && args[0].equalsIgnoreCase("daily") && sender instanceof Player player) {
			rewards.claimDaily(player);
			return;
		}
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
		BigDecimal tax = config.payTax(amount, config.decimalPlaces());
		BigDecimal net = amount.subtract(tax);
		if (!ledger.transferWithTax(player.getUniqueId(), target.getUniqueId(), amount, tax, "PAY", player.getName())) {
			sender.sendMessage(target.getUniqueId().equals(player.getUniqueId())
				? "不能向自己转账。"
				: "余额不足，当前余额：" + economy.format(economy.getBalance(player.getUniqueId())));
			return;
		}

		sender.sendMessage("已向 " + target.getName() + " 转账 " + economy.format(amount)
			+ "（交易税 " + economy.format(tax) + "，实际到账 " + economy.format(net) + "）。");
		if (target.isOnline()) {
			target.getPlayer().sendMessage("你收到了来自 " + player.getName() + " 的 "
				+ economy.format(net) + "（转账 " + economy.format(amount)
				+ "，交易税 " + economy.format(tax) + "）。");
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
			case "selftest" -> {
				if (args.length != 1) {
					usage(sender);
					return;
				}
				selfTest(sender);
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
			case "audit" -> {
				audit(sender, args);
				return;
			}
			case "treasury" -> {
				treasury(sender, args);
				return;
			}
			case "freeze", "unfreeze" -> {
				freeze(sender, args, action.equals("freeze"));
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
				if (!ledger.withdraw(target.getUniqueId(), amount, "ADMIN_TAKE", sender.getName(), true)) {
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
		sender.sendMessage("经济统计：");
		for (String line : audit.statsLines()) {
			sender.sendMessage("- " + line);
		}
	}

	private void doctor(CommandSender sender) {
		List<String> issues = new ArrayList<>();
		for (EconomyAuditService.Check check : audit.doctorChecks()) {
			if (!check.passed()) {
				issues.add(check.name() + "：" + check.detail());
			}
		}
		List<String> reminders = audit.reminders();
		for (String reminder : reminders) {
			sender.sendMessage("[提示] " + reminder);
		}
		if (issues.isEmpty()) {
			sender.sendMessage("经济自检通过：账本平衡，玩家/托管账户无负数，托管金额与挂单一致。");
		} else {
			sender.sendMessage("经济自检发现 " + issues.size() + " 个问题：");
			for (String issue : issues) {
				sender.sendMessage("- " + issue);
			}
		}
		sender.sendMessage("事务序号：" + store.lastSeq() + "，数据目录：" + store.dataDir());
	}

	private void selfTest(CommandSender sender) {
		List<EconomyAuditService.Check> checks = audit.selfTestChecks();
		long failed = checks.stream().filter(check -> !check.passed()).count();
		sender.sendMessage("经济演练（不接触真实数据）：");
		for (EconomyAuditService.Check check : checks) {
			sender.sendMessage((check.passed() ? "  ✔ " : "  ✘ ") + check.name() + " [" + check.detail() + "]");
		}
		if (failed == 0) {
			sender.sendMessage("演练全部通过：" + checks.size() + " 项。");
		} else {
			sender.sendMessage("演练失败 " + failed + " 项，请检查结算与账本逻辑。");
		}
	}

	private void usage(CommandSender sender) {
		sender.sendMessage("用法：/eco <give|take|set> <玩家> <金额>");
		sender.sendMessage("管理：/eco stats | /eco doctor | /eco selftest | /eco audit <玩家> [页]");
		sender.sendMessage("资金：/eco treasury pay <玩家> <金额> <原因> | /eco freeze|unfreeze <玩家>");
		sender.sendMessage("数据：/eco save | /eco backup");
	}

	private void audit(CommandSender sender, String[] args) {
		if (args.length < 2 || args.length > 3) {
			sender.sendMessage("用法：/eco audit <玩家> [页]");
			return;
		}
		OfflinePlayer target = findPlayer(args[1]);
		if (target == null) {
			sender.sendMessage("找不到该玩家；对方需要至少加入过一次服务器。");
			return;
		}
		int page = 1;
		if (args.length == 3) {
			try {
				page = Math.max(1, Integer.parseInt(args[2]));
			} catch (NumberFormatException exception) {
				sender.sendMessage("页码必须是数字。");
				return;
			}
		}
		int pageSize = 10;
		String account = LedgerService.playerKey(target.getUniqueId());
		LedgerQuery query = new LedgerQuery(store.ledgerDir());
		int total = query.totalEntries(account);
		if (total == 0) {
			sender.sendMessage(target.getName() + " 还没有账本流水。");
			return;
		}
		int pageCount = Math.max(1, (total + pageSize - 1) / pageSize);
		int current = Math.min(page, pageCount);
		int skip = (current - 1) * pageSize;
		List<LedgerQuery.Entry> entries = query.forAccount(account, skip + pageSize);
		List<LedgerQuery.Entry> pageEntries = entries.subList(
			Math.min(skip, entries.size()),
			entries.size()
		);
		sender.sendMessage(target.getName() + " 的流水（第 " + current + "/" + pageCount
			+ " 页，共 " + total + " 笔）：");
		for (LedgerQuery.Entry entry : pageEntries) {
			boolean income = entry.delta().signum() >= 0;
			sender.sendMessage("#" + entry.seq() + " " + entry.reason()
				+ " " + (income ? "+" : "-") + economy.format(entry.delta().abs())
				+ (entry.ref() == null || entry.ref().isBlank() ? "" : " [" + entry.ref() + "]"));
		}
	}

	private void treasury(CommandSender sender, String[] args) {
		if (args.length >= 3 && args[1].equalsIgnoreCase("fund")) {
			BigDecimal amount = parseAmount(sender, args[2]);
			if (amount == null) {
				return;
			}
			String reason = args.length > 3
				? String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length))
				: "TREASURY_FUND";
			boolean funded = ledger.transferAccount(
				LedgerService.MINT,
				LedgerService.TREASURY,
				amount,
				"TREASURY_FUND",
				reason
			);
			if (!funded) {
				sender.sendMessage("财政拨款失败，请检查账本状态。");
				return;
			}
			sender.sendMessage("已向财政注入 " + economy.format(amount) + "，当前财政："
				+ economy.format(ledger.accountBalance(LedgerService.TREASURY))
				+ "，原因：" + reason + "。");
			return;
		}
		if (args.length < 4 || !args[1].equalsIgnoreCase("pay")) {
			sender.sendMessage("用法：/eco treasury pay <玩家> <金额> <原因>");
			sender.sendMessage("　　　/eco treasury fund <金额> [原因]");
			return;
		}
		OfflinePlayer target = findPlayer(args[2]);
		if (target == null) {
			sender.sendMessage("找不到该玩家；对方需要至少加入过一次服务器。");
			return;
		}
		BigDecimal amount = parseAmount(sender, args[3]);
		if (amount == null) {
			return;
		}
		String reason = String.join(" ", java.util.Arrays.copyOfRange(args, 4, args.length));
		if (reason.isBlank()) {
			reason = "TREASURY_PAY";
		}
		boolean paid = ledger.transferAccount(
			LedgerService.TREASURY,
			LedgerService.playerKey(target.getUniqueId()),
			amount,
			"TREASURY_PAY",
			reason
		);
		if (!paid) {
			sender.sendMessage("财政余额不足，当前财政："
				+ economy.format(ledger.accountBalance(LedgerService.TREASURY)));
			return;
		}
		sender.sendMessage("已从财政向 " + target.getName() + " 发放 " + economy.format(amount)
			+ "，原因：" + reason + "。");
		if (target.isOnline()) {
			target.getPlayer().sendMessage("你收到了服务器财政发放的 " + economy.format(amount)
				+ "（" + reason + "）。");
		}
	}

	private void freeze(CommandSender sender, String[] args, boolean frozen) {
		if (args.length != 2) {
			sender.sendMessage("用法：/eco " + (frozen ? "freeze" : "unfreeze") + " <玩家>");
			return;
		}
		OfflinePlayer target = findPlayer(args[1]);
		if (target == null) {
			sender.sendMessage("找不到该玩家；对方需要至少加入过一次服务器。");
			return;
		}
		ledger.setFrozen(target.getUniqueId(), frozen, sender.getName());
		sender.sendMessage("已" + (frozen ? "冻结" : "解冻") + " " + target.getName()
			+ " 的支出" + (frozen ? "，入账不受影响。" : "。"));
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
		if (args.length == 2 && command.getName().equalsIgnoreCase("eco")
			&& args[0].equalsIgnoreCase("treasury")) {
			String prefix = args[1].toLowerCase();
			return List.of("pay", "fund").stream()
				.filter(option -> option.startsWith(prefix))
				.toList();
		}
		boolean playerArg = (command.getName().equalsIgnoreCase("money")
				&& args.length == 1
				&& sender.hasPermission("economy.balance.others"))
			|| (command.getName().equalsIgnoreCase("pay") && args.length == 1)
			|| (command.getName().equalsIgnoreCase("eco") && args.length == 2
				&& List.of("give", "take", "set", "audit", "freeze", "unfreeze")
					.contains(args[0].toLowerCase())
				&& sender.hasPermission("economy.admin"))
			|| (command.getName().equalsIgnoreCase("eco") && args.length == 3
				&& args[0].equalsIgnoreCase("treasury")
				&& sender.hasPermission("economy.admin"));
		if (playerArg) {
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
