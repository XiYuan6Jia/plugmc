package com.xiyuan.plugmc.economy.shop;

import com.xiyuan.plugmc.economy.gui.ShopGui;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class ShopCommand implements CommandExecutor, TabCompleter {
	private static final List<String> SUBCOMMANDS = List.of(
		"buy", "sell", "sellall", "price", "search", "reload"
	);

	private final ShopService service;
	private final ShopGui gui;

	public ShopCommand(ShopService service, ShopGui gui) {
		this.service = service;
		this.gui = gui;
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (!command.getName().equalsIgnoreCase("shop")) {
			return false;
		}
		if (args.length == 0) {
			Player player = requirePlayer(sender);
			if (player != null) {
				gui.openMain(player);
			}
			return true;
		}

		String action = args[0].toLowerCase(Locale.ROOT);
		switch (action) {
			case "buy" -> buy(sender, args);
			case "sell" -> sell(sender, args);
			case "sellall" -> {
				Player player = requirePlayer(sender);
				if (player != null) {
					gui.openSell(player);
				}
			}
			case "price" -> {
				if (args.length >= 2) {
					price(sender, args[1]);
					return true;
				}
				Player player = requirePlayer(sender);
				if (player != null) {
					service.price(player);
				}
			}
			case "search" -> search(sender, args);
			case "reload" -> reload(sender);
			default -> usage(sender);
		}
		return true;
	}

	private void buy(CommandSender sender, String[] args) {
		Player player = requirePlayer(sender);
		if (player == null) {
			return;
		}
		if (args.length < 2) {
			sender.sendMessage("用法：/shop buy <商品> [数量]");
			return;
		}
		Optional<ShopItem> item = service.find(args[1]);
		if (item.isEmpty()) {
			sender.sendMessage("找不到商品：" + args[1]);
			return;
		}
		int amount = args.length >= 3 ? parseInt(args[2]) : 1;
		if (amount <= 0) {
			sender.sendMessage("数量必须是正整数。");
			return;
		}
		service.buy(player, item.get(), amount);
	}

	private void sell(CommandSender sender, String[] args) {
		Player player = requirePlayer(sender);
		if (player == null) {
			return;
		}
		if (args.length >= 2 && args[1].equalsIgnoreCase("all")) {
			service.sell(player, 0, true);
			return;
		}
		int amount = args.length >= 2 ? parseInt(args[1]) : 1;
		if (amount <= 0) {
			sender.sendMessage("数量必须是正整数，或使用 all。");
			return;
		}
		service.sell(player, amount, false);
	}

	private void search(CommandSender sender, String[] args) {
		Player player = requirePlayer(sender);
		if (player == null) {
			return;
		}
		if (args.length < 2) {
			sender.sendMessage("用法：/shop search <关键词>");
			return;
		}
		gui.openSearch(player, join(args, 1), 0);
	}

	private void reload(CommandSender sender) {
		if (!sender.hasPermission("economy.shop.admin")) {
			sender.sendMessage("你没有权限重载商店配置。");
			return;
		}
		try {
			service.reload();
			sender.sendMessage("商店配置已重载。");
		} catch (RuntimeException exception) {
			sender.sendMessage("商店配置重载失败：" + exception.getMessage());
		}
	}

	private void price(CommandSender sender, String id) {
		Optional<ShopItem> item = service.find(id);
		if (item.isEmpty()) {
			sender.sendMessage("找不到商品：" + id);
			return;
		}
		sender.sendMessage("商品 " + item.get().id()
			+ "：买价 " + service.format(item.get().buyPrice())
			+ " / 件，回收价 " + service.format(item.get().sellPrice()) + " / 件。");
	}

	private Player requirePlayer(CommandSender sender) {
		if (sender instanceof Player player) {
			return player;
		}
		sender.sendMessage("只有玩家可以执行这个商店操作。");
		return null;
	}

	private int parseInt(String raw) {
		try {
			return Integer.parseInt(raw);
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
		sender.sendMessage("用法：/shop [buy <商品> [数量] | sell [数量|all] | sellall | "
			+ "price [商品] | search <关键词> | reload]");
	}

	@Override
	public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
		if (!command.getName().equalsIgnoreCase("shop")) {
			return List.of();
		}
		if (args.length == 1) {
			String prefix = args[0].toLowerCase(Locale.ROOT);
			List<String> options = new ArrayList<>();
			for (String subcommand : SUBCOMMANDS) {
				if (subcommand.startsWith(prefix)
					&& (!subcommand.equals("reload") || sender.hasPermission("economy.shop.admin"))) {
					options.add(subcommand);
				}
			}
			return options;
		}
		if (args.length == 2 && args[0].equalsIgnoreCase("buy")) {
			String prefix = args[1].toLowerCase(Locale.ROOT);
			return service.goods().items().stream()
				.map(ShopItem::id)
				.filter(id -> id.toLowerCase(Locale.ROOT).startsWith(prefix))
				.sorted()
				.toList();
		}
		if (args.length == 2 && args[0].equalsIgnoreCase("price")) {
			String prefix = args[1].toLowerCase(Locale.ROOT);
			return service.goods().items().stream()
				.map(ShopItem::id)
				.filter(id -> id.toLowerCase(Locale.ROOT).startsWith(prefix))
				.sorted()
				.toList();
		}
		if (args.length == 2 && args[0].equalsIgnoreCase("sell")) {
			String prefix = args[1].toLowerCase(Locale.ROOT);
			return List.of("1", "16", "64", "all").stream()
				.filter(option -> option.startsWith(prefix))
				.toList();
		}
		return List.of();
	}
}
