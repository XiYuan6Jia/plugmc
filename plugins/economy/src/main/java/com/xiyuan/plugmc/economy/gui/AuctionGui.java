package com.xiyuan.plugmc.economy.gui;

import com.xiyuan.plugmc.economy.auction.AuctionListing;
import com.xiyuan.plugmc.economy.auction.AuctionService;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

public final class AuctionGui {
	private static final DateTimeFormatter TIME = DateTimeFormatter
		.ofPattern("MM-dd HH:mm")
		.withZone(ZoneId.systemDefault());
	private static final int PAGE_SIZE = 45;

	private final AuctionService service;
	private final ChatPrompt chatPrompt;

	public AuctionGui(AuctionService service, ChatPrompt chatPrompt) {
		this.service = service;
		this.chatPrompt = chatPrompt;
	}

	public void openList(Player player, int page) {
		List<AuctionListing> listings = service.listings();
		int pageCount = Math.max(1, (listings.size() + PAGE_SIZE - 1) / PAGE_SIZE);
		int current = Math.max(0, Math.min(page, pageCount - 1));
		EconomyMenuHolder holder = new EconomyMenuHolder(
			6,
			"&8拍卖行 &7(" + (current + 1) + "/" + pageCount + ")"
		);
		holder.fill(filler());

		if (listings.isEmpty()) {
			holder.set(22, item(Material.CHEST, "&7暂无拍卖", List.of("&8点击下方按钮上拍主手物品")), null);
		} else {
			int start = current * PAGE_SIZE;
			for (int index = 0; index < PAGE_SIZE && start + index < listings.size(); index++) {
				AuctionListing listing = listings.get(start + index);
				holder.set(index, icon(listing), p -> openDetail(p, listing.id(), page));
			}
		}

		if (current > 0) {
			holder.set(45, item(Material.ARROW, "&e上一页", List.of()),
				p -> openList(p, current - 1));
		}
		holder.set(49, GuiNavigation.backButton(), GuiNavigation::back);
		holder.set(50, item(Material.EMERALD, "&a上拍主手物品", List.of(
			"&7把主手物品放到拍卖行",
			"&8点击后输入起拍价"
		)), this::startCreate);
		if (current + 1 < pageCount) {
			holder.set(53, item(Material.ARROW, "&e下一页", List.of()),
				p -> openList(p, current + 1));
		}
		player.openInventory(holder.getInventory());
	}

	public void openMine(Player player) {
		List<AuctionListing> mine = service.mine(player);
		if (mine.isEmpty()) {
			player.sendMessage("你没有进行中的拍卖。");
			openList(player, 0);
			return;
		}
		openDetail(player, mine.get(0).id(), 0);
	}

	private void openDetail(Player player, long id, int backPage) {
		AuctionListing listing = service.listing(id);
		if (listing == null || !listing.active()) {
			player.sendMessage("该拍卖已结束。");
			openList(player, backPage);
			return;
		}

		EconomyMenuHolder holder = new EconomyMenuHolder(6, "&8拍卖 #" + id);
		holder.fill(filler());
		holder.set(13, icon(listing), null);
		holder.set(4, item(Material.GOLD_INGOT, "&6余额", List.of(
			"&e" + service.format(service.balance(player))
		)), null);

		boolean owner = listing.seller().equals(player.getUniqueId());
		if (!owner) {
			BigDecimal nextIncrement = service.config().minIncrementFor(
				listing.highestBid(),
				service.ledgerDecimals()
			);
			holder.set(20, item(Material.LIME_STAINED_GLASS_PANE, "&a出价", List.of(
				"&7当前：" + (listing.hasBid()
					? "&e" + service.format(listing.highestBid())
					: "&e无人出价"),
				"&7最低加价：&f" + service.format(nextIncrement),
				"&8点击后输入金额"
			)), p -> promptBid(p, id, backPage));
			if (listing.hasBuyout()) {
				holder.set(22, item(Material.GOLD_BLOCK, "&6一口价购买", List.of(
					"&7价格：&e" + service.format(listing.buyoutPrice())
				)), p -> {
					service.buyout(p, id);
					openList(p, backPage);
				});
			}
		} else if (!listing.hasBid()) {
			holder.set(31, item(Material.RED_STAINED_GLASS_PANE, "&c撤拍", List.of(
				"&7物品会退回邮箱"
			)), p -> {
				service.cancel(p, id);
				openList(p, backPage);
			});
		}

		holder.set(45, item(Material.ARROW, "&e返回拍卖行", List.of()),
			p -> openList(p, backPage));
		holder.set(49, item(Material.BARRIER, "&c关闭", List.of()), Player::closeInventory);
		player.openInventory(holder.getInventory());
	}

