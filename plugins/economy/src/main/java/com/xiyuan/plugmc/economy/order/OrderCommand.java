package com.xiyuan.plugmc.economy.order;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class OrderCommand implements CommandExecutor, TabCompleter {
	private static final List<String> SUBCOMMANDS = List.of(
		"create", "fill", "cancel", "list", "mine", "search", "reload"
	);

	private final OrderService service;

	public OrderCommand(OrderService service) {
		this.service = service;
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (args.length == 0) {
			list(sender, 1);
			return true;
		}
		switch (args[0].toLowerCase(Locale.ROOT)) {
			case "create" -> create(sender, args);
			case "fill" -> fill(sender, args);
			case "cancel" -> cancel(sender, args);
			case "list" -> list(sender, args.length > 1 ? parseInt(args[1]) : 1);
			case "mine" -> mine(sender);
			case "search" -> search(sender, args);
			case "reload" -> reload(sender);
			default -> usage(sender);
		}
		return true;
	}

	private void create(CommandSender sender, String[] args) {
		Player player = requirePlayer(sender);
		if (player == null) {
			return;
		}
		if (args.length < 3) {
			sender.sendMessage("用法：/order create <单价> <数量|all>");
			return;
		}
		BigDecimal price;
		try {
			price = new BigDecimal(args[1]);
		} catch (NumberFormatException exception) {
			sender.sendMessage("单价无效：" + args[1]);
			return;
		}
		boolean all = args[2].equalsIgnoreCase("all");
		int amount = all ? 0 : parseInt(args[2]);
		if (!all && amount <= 0) {
			sender.sendMessage("数量必须是正整数，或使用 all。");
			return;
		}
		service.create(player, price, amount, all);
	}

	private void fill(CommandSender sender, String[] args) {
		Player player = requirePlayer(sender);
		if (player == null) {
			return;
		}
		if (args.length < 2) {
			sender.sendMessage("用法：/order fill <编号> [数量|all]");
			return;
		}
		long id = parseId(sender, args[1]);
		if (id <= 0) {
			return;
		}
		boolean all = args.length < 3 || args[2].equalsIgnoreCase("all");
		int amount = all ? 0 : parseInt(args[2]);
		service.fill(player, id, amount, all);
	}

	private void cancel(CommandSender sender, String[] args) {
		Player player = requirePlayer(sender);
		if (player == null) {
			return;
		}
		if (args.length < 2) {
			sender.sendMessage("用法：/order cancel <编号>");
			return;
		}
		long id = parseId(sender, args[1]);
		if (id > 0) {
			service.cancel(player, id);
		}
	}

	private void list(CommandSender sender, int page) {
		List<BuyOrder> orders = service.orders();
		if (orders.isEmpty()) {
			sender.sendMessage("目前没有进行中的收购订单。");
			return;
		}
		int pageSize = 8;
		int pageCount = Math.max(1, (orders.size() + pageSize - 1) / pageSize);
		int current = Math.max(1, Math.min(page, pageCount));
		sender.sendMessage("收购订单（" + current + "/" + pageCount + "，共 " + orders.size() + " 条）");
		int start = (current - 1) * pageSize;
		for (int index = start; index < Math.min(start + pageSize, orders.size()); index++) {
			BuyOrder order = orders.get(index);
			sender.sendMessage("#" + order.id() + " " + order.template().getType().name()
				+ " ×" + order.remaining() + " @ " + service.format(order.unitPrice())
				+ " 由 " + order.buyerName());
		}
		if (current < pageCount) {
			sender.sendMessage("使用 /order list " + (current + 1) + " 查看下一页。");
		}
	}

	private void mine(CommandSender sender) {
		Player player = requirePlayer(sender);
		if (player == null) {
			return;
		}
		List<BuyOrder> orders = service.mine(player);
		if (orders.isEmpty()) {
			player.sendMessage("你没有进行中的收购订单。");
			return;
		}
		player.sendMessage("你的收购订单：");
		for (BuyOrder order : orders) {
			player.sendMessage("#" + order.id() + " " + order.template().getType().name()
				+ " ×" + order.remaining() + " @ " + service.format(order.unitPrice()));
		}
	}

	private void search(CommandSender sender, String[] args) {
		if (args.length < 2) {
			sender.sendMessage("用法：/order search <关键词>");
			return;
		}
		List<BuyOrder> results = service.search(args[1]);
		if (results.isEmpty()) {
			sender.sendMessage("没有匹配的收购订单。");
			return;
		}
		for (BuyOrder order : results.subList(0, Math.min(8, results.size()))) {
			sender.sendMessage("#" + order.id() + " " + order.template().getType().name()
				+ " ×" + order.remaining() + " @ " + service.format(order.unitPrice())
				+ " 由 " + order.buyerName());
		}
		if (results.size() > 8) {
			sender.sendMessage("还有 " + (results.size() - 8) + " 条结果，请使用更精确的关键词。");
		}
	}

	private void reload(CommandSender sender) {
		if (!sender.hasPermission("economy.order.admin")) {
			sender.sendMessage("你没有权限重载收购订单配置。");
			return;
		}
		service.reload();
		sender.sendMessage("收购订单配置已重载。");
	}

	private void usage(CommandSender sender) {
		sender.sendMessage("用法：/order [create <单价> <数量|all> | fill <编号> [数量|all]"
			+ " | cancel <编号> | list [页] | mine | search <关键词> | reload]");
	}

	private Player requirePlayer(CommandSender sender) {
		if (sender instanceof Player player) {
			if (!player.hasPermission("economy.order.use")) {
				player.sendMessage("你没有权限使用收购订单。");
				return null;
			}
			return player;
		}
		sender.sendMessage("只有玩家可以使用收购订单。");
		return null;
	}

	private long parseId(CommandSender sender, String raw) {
		try {
			return Long.parseLong(raw);
		} catch (NumberFormatException exception) {
			sender.sendMessage("编号无效：" + raw);
			return -1;
		}
	}

	private int parseInt(String raw) {
		try {
			return Integer.parseInt(raw);
		} catch (NumberFormatException exception) {
			return -1;
		}
	}

	@Override
	public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
		if (args.length == 1) {
			String prefix = args[0].toLowerCase(Locale.ROOT);
			return SUBCOMMANDS.stream().filter(option -> option.startsWith(prefix)).toList();
		}
		if (args.length == 2 && args[0].equalsIgnoreCase("fill")) {
			String prefix = args[1];
			List<String> ids = new ArrayList<>();
			for (BuyOrder order : service.orders()) {
				String id = Long.toString(order.id());
				if (id.startsWith(prefix)) {
					ids.add(id);
				}
			}
			return ids;
		}
		if (args.length == 2 && args[0].equalsIgnoreCase("cancel") && sender instanceof Player player) {
			String prefix = args[1];
			List<String> ids = new ArrayList<>();
			for (BuyOrder order : service.mine(player)) {
				String id = Long.toString(order.id());
				if (id.startsWith(prefix)) {
					ids.add(id);
				}
			}
			return ids;
		}
		if (args.length == 3 && (args[0].equalsIgnoreCase("fill") || args[0].equalsIgnoreCase("create"))) {
			return List.of("all");
		}
		return List.of();
	}
}
