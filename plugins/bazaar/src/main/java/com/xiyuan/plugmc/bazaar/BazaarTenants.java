package com.xiyuan.plugmc.bazaar;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.profile.PlayerProfile;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 把三类交易归并成「每个玩家一组」。
 *
 * <p><b>为什么一玩家一摊</b>：需求是「每个玩家都只在发布后占用一个摊位」，
 * 而一个玩家可以同时挂着若干个挂单/拍卖/求购。所以分配单位是人不是交易，
 * 一个摊位的招牌要能列出这个人的<b>全部</b>在售项 —— 这里就把它们按人聚合好。</p>
 *
 * <p>排序是固定的：市场 → 拍卖 → 求购，同类内按编号升序。
 * 固定排序是为了让招牌内容在两次刷新之间不会自己跳顺序。</p>
 */
public record BazaarTenants(List<Tenant> tenants) {
	public BazaarTenants {
		tenants = List.copyOf(tenants);
	}

	public static BazaarTenants empty() {
		return new BazaarTenants(List.of());
	}

	public boolean isEmpty() {
		return tenants.isEmpty();
	}

	/** 按玩家名找租户（招牌/点击时用）。 */
	public Tenant byName(String name) {
		for (Tenant tenant : tenants) {
			if (tenant.name().equalsIgnoreCase(name)) {
				return tenant;
			}
		}
		return null;
	}

	/** 这一组交易的招牌行，交给 {@link BazaarText} 排版。 */
	public List<String> describe(Tenant tenant) {
		List<String> lines = new ArrayList<>();
		for (BazaarDeal deal : tenant.deals()) {
			lines.add(deal.kind().label() + " " + deal.itemLine() + " · " + deal.priceLine());
		}
		return lines;
	}

	/** 一个玩家（= 一个摊位）名下的全部在售交易。 */
	public record Tenant(String name, List<BazaarDeal> deals) {
		public Tenant {
			deals = List.copyOf(deals);
		}

		/** 摊主在线时返回 Player，用于取皮肤；离线返回 null。 */
		public Player online() {
			return Bukkit.getPlayerExact(name);
		}

		/** 招牌第一行：种类概览，如「售卖 2 · 拍卖 1」。 */
		public String kindSummary() {
			int market = 0;
			int auction = 0;
			int order = 0;
			for (BazaarDeal deal : deals) {
				switch (deal.kind()) {
					case MARKET -> market++;
					case AUCTION -> auction++;
					case ORDER -> order++;
				}
			}
			List<String> parts = new ArrayList<>();
			if (market > 0) {
				parts.add("售卖 " + market);
			}
			if (auction > 0) {
				parts.add("拍卖 " + auction);
			}
			if (order > 0) {
				parts.add("求购 " + order);
			}
			return String.join(" · ", parts);
		}
	}

