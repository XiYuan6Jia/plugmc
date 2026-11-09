package com.xiyuan.plugmc.economy;

import com.xiyuan.plugmc.economy.api.EconomyService;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public final class EconomyCommand implements CommandExecutor, TabCompleter {
	private final JavaPlugin plugin;
	private final EconomyService economy;

	public EconomyCommand(JavaPlugin plugin, EconomyService economy) {
		this.plugin = plugin;
		this.economy = economy;
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
		if (!economy.transfer(player.getUniqueId(), target.getUniqueId(), amount)) {
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
			sender.sendMessage("你没有权限管理余额。");
			return;
		}
		if (args.length != 3) {
			sender.sendMessage("用法：/eco <give|take|set> <玩家> <金额>");
			return;
		}

		String action = args[0].toLowerCase();
		if (!List.of("give", "take", "set").contains(action)) {
			sender.sendMessage("用法：/eco <give|take|set> <玩家> <金额>");
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
			case "give" -> economy.deposit(target.getUniqueId(), amount);
			case "take" -> {
				if (!economy.withdraw(target.getUniqueId(), amount)) {
					sender.sendMessage("余额不足，当前余额：" + economy.format(economy.getBalance(target.getUniqueId())));
					return;
				}
			}
			case "set" -> economy.setBalance(target.getUniqueId(), amount);
		}
		sender.sendMessage("已将 " + target.getName() + " 的余额" + switch (action) {
			case "give" -> "增加 " + economy.format(amount);
			case "take" -> "扣除 " + economy.format(amount);
			default -> "设为 " + economy.format(amount);
		} + "。");
	}

	private BigDecimal parseAmount(CommandSender sender, String raw) {
		return parseAmount(sender, raw, false);
	}

	private BigDecimal parseAmount(CommandSender sender, String raw, boolean allowZero) {
		try {
			BigDecimal amount = new BigDecimal(raw);
			if (amount.scale() > plugin.getConfig().getInt("currency.decimal-places", 2)
				|| (allowZero ? amount.signum() < 0 : amount.signum() <= 0)) {
				sender.sendMessage("金额必须为" + (allowZero ? "非负" : "正数") + "，且最多有 "
					+ plugin.getConfig().getInt("currency.decimal-places", 2) + " 位小数。");
				return null;
			}
			return amount;
		} catch (NumberFormatException exception) {
			sender.sendMessage("无效金额：" + raw);
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
			return List.of("give", "take", "set").stream()
				.filter(option -> option.startsWith(args[0].toLowerCase()))
				.toList();
		}
		if ((command.getName().equalsIgnoreCase("money") && args.length == 1 && sender.hasPermission("economy.balance.others"))
			|| (command.getName().equalsIgnoreCase("pay") && args.length == 1)
			|| (command.getName().equalsIgnoreCase("eco") && args.length == 2 && sender.hasPermission("economy.admin"))) {
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
