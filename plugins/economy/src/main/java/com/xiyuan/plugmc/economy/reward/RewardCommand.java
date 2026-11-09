package com.xiyuan.plugmc.economy.reward;

import com.xiyuan.plugmc.economy.internal.LedgerService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

public final class RewardCommand implements CommandExecutor, TabCompleter {
	private final RewardService rewards;
	private final LedgerService ledger;

	public RewardCommand(RewardService rewards, LedgerService ledger) {
		this.rewards = rewards;
		this.ledger = ledger;
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (!command.getName().equalsIgnoreCase("daily")) {
			return false;
		}
		if (!(sender instanceof Player player)) {
			sender.sendMessage("只有玩家可以签到。");
			return true;
		}
		if (args.length == 0) {
			rewards.claimDaily(player);
			return true;
		}
		switch (args[0].toLowerCase(Locale.ROOT)) {
			case "status" -> player.sendMessage("签到状态：连续 " + rewards.todayDailyStreak(player)
				+ " 天，余额 " + ledger.format(ledger.balance(player.getUniqueId())) + "。");
			case "reload" -> {
				if (!player.hasPermission("economy.reward.admin")) {
					player.sendMessage("你没有权限重载奖励配置。");
					return true;
				}
				try {
					rewards.reload();
					player.sendMessage("奖励配置已重载。");
				} catch (RuntimeException exception) {
					player.sendMessage("奖励配置重载失败：" + exception.getMessage());
				}
			}
			default -> player.sendMessage("用法：/daily [status|reload]");
		}
		return true;
	}

	@Override
	public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
		if (!command.getName().equalsIgnoreCase("daily")) {
			return List.of();
		}
		if (args.length == 1) {
			String prefix = args[0].toLowerCase(Locale.ROOT);
			return List.of("status", "reload").stream()
				.filter(option -> option.startsWith(prefix)
					&& (!option.equals("reload") || sender.hasPermission("economy.reward.admin")))
				.toList();
		}
		return List.of();
	}
}