	/**
	 * 从经济系统的三类列表构造。
	 *
	 * <p>只收 {@code active()} 的项：拍卖结算后状态变 SOLD/EXPIRED、挂单卖光变 SOLD、
	 * 收购单被填满变 FILLED —— 这些都不该再占摊位。当某个玩家的全部交易都结束后，
	 * 他就不再出现在结果里，摊位自然被释放。</p>
	 */
	public static BazaarTenants from(
		List<com.xiyuan.plugmc.economy.market.MarketListing> markets,
		List<com.xiyuan.plugmc.economy.auction.AuctionListing> auctions,
		List<com.xiyuan.plugmc.economy.order.BuyOrder> orders
	) {
		java.util.Map<String, List<BazaarDeal>> byPlayer = new java.util.LinkedHashMap<>();
		java.util.Map<String, String> displayNames = new java.util.HashMap<>();

		for (com.xiyuan.plugmc.economy.market.MarketListing listing : markets) {
			if (!listing.active()) {
				continue;
			}
			String key = keyOf(listing.seller(), listing.sellerName());
			displayNames.putIfAbsent(key, nameOf(listing.sellerName(), listing.seller()));
			byPlayer.computeIfAbsent(key, ignored -> new ArrayList<>()).add(new BazaarDeal(
				BazaarDeal.Kind.MARKET,
				listing.id(),
				itemName(listing.item()),
				listing.amount(),
				listing.unitPrice().toPlainString(),
				nameOf(listing.sellerName(), listing.seller()),
				listing.item()
			));
		}

		for (com.xiyuan.plugmc.economy.auction.AuctionListing listing : auctions) {
			if (!listing.active()) {
				continue;
			}
			String key = keyOf(listing.seller(), listing.sellerName());
			displayNames.putIfAbsent(key, nameOf(listing.sellerName(), listing.seller()));
			String price = listing.hasBuyout()
				? "一口价 " + listing.buyoutPrice().toPlainString()
				: "起拍 " + listing.startingPrice().toPlainString();
			if (listing.hasBid()) {
				price = price + " · 已出价 " + listing.highestBid().toPlainString();
			}
			byPlayer.computeIfAbsent(key, ignored -> new ArrayList<>()).add(new BazaarDeal(
				BazaarDeal.Kind.AUCTION,
				listing.id(),
				itemName(listing.item()),
				listing.item().getAmount(),
				price,
				nameOf(listing.sellerName(), listing.seller()),
				listing.item()
			));
		}

		for (com.xiyuan.plugmc.economy.order.BuyOrder order : orders) {
			if (!order.active()) {
				continue;
			}
			String key = keyOf(order.buyer(), order.buyerName());
			displayNames.putIfAbsent(key, nameOf(order.buyerName(), order.buyer()));
			byPlayer.computeIfAbsent(key, ignored -> new ArrayList<>()).add(new BazaarDeal(
				BazaarDeal.Kind.ORDER,
				order.id(),
				itemName(order.template()),
				order.remaining(),
				"单价 " + order.unitPrice().toPlainString(),
				nameOf(order.buyerName(), order.buyer()),
				order.template()
			));
		}

		List<Tenant> result = new ArrayList<>();
		for (var entry : byPlayer.entrySet()) {
			String name = displayNames.getOrDefault(entry.getKey(), entry.getKey());
			result.add(new Tenant(name, entry.getValue()));
		}
		// 名字排序，保证同一批人在两次刷新间的摊位顺序不抖动
		result.sort(java.util.Comparator.comparing(Tenant::name, String.CASE_INSENSITIVE_ORDER));
		return new BazaarTenants(result);
	}

	private static String keyOf(java.util.UUID id, String name) {
		if (id != null) {
			return id.toString();
		}
		return "name:" + (name == null ? "" : name.toLowerCase(Locale.ROOT));
	}

	private static String nameOf(String name, java.util.UUID id) {
		if (name != null && !name.isBlank()) {
			return name;
		}
		if (id == null) {
			return "?";
		}
		String cached = Bukkit.getOfflinePlayer(id).getName();
		return cached == null ? id.toString().substring(0, 8) : cached;
	}

	private static String itemName(org.bukkit.inventory.ItemStack stack) {
		if (stack == null) {
			return "?";
		}
		String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
			.serialize(stack.displayName());
		if (plain != null && !plain.isBlank()) {
			return plain;
		}
		String key = stack.getType().getKey().getKey();
		return key.replace('_', ' ');
	}

	/** 玩家在某摊位上的稳定索引：用名字的哈希取模，换人不换位置感（同一人重开摊位也回到原处）。 */
	public static int stableIndex(String name, int size) {
		if (size <= 0) {
			return 0;
		}
		int hash = name == null ? 0 : name.toLowerCase(Locale.ROOT).hashCode();
		return Math.floorMod(hash, size);
	}

	/** 供外部取皮肤用：名字 → 在线玩家。 */
	public static PlayerProfile profileOf(String name) {
		Player online = Bukkit.getPlayerExact(name);
		if (online != null) {
			return online.getPlayerProfile();
		}
		return Bukkit.createProfile(name);
	}
}
