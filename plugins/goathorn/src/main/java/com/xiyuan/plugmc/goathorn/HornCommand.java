package com.xiyuan.plugmc.goathorn;

import java.util.List;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

/** /goathorn [info|reload] —— 玩家侧用法都在物品上，命令只做状态查询与热重载。 */
final class HornCommand implements CommandExecutor, TabCompleter {
	private static final List<String> SUBCOMMANDS = List.of("info", "reload");

	private final GoatHornPlugin plugin;

	HornCommand(GoatHornPlugin plugin) {
		this.plugin = plugin;
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (args.length == 0) {
			sender.sendMessage(HornText.component("&f用法：&e/" + label + " info|reload"));
			return true;
		}
		switch (args[0].toLowerCase()) {
			case "info" -> info(sender);
			case "reload" -> reload(sender);
			default -> sender.sendMessage(HornText.component("&c未知子命令，用法：/" + label + " info|reload"));
		}
		return true;
	}

	private void info(CommandSender sender) {
		HornConfig config = plugin.settings();
		sender.sendMessage(HornText.component("&6山羊角宠物哨"));
		sender.sendMessage(HornText.component("&7状态：&f" + (config.enabled() ? "启用" : "关闭")));
		sender.sendMessage(HornText.component("&7吹奏冷却：&f" + config.cooldownSeconds() + " 秒"));
		sender.sendMessage(HornText.component("&7每只羊角上限：&f"
			+ (config.maxAnimalsPerHorn() <= 0 ? "无限" : config.maxAnimalsPerHorn() + " 只")));
	}

	private void reload(CommandSender sender) {
		if (!sender.hasPermission("goathorn.admin")) {
			sender.sendMessage(HornText.component("&c你没有权限执行这个操作。"));
			return;
		}
		plugin.reloadSettings();
		sender.sendMessage(HornText.component("&a山羊角宠物哨配置已重载。"));
	}

	@Override
	public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
		if (args.length == 1) {
			return SUBCOMMANDS.stream()
				.filter(value -> value.startsWith(args[0].toLowerCase()))
				.toList();
		}
		return List.of();
	}
}
