package com.xiyuan.plugmc.sleepvote;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

public final class SleepVoteCommand implements CommandExecutor, TabCompleter {
	private final SleepVotePlugin plugin;

	public SleepVoteCommand(SleepVotePlugin plugin) {
		this.plugin = plugin;
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		String action = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
		switch (action) {
			case "status" -> status(sender);
			case "reload" -> reload(sender);
			default -> sender.sendMessage("用法：/sleepvote [status|reload]");
		}
		return true;
	}

	private void status(CommandSender sender) {
		if (!(sender instanceof Player player)) {
			sender.sendMessage("当前配置：半径 " + plugin.radius() + " 格，比例 "
				+ plugin.percentage() + "%，最少玩家 " + plugin.minPlayers() + "。");
			return;
		}
		int[] counts = plugin.counts(player);
		player.sendMessage(SleepVotePlugin.LEGACY.deserialize("&e入睡半径：&f" + plugin.radius()
			+ " &e格，比例 &f" + plugin.percentage() + "%&e。"));
		player.sendMessage(SleepVotePlugin.LEGACY.deserialize("&7半径内玩家 &f" + counts[0]
			+ " &7，已入睡 &f" + counts[1] + " &7，需要 &f" + counts[2] + " &7人。"));
	}

	private void reload(CommandSender sender) {
		if (!sender.hasPermission("sleepvote.admin")) {
			sender.sendMessage(SleepVotePlugin.LEGACY.deserialize("&c你没有权限重载配置。"));
			return;
		}
		plugin.loadSettings();
		sender.sendMessage(SleepVotePlugin.LEGACY.deserialize("&a入睡半径配置已重载。"));
	}

	@Override
	public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
		if (args.length != 1) {
			return List.of();
		}
		String prefix = args[0].toLowerCase(Locale.ROOT);
		return List.of("status", "reload").stream()
			.filter(option -> option.startsWith(prefix))
			.toList();
	}
}
