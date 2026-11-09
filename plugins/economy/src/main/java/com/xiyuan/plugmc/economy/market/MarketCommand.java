package com.xiyuan.plugmc.economy.market;

import com.xiyuan.plugmc.economy.gui.MarketGui;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class MarketCommand implements CommandExecutor, TabCompleter {
	private static final List<String> SUBCOMMANDS = List.of(
		"sell", "buy", "cancel", "mine", "search", "reload"
	);

	private final MarketService service;
	private final MarketGui gui;

	public MarketCommand(MarketService service, MarketGui gui) {
		this.service = service;
		this.gui = gui;
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (!command.getName().equalsIgnoreCase("market")) {
			return false;
		}
		if (args.length == 0) {
			Player player = requirePlayer(sender);
			if (player != null) {
				gui.openMain(player);
			}
			return true;
		}

		switch (args[0].toLowerCase(Locale.ROOT)) {
			case "sell" -> sell(sender, args);
			case "buy" -> buy(sender, args);
			case "cancel" -> cancel(sender, args);
			case "mine" -> {
				Player player = requirePlayer(sender);
				if (player != null) {
					gui.openOwn(player);
				}
			}
			case "search" -> search(sender, args);
			case "reload" -> reload(sender);
			default -> usage(sender);
		}
		return true;
	}

	private void sell(CommandSender sender, String[] args) {
		Player player = requirePlayer(sender);
		if (player == null) {
			return;
		}
		if (args.length < 2) {
			sender.sendMessage("用法：/market sell <单价> [数量|all]");
			return;
		}
		BigDecimal price;
		try {
			price = new BigDecimal(args[1]);
		} catch (NumberFormatException exception) {
			sender.sendMessage("单价无效：" + args[1]);
			return;
		}
		boolean all = args.length < 3 || args[2].equalsIgnoreCase("all");
		int amount = all ? 0 : parseInt(args[2]);
		if (!all && amount <= 0) {
			sender.sendMessage("数量必须是正整数，或使用 all。");
			return;
		}
		service.create(player, price, amount, all);
	}

	private void buy(CommandSender sender, String[] args) {
		Player player = requirePlayer(sender);
		if (player == null) {
			return;
		}
		if (args.length < 2) {
			sender.sendMessage("用法：/market buy <挂单编号> [数量]");
			return;
		}
		long id = parseLong(args[1]);
		if (id <= 0) {
			sender.sendMessage("挂单编号无效。");
			return;
		}
		int amount = args.length >= 3 ? parseInt(args[2]) : 1;
		if (amount <= 0) {
			sender.sendMessage("数量必须是正整数。");
			return;
		}
		service.buy(player, id, amount);
	}

	private void cancel(CommandSender sender, String[] args) {
		Player player = requirePlayer(sender);
		if (player == null) {
			return;
		}
		if (args.length != 2) {
			sender.sendMessage("用法：/market cancel <挂单编号>");
			return;
		}
		long id = parseLong(args[1]);
		if (id <= 0) {
			sender.sendMessage("挂单编号无效。");
			return;
		}
		service.cancel(player, id);
	}

	private void search(CommandSender sender, String[] args) {
		Player player = requirePlayer(sender);
		if (player == null) {
			return;
		}
		if (args.length < 2) {
			sender.sendMessage("用法：/market search <关键词>");
			return;
		}
		gui.openSearch(player, join(args, 1));
	}

	private void reload(CommandSender sender) {
		if (!sender.hasPermission("economy.market.admin")) {
			sender.sendMessage("你没有权限重载市场配置。");
			return;
		}
		try {
			service.reload();
			sender.sendMessage("市场配置已重载。");
		} catch (RuntimeException exception) {
			sender.sendMessage("市场配置重载失败：" + exception.getMessage());
		}
	}

	private Player requirePlayer(CommandSender sender) {
		if (sender instanceof Player player) {
			return player;
		}
		sender.sendMessage("只有玩家可以执行这个市场操作。");
		return null;
	}

	private int parseInt(String raw) {
		try {
			return Integer.parseInt(raw);
		} catch (NumberFormatException exception) {
			return -1;
		}
	}

	private long parseLong(String raw) {
		try {
			return Long.parseLong(raw);
		} catch (NumberFormatException exception) {
			return -1;
		}
	}

	private String join(String[] args, int start) {
		StringBuilder builder = new StringBuilder();
		for (int index = start; index < args.length; index++) {
			if (builder.length() > 0) {
				builder.append(' ');
			}
			builder.append(args[index]);
		}
		return builder.toString();
	}

	private void usage(CommandSender sender) {
		sender.sendMessage("用法：/market [sell <单价> [数量|all] | buy <编号> [数量] | "
			+ "cancel <编号> | mine | search <关键词> | reload]");
	}

	@Override
	public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
		if (!command.getName().equalsIgnoreCase("market")) {
			return List.of();
		}
		if (args.length == 1) {
			String prefix = args[0].toLowerCase(Locale.ROOT);
			List<String> options = new ArrayList<>();
			for (String subcommand : SUBCOMMANDS) {
				if (subcommand.startsWith(prefix)
					&& (!subcommand.equals("reload") || sender.hasPermission("economy.market.admin"))) {
					options.add(subcommand);
				}
			}
			return options;
		}
		if (args.length == 2 && List.of("buy", "cancel").contains(args[0].toLowerCase(Locale.ROOT))) {
			String prefix = args[1];
			return service.listings().stream()
				.map(listing -> Long.toString(listing.id()))
				.filter(id -> id.startsWith(prefix))
				.toList();
		}
		if (args.length == 3 && args[0].equalsIgnoreCase("buy")) {
			return List.of("1", "16", "64");
		}
		if (args.length == 3 && args[0].equalsIgnoreCase("sell")) {
			return List.of("1", "16", "64", "all");
		}
		return List.of();
	}
}
