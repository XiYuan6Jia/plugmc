package com.xiyuan.plugmc.economy.gui;

import com.xiyuan.plugmc.economy.market.MarketListing;
import com.xiyuan.plugmc.economy.market.MarketService;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

public final class MarketGui {
	private static final DateTimeFormatter TIME = DateTimeFormatter
		.ofPattern("MM-dd HH:mm")
		.withZone(ZoneId.systemDefault());
	private static final int PAGE_SIZE = 45;

	private final MarketService service;

	public MarketGui(MarketService service) {
		this.service = service;
	}

	public void openMain(Player player) {
		openList(player, "&8玩家市场", service.listings(), 0, "");
	}

	public void openSearch(Player player, String query) {
		openList(player, "&8市场搜索：" + query, service.search(query), 0, query);
	}

	public void openOwn(Player player) {
		List<MarketListing> own = service.listings().stream()
			.filter(listing -> listing.seller().equals(player.getUniqueId()))
			.toList();
		openList(player, "&8我的挂单", own, 0, "");
	}

	private void openList(
		Player player,
		String title,
		List<MarketListing> listings,
		int page,
		String query
	) {
		int pageCount = Math.max(1, (listings.size() + PAGE_SIZE - 1) / PAGE_SIZE);
		int current = Math.max(0, Math.min(page, pageCount - 1));
		EconomyMenuHolder holder = new EconomyMenuHolder(
			6,
			title + " &7(" + (current + 1) + "/" + pageCount + ")"
		);
		holder.fill(filler());

		if (listings.isEmpty()) {
			holder.set(22, button(Material.CHEST, "&7暂无挂单", List.of("&8使用 /market sell <单价> 上架")), null);
		} else {
			int start = current * PAGE_SIZE;
			for (int index = 0; index < PAGE_SIZE && start + index < listings.size(); index++) {
				MarketListing listing = listings.get(start + index);
				holder.set(index, icon(listing), p ->
					openDetail(p, listing.id(), title, listings, current, query)
				);
			}
		}

		if (current > 0) {
			holder.set(45, button(Material.ARROW, "&e上一页", List.of()), p ->
				openList(p, title, listings, current - 1, query)
			);
		}
		holder.set(48, GuiNavigation.backButton(), GuiNavigation::back);
		holder.set(49, button(Material.BARRIER, "&c关闭", List.of()), Player::closeInventory);
		if (current + 1 < pageCount) {
			holder.set(53, button(Material.ARROW, "&e下一页", List.of()), p ->
				openList(p, title, listings, current + 1, query)
			);
		}
		player.openInventory(holder.getInventory());
	}

	private void openDetail(
		Player player,
		long id,
		String title,
		List<MarketListing> backList,
		int page,
		String query
	) {
		MarketListing listing = service.listing(id);
		if (listing == null || !listing.active()) {
			player.sendMessage("该挂单已结束。");
			openList(player, title, backList, page, query);
			return;
		}

		EconomyMenuHolder holder = new EconomyMenuHolder(5, "&8挂单 #" + id);
		holder.fill(filler());
		holder.set(13, icon(listing), null);
		holder.set(4, button(Material.GOLD_INGOT, "&6余额", List.of(
			"&e" + service.format(service.balance(player))
		)), null);

		holder.set(20, buyButton(listing, 1), p -> {
			service.buy(p, id, 1);
			openDetail(p, id, title, backList, page, query);
		});
		holder.set(21, buyButton(listing, 16), p -> {
			service.buy(p, id, 16);
			openDetail(p, id, title, backList, page, query);
		});
		holder.set(22, buyButton(listing, 64), p -> {
			service.buy(p, id, 64);
			openDetail(p, id, title, backList, page, query);
		});
		holder.set(23, buyButton(listing, listing.amount()), p -> {
			service.buy(p, id, listing.amount());
			openDetail(p, id, title, backList, page, query);
		});

		if (listing.seller().equals(player.getUniqueId())) {
			holder.set(31, button(Material.RED_STAINED_GLASS_PANE, "&c下架挂单", List.of(
				"&7商品会退回邮箱"
			)), p -> {
				service.cancel(p, id);
				openList(p, title, backList, page, query);
			});
		}

		holder.set(36, button(Material.ARROW, "&e返回", List.of()), p ->
			openList(p, title, backList, page, query)
		);
		holder.set(44, button(Material.BARRIER, "&c关闭", List.of()), Player::closeInventory);
		player.openInventory(holder.getInventory());
	}

	private ItemStack icon(MarketListing listing) {
		ItemStack icon = listing.item().clone();
		ItemMeta meta = icon.getItemMeta();
		if (meta != null) {
			meta.displayName(MenuText.component("&f#" + listing.id() + " &7· &e"
				+ listing.item().getType().name()));
			meta.lore(MenuText.components(List.of(
				"&7单价：&e" + service.format(listing.unitPrice()),
				"&7数量：&f" + listing.amount(),
				"&7卖家：&f" + (listing.sellerName().isBlank() ? "未知" : listing.sellerName()),
				"&7过期：&f" + TIME.format(Instant.ofEpochMilli(listing.expiresAt())),
				"&8点击查看详情"
			)));
			icon.setItemMeta(meta);
		}
		return icon;
	}

	private ItemStack buyButton(MarketListing listing, int amount) {
		int actual = Math.min(amount, listing.amount());
		BigDecimal total = listing.unitPrice().multiply(BigDecimal.valueOf(actual));
		return button(Material.LIME_STAINED_GLASS_PANE, "&a购买 " + actual + " 个", List.of(
			"&7花费：&e" + service.format(total)
		));
	}

	private ItemStack button(Material material, String name, List<String> lore) {
		ItemStack item = new ItemStack(material);
		ItemMeta meta = item.getItemMeta();
		if (meta != null) {
			meta.displayName(MenuText.component(name));
			if (!lore.isEmpty()) {
				meta.lore(MenuText.components(lore));
			}
			item.setItemMeta(meta);
		}
		return item;
	}

	private ItemStack filler() {
		return button(Material.GRAY_STAINED_GLASS_PANE, "&8 ", List.of());
	}
}
