package com.xiyuan.plugmc.tpa;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class TpaCommand implements CommandExecutor, TabCompleter {
	private final TpaManager manager;
	private final TpaGui gui;
	private final TpaPlugin plugin;

	public TpaCommand(TpaPlugin plugin, TpaManager manager, TpaGui gui) {
		this.plugin = plugin;
		this.manager = manager;
		this.gui = gui;
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (!(sender instanceof Player player)) {
			sender.sendMessage("只有玩家可以使用申请传送。");
			return true;
		}
		if (!player.hasPermission("tpa.use")) {
			player.sendMessage(TpaText.of("&c你没有权限使用申请传送。"));
			return true;
		}

		String name = command.getName().toLowerCase(Locale.ROOT);
		switch (name) {
			case "tpaccept" -> manager.accept(player, args.length >= 1 ? args[0] : null);
			case "tpadeny" -> manager.deny(player, args.length >= 1 ? args[0] : null);
			case "tpcancel" -> manager.cancel(player, args.length >= 1 ? args[0] : null);
			case "tpa" -> {
				if (args.length == 0) {
					gui.open(player, false);
					return true;
				}
				if (args[0].equalsIgnoreCase("reload")) {
					if (!player.hasPermission("tpa.admin")) {
						player.sendMessage(TpaText.of("&c你没有权限重载传送配置。"));
						return true;
					}
					manager.reload();
					player.sendMessage(TpaText.of("&a申请传送配置已重载。"));
					return true;
				}
				Player target = Bukkit.getPlayerExact(args[0]);
				if (target == null) {
					player.sendMessage(TpaText.of("&c找不到在线玩家：" + args[0]));
					return true;
				}
				manager.request(player, target, TpaRequest.Direction.TO_TARGET);
			}
			case "tpahere" -> {
				if (args.length == 0) {
					player.sendMessage(TpaText.of("&c用法：/tpahere <玩家>"));
					return true;
				}
				Player target = Bukkit.getPlayerExact(args[0]);
				if (target == null) {
					player.sendMessage(TpaText.of("&c找不到在线玩家：" + args[0]));
					return true;
				}
				manager.request(player, target, TpaRequest.Direction.TO_SENDER);
			}
			default -> {
				return false;
			}
		}
		return true;
	}

	@Override
	public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
		if (!(sender instanceof Player player)) {
			return List.of();
		}
		String name = command.getName().toLowerCase(Locale.ROOT);
		if (name.equals("tpa") && args.length == 1) {
			String prefix = args[0].toLowerCase(Locale.ROOT);
			List<String> options = new ArrayList<>();
			if (player.hasPermission("tpa.admin") && "reload".startsWith(prefix)) {
				options.add("reload");
			}
			options.addAll(onlineNames(player, prefix));
			return options;
		}
		if ((name.equals("tpahere") || name.equals("tpaccept") || name.equals("tpadeny")
			|| name.equals("tpcancel")) && args.length == 1) {
			return onlineNames(player, args[0].toLowerCase(Locale.ROOT));
		}
		return List.of();
	}

	private List<String> onlineNames(Player sender, String prefix) {
		return Bukkit.getOnlinePlayers().stream()
			.filter(online -> !online.getUniqueId().equals(sender.getUniqueId()))
			.map(Player::getName)
			.filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
			.sorted(String::compareToIgnoreCase)
			.toList();
	}
}