	private void promptBid(Player player, long id, int backPage) {
		AuctionListing listing = service.listing(id);
		if (listing == null || !listing.active()) {
			player.sendMessage("该拍卖已结束。");
			return;
		}
		BigDecimal minimum = listing.hasBid()
			? listing.highestBid().add(service.config().minIncrementFor(
				listing.highestBid(),
				service.ledgerDecimals()
			))
			: listing.startingPrice();
		chatPrompt.request(player, "请输入出价（至少 " + service.format(minimum) + "）：", input -> {
			BigDecimal amount;
			try {
				amount = new BigDecimal(input);
			} catch (NumberFormatException exception) {
				player.sendMessage("金额无效：" + input);
				return;
			}
			service.bid(player, id, amount);
			openDetail(player, id, backPage);
		});
	}

	private void startCreate(Player player) {
		if (player.getInventory().getItemInMainHand().getType().isAir()) {
			player.sendMessage("请先把要拍卖的物品拿在主手。");
			return;
		}
		chatPrompt.request(player, "请输入起拍价：", startInput -> {
			BigDecimal start;
			try {
				start = new BigDecimal(startInput);
			} catch (NumberFormatException exception) {
				player.sendMessage("起拍价无效：" + startInput);
				return;
			}
			chatPrompt.request(player, "请输入一口价（输入 - 表示不设置）：", buyoutInput -> {
				BigDecimal buyout = BigDecimal.ZERO;
				if (!"-".equals(buyoutInput)) {
					try {
						buyout = new BigDecimal(buyoutInput);
					} catch (NumberFormatException exception) {
						player.sendMessage("一口价无效：" + buyoutInput);
						return;
					}
				}
				openCreateQuantity(player, start, buyout);
			});
		});
	}

	private void openCreateQuantity(Player player, BigDecimal start, BigDecimal buyout) {
		ItemStack hand = player.getInventory().getItemInMainHand();
		if (hand.getType().isAir()) {
			player.sendMessage("主手已经空了，上拍取消。");
			openList(player, 0);
			return;
		}
		int available = hand.getAmount();
		EconomyMenuHolder holder = new EconomyMenuHolder(3, "&8上拍数量");
		holder.fill(filler());
		holder.set(4, item(Material.PAPER, "&f" + hand.getType().name(), List.of(
			"&7起拍价：&e" + service.format(start),
			"&7一口价：" + (buyout.signum() > 0 ? "&e" + service.format(buyout) : "&f无"),
			"&7上拍费：&e" + service.format(service.config().listingFee(start, service.ledgerDecimals())),
			"&7主手数量：&f" + available
		)), null);
		holder.set(10, quantityButton(1, start, available), p -> create(p, start, buyout, 1, false));
		holder.set(12, quantityButton(16, start, available), p -> create(p, start, buyout, 16, false));
		holder.set(14, quantityButton(64, start, available), p -> create(p, start, buyout, 64, false));
		holder.set(16, item(Material.CHEST, "&a全部上拍 &7(" + available + ")", List.of()),
			p -> create(p, start, buyout, available, true));
		holder.set(22, item(Material.ARROW, "&e返回拍卖行", List.of()), p -> openList(p, 0));
		player.openInventory(holder.getInventory());
	}

	private void create(Player player, BigDecimal start, BigDecimal buyout, int amount, boolean all) {
		player.closeInventory();
		if (service.create(player, start, buyout, amount, all)) {
			openList(player, 0);
		}
	}

	private ItemStack quantityButton(int amount, BigDecimal start, int available) {
		int actual = Math.min(amount, available);
		return item(Material.LIME_STAINED_GLASS_PANE, "&a上拍 " + actual + " 个", List.of(
			"&7起拍价：&e" + service.format(start)
		));
	}

	private ItemStack icon(AuctionListing listing) {
		ItemStack icon = listing.item().clone();
		ItemMeta meta = icon.getItemMeta();
		if (meta != null) {
			meta.displayName(MenuText.component("&f#" + listing.id() + " &7· &e"
				+ listing.item().getType().name()));
			meta.lore(MenuText.components(List.of(
				"&7数量：&f" + listing.item().getAmount(),
				"&7卖家：&f" + (listing.sellerName().isBlank() ? "未知" : listing.sellerName()),
				listing.hasBid()
					? "&7当前出价：&e" + service.format(listing.highestBid())
					: "&7起拍价：&e" + service.format(listing.startingPrice()),
				listing.hasBuyout() ? "&7一口价：&e" + service.format(listing.buyoutPrice()) : "&7一口价：&8无",
				"&7截止：&f" + TIME.format(Instant.ofEpochMilli(listing.expiresAt()))
					+ (listing.extensions() > 0 ? " &8(已延时 " + listing.extensions() + " 次)" : ""),
				"&8点击查看详情"
			)));
			icon.setItemMeta(meta);
		}
		return icon;
	}

	private ItemStack item(Material material, String name, List<String> lore) {
		ItemStack stack = new ItemStack(material);
		ItemMeta meta = stack.getItemMeta();
		if (meta != null) {
			meta.displayName(MenuText.component(name));
			if (!lore.isEmpty()) {
				meta.lore(MenuText.components(lore));
			}
			stack.setItemMeta(meta);
		}
		return stack;
	}

	private ItemStack filler() {
		return item(Material.GRAY_STAINED_GLASS_PANE, "&8 ", List.of());
	}
}
