package com.xiyuan.plugmc.economy.trade;

import com.xiyuan.plugmc.economy.gui.TradeGui;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class TradeCommand implements CommandExecutor, TabCompleter {
	private static final List<String> SUBCOMMANDS = List.of("accept", "deny", "cancel");

	private final TradeManager manager;
	private final TradeGui gui;

	public TradeCommand(TradeManager manager, TradeGui gui) {
		this.manager = manager;
		this.gui = gui;
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (!(sender instanceof Player player)) {
			sender.sendMessage("只有玩家可以交易。");
			return true;
		}
		if (!player.hasPermission("economy.trade.use")) {
			player.sendMessage("你没有权限使用直接交易。");
			return true;
		}
		if (args.length == 0) {
			gui.open(player);
			return true;
		}
		switch (args[0].toLowerCase(Locale.ROOT)) {
			case "accept" -> manager.accept(player, args.length >= 2 ? args[1] : null);
			case "deny", "reject" -> manager.deny(player, args.length >= 2 ? args[1] : null);
			case "cancel" -> {
				if (manager.inSession(player)) {
					player.sendMessage("请点击交易窗口里的「取消交易」结束交易。");
				} else {
					manager.cancelRequest(player, args.length >= 2 ? args[1] : null);
				}
			}
			default -> manager.request(player, args[0]);
		}
		return true;
	}

	@Override
	public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
		if (args.length == 1) {
			String prefix = args[0].toLowerCase(Locale.ROOT);
			List<String> options = new ArrayList<>();
			for (String option : SUBCOMMANDS) {
				if (option.startsWith(prefix)) {
					options.add(option);
				}
			}
			for (Player online : Bukkit.getOnlinePlayers()) {
				if (online != sender && online.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
					options.add(online.getName());
				}
			}
			return options;
		}
		if (args.length == 2 && List.of("accept", "deny", "reject").contains(args[0].toLowerCase(Locale.ROOT))) {
			String prefix = args[1].toLowerCase(Locale.ROOT);
			return Bukkit.getOnlinePlayers().stream()
				.filter(online -> online != sender)
				.map(Player::getName)
				.filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
				.toList();
		}
		return List.of();
	}
}
