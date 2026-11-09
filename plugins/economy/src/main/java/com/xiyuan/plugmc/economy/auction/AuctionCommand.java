package com.xiyuan.plugmc.economy.auction;

import com.xiyuan.plugmc.economy.gui.AuctionGui;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class AuctionCommand implements CommandExecutor, TabCompleter {
	private static final List<String> SUBCOMMANDS = List.of(
		"create", "bid", "buy", "cancel", "list", "mine", "search", "reload"
	);
	private static final DateTimeFormatter TIME = DateTimeFormatter
		.ofPattern("MM-dd HH:mm")
		.withZone(ZoneId.systemDefault());

	private final AuctionService service;
	private final AuctionGui gui;

	public AuctionCommand(AuctionService service, AuctionGui gui) {
		this.service = service;
		this.gui = gui;
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (args.length == 0) {
			if (sender instanceof Player player) {
				gui.openList(player, 0);
			} else {
				list(sender, 1);
			}
			return true;
		}
		switch (args[0].toLowerCase(Locale.ROOT)) {
			case "mine" -> {
				Player player = requirePlayer(sender);
				if (player != null) {
					gui.openMine(player);
				}
				return true;
			}
			case "create" -> create(sender, args);
			case "bid" -> bid(sender, args);
			case "buy" -> buy(sender, args);
			case "cancel" -> cancel(sender, args);
			case "list" -> list(sender, args.length > 1 ? parseInt(args[1]) : 1);
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
		if (args.length < 2) {
			sender.sendMessage("用法：/auction create <起拍价> [一口价|-] [数量|all]");
			return;
		}
		BigDecimal start = parseMoney(sender, args[1]);
		if (start == null) {
			return;
		}
		BigDecimal buyout = BigDecimal.ZERO;
		int amountIndex = 2;
		if (args.length > 2 && !args[2].equalsIgnoreCase("all")) {
			if (!"-".equals(args[2])) {
				buyout = parseMoney(sender, args[2]);
				if (buyout == null) {
					return;
				}
			}
			amountIndex = 3;
		}
		boolean all = args.length <= amountIndex || args[amountIndex].equalsIgnoreCase("all");
		int amount = all ? 0 : parseInt(args[amountIndex]);
		if (!all && amount <= 0) {
			sender.sendMessage("数量必须是正整数，或使用 all。");
			return;
		}
		service.create(player, start, buyout, amount, all);
	}

	private void bid(CommandSender sender, String[] args) {
		Player player = requirePlayer(sender);
		if (player == null) {
			return;
		}
		if (args.length < 3) {
			sender.sendMessage("用法：/auction bid <编号> <金额>");
			return;
		}
		long id = parseId(sender, args[1]);
		BigDecimal amount = parseMoney(sender, args[2]);
		if (id > 0 && amount != null) {
			service.bid(player, id, amount);
		}
	}

	private void buy(CommandSender sender, String[] args) {
		Player player = requirePlayer(sender);
		if (player == null) {
			return;
		}
		if (args.length < 2) {
			sender.sendMessage("用法：/auction buy <编号>");
			return;
		}
		long id = parseId(sender, args[1]);
		if (id > 0) {
			service.buyout(player, id);
		}
	}

	private void cancel(CommandSender sender, String[] args) {
		Player player = requirePlayer(sender);
		if (player == null) {
			return;
		}
		if (args.length < 2) {
			sender.sendMessage("用法：/auction cancel <编号>");
			return;
		}
		long id = parseId(sender, args[1]);
		if (id > 0) {
			service.cancel(player, id);
		}
	}

	private void list(CommandSender sender, int page) {
		List<AuctionListing> listings = service.listings();
		if (listings.isEmpty()) {
			sender.sendMessage("拍卖行暂时没有进行中的拍卖。");
			return;
		}
		int pageSize = 8;
		int pageCount = Math.max(1, (listings.size() + pageSize - 1) / pageSize);
		int current = Math.max(1, Math.min(page, pageCount));
		sender.sendMessage("拍卖行（" + current + "/" + pageCount + "，共 " + listings.size() + " 件）");
		int start = (current - 1) * pageSize;
		for (int index = start; index < Math.min(start + pageSize, listings.size()); index++) {
			AuctionListing listing = listings.get(index);
			String currentBid = listing.hasBid()
				? "当前 " + service.format(listing.highestBid())
				: "起拍 " + service.format(listing.startingPrice());
			String buyout = listing.hasBuyout() ? "，一口价 " + service.format(listing.buyoutPrice()) : "";
			sender.sendMessage("#" + listing.id() + " " + listing.item().getType().name()
				+ " ×" + listing.item().getAmount() + " " + currentBid + buyout
				+ "，截止 " + TIME.format(Instant.ofEpochMilli(listing.expiresAt())));
		}
		if (current < pageCount) {
			sender.sendMessage("使用 /auction list " + (current + 1) + " 查看下一页。");
		}
	}

	private void mine(CommandSender sender) {
		Player player = requirePlayer(sender);
		if (player == null) {
			return;
		}
		List<AuctionListing> listings = service.mine(player);
		if (listings.isEmpty()) {
			player.sendMessage("你没有进行中的拍卖。");
			return;
		}
		player.sendMessage("你的拍卖：");
		for (AuctionListing listing : listings) {
			player.sendMessage("#" + listing.id() + " " + listing.item().getType().name()
				+ " ×" + listing.item().getAmount()
				+ (listing.hasBid() ? "，当前 " + service.format(listing.highestBid()) : "，暂无出价"));
		}
	}

	private void search(CommandSender sender, String[] args) {
		if (args.length < 2) {
			sender.sendMessage("用法：/auction search <关键词>");
			return;
		}
		List<AuctionListing> results = service.search(args[1]);
		if (results.isEmpty()) {
			sender.sendMessage("没有匹配的拍卖。");
			return;
		}
		for (AuctionListing listing : results.subList(0, Math.min(8, results.size()))) {
			sender.sendMessage("#" + listing.id() + " " + listing.item().getType().name()
				+ " ×" + listing.item().getAmount()
				+ (listing.hasBid() ? "，当前 " + service.format(listing.highestBid())
					: "，起拍 " + service.format(listing.startingPrice())));
		}
		if (results.size() > 8) {
			sender.sendMessage("还有 " + (results.size() - 8) + " 条结果，请使用更精确的关键词。");
		}
	}

	private void reload(CommandSender sender) {
		if (!sender.hasPermission("economy.auction.admin")) {
			sender.sendMessage("你没有权限重载拍卖行配置。");
			return;
		}
		service.reload();
		sender.sendMessage("拍卖行配置已重载。");
	}

	private void usage(CommandSender sender) {
		sender.sendMessage("用法：/auction [create <起拍价> [一口价|-] [数量|all] | bid <编号> <金额>"
			+ " | buy <编号> | cancel <编号> | list [页] | mine | search <关键词> | reload]");
	}

	private Player requirePlayer(CommandSender sender) {
		if (sender instanceof Player player) {
			if (!player.hasPermission("economy.auction.use")) {
				player.sendMessage("你没有权限使用拍卖行。");
				return null;
			}
			return player;
		}
		sender.sendMessage("只有玩家可以使用拍卖行。");
		return null;
	}

	private BigDecimal parseMoney(CommandSender sender, String raw) {
		try {
			return new BigDecimal(raw);
		} catch (NumberFormatException exception) {
			sender.sendMessage("金额无效：" + raw);
			return null;
		}
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
		if (args.length == 2 && (args[0].equalsIgnoreCase("bid")
			|| args[0].equalsIgnoreCase("buy")
			|| args[0].equalsIgnoreCase("cancel"))) {
			String prefix = args[1];
			List<String> ids = new ArrayList<>();
			List<AuctionListing> source = args[0].equalsIgnoreCase("cancel") && sender instanceof Player player
				? service.mine(player)
				: service.listings();
			for (AuctionListing listing : source) {
				String id = Long.toString(listing.id());
				if (id.startsWith(prefix)) {
					ids.add(id);
				}
			}
			return ids;
		}
		if (args.length == 3 && args[0].equalsIgnoreCase("create")) {
			return List.of("-", "all");
		}
		if (args.length == 4 && args[0].equalsIgnoreCase("create")) {
			return List.of("all");
		}
		return List.of();
	}
}
