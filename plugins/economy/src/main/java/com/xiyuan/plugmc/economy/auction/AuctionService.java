package com.xiyuan.plugmc.economy.auction;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.xiyuan.plugmc.economy.internal.EconomyStore;
import com.xiyuan.plugmc.economy.internal.ItemCodec;
import com.xiyuan.plugmc.economy.internal.JournalRecord;
import com.xiyuan.plugmc.economy.internal.LedgerService;
import com.xiyuan.plugmc.economy.internal.Money;
import com.xiyuan.plugmc.economy.internal.Settlement;
import com.xiyuan.plugmc.economy.mail.MailService;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 拍卖行：卖家托管物品，买家竞价或一口价；资金在 {@link LedgerService#ESCROW_AUCTION} 托管，
 * 到期后物品发给最高出价者（或退回卖家），成交款扣除税费后支付卖家。
 */
public final class AuctionService {
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

	private final JavaPlugin plugin;
	private final LedgerService ledger;
	private final EconomyStore store;
	private final MailService mail;
	private volatile AuctionConfig config;

	public AuctionService(
		JavaPlugin plugin,
		LedgerService ledger,
		EconomyStore store,
		MailService mail,
		AuctionConfig config
	) {
		this.plugin = plugin;
		this.ledger = ledger;
		this.store = store;
		this.mail = mail;
		this.config = config;
	}

	public AuctionConfig config() {
		return config;
	}

	public void reload() {
		plugin.reloadConfig();
		this.config = AuctionConfig.load(plugin.getConfig(), ledger.decimalPlaces());
		plugin.getLogger().info("拍卖行配置已重载：槽位 " + config.slots()
			+ "，最小加价 " + config.minIncrement().toPlainString());
	}

	public BukkitTask startSettlementTask() {
		return plugin.getServer().getScheduler().runTaskTimer(plugin, this::settleExpired, 20L * 20L, 20L * 20L);
	}

	public List<AuctionListing> listings() {
		long now = System.currentTimeMillis();
		List<AuctionListing> result = new ArrayList<>();
		for (EconomyStore.AuctionDto dto : store.auctions().values()) {
			if (!"ACTIVE".equals(dto.status)) {
				continue;
			}
			try {
				AuctionListing listing = AuctionListing.fromDto(dto);
				if (!listing.expired(now)) {
					result.add(listing);
				}
			} catch (RuntimeException exception) {
				plugin.getLogger().warning("拍卖无法读取 " + dto.id + "：" + exception.getMessage());
			}
		}
		result.sort(Comparator.comparingLong(AuctionListing::expiresAt));
		return result;
	}

	public AuctionListing listing(long id) {
		EconomyStore.AuctionDto dto = store.auction(Long.toString(id));
		if (dto == null) {
			return null;
		}
		try {
			return AuctionListing.fromDto(dto);
		} catch (RuntimeException exception) {
			plugin.getLogger().warning("拍卖无法读取 " + id + "：" + exception.getMessage());
			return null;
		}
	}

	public List<AuctionListing> mine(Player player) {
		return listings().stream()
			.filter(listing -> listing.seller().equals(player.getUniqueId()))
			.toList();
	}

	public List<AuctionListing> search(String query) {
		String needle = query == null ? "" : query.toLowerCase(Locale.ROOT).trim();
		List<AuctionListing> all = listings();
		if (needle.isEmpty()) {
			return all;
		}
		return all.stream()
			.filter(listing -> listing.item().getType().name().toLowerCase(Locale.ROOT).contains(needle)
				|| listing.sellerName().toLowerCase(Locale.ROOT).contains(needle)
				|| Long.toString(listing.id()).contains(needle))
			.toList();
	}

	public BigDecimal balance(Player player) {
		return ledger.balance(player.getUniqueId());
	}

	public String format(BigDecimal amount) {
		return ledger.format(amount);
	}

	public int usedSlots(Player player) {
		return (int) store.auctions().values().stream()
			.filter(dto -> player.getUniqueId().toString().equals(dto.seller))
			.filter(dto -> "ACTIVE".equals(dto.status))
			.count();
	}

	public boolean create(Player player, BigDecimal startingPrice, BigDecimal buyoutPrice, int requestedAmount, boolean all) {
		if (!config.enabled()) {
			player.sendMessage("拍卖行暂未开放。");
			return false;
		}
		ItemStack hand = player.getInventory().getItemInMainHand();
		if (hand.getType().isAir() || hand.getAmount() <= 0) {
			player.sendMessage("请把要拍卖的物品拿在主手。");
			return false;
		}
		if (usedSlots(player) >= config.slots()) {
			player.sendMessage("你的拍卖槽位已满（" + config.slots() + " 个）。");
			return false;
		}

		BigDecimal start;
		try {
			start = Money.positive(startingPrice, ledger.decimalPlaces());
		} catch (IllegalArgumentException exception) {
			player.sendMessage("起拍价无效：" + exception.getMessage());
			return false;
		}
		BigDecimal buyout = Money.zero(ledger.decimalPlaces());
		if (buyoutPrice != null && buyoutPrice.signum() > 0) {
			try {
				buyout = Money.positive(buyoutPrice, ledger.decimalPlaces());
			} catch (IllegalArgumentException exception) {
				player.sendMessage("一口价无效：" + exception.getMessage());
				return false;
			}
			if (buyout.compareTo(start) <= 0) {
				player.sendMessage("一口价必须高于起拍价。");
				return false;
			}
		}

		int amount = all ? hand.getAmount() : Math.min(Math.max(1, requestedAmount), hand.getAmount());
		ItemStack escrow = hand.clone();
		escrow.setAmount(amount);

		long now = System.currentTimeMillis();
		long id = store.nextAuctionId();
		AuctionListing listing = new AuctionListing(
			id,
			player.getUniqueId(),
			player.getName(),
			escrow,
			start,
			buyout,
			null,
			"",
			Money.zero(ledger.decimalPlaces()),
			now,
			now + config.durationHours() * 60L * 60L * 1000L,
			AuctionListing.Status.ACTIVE
		);
		if (!ledger.commitState(
			List.of(new JournalRecord.StateOp("AUCTION_ADD", Long.toString(id), GSON.toJson(listing.toDto()))),
			"AUCTION_CREATE",
			Long.toString(id)
		)) {
			player.sendMessage("拍卖创建失败，请稍后再试。");
			return false;
		}

		int remaining = hand.getAmount() - amount;
		if (remaining <= 0) {
			player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
		} else {
			ItemStack left = hand.clone();
			left.setAmount(remaining);
			player.getInventory().setItemInMainHand(left);
		}
		player.sendMessage("已上拍 #" + id + "：" + amount + " 个 " + escrow.getType().name()
			+ "，起拍价 " + ledger.format(start)
			+ (buyout.signum() > 0 ? "，一口价 " + ledger.format(buyout) : "")
			+ "，时长 " + config.durationHours() + " 小时。");
		return true;
	}

	public boolean bid(Player player, long id, BigDecimal amount) {
		if (!config.enabled()) {
			player.sendMessage("拍卖行暂未开放。");
			return false;
		}
		AuctionListing listing = listing(id);
		if (listing == null || !listing.active() || listing.expired(System.currentTimeMillis())) {
			player.sendMessage("该拍卖不存在或已结束。");
			return false;
		}
		if (listing.seller().equals(player.getUniqueId())) {
			player.sendMessage("不能竞价自己的拍卖。");
			return false;
		}

		BigDecimal bid;
		try {
			bid = Money.positive(amount, ledger.decimalPlaces());
		} catch (IllegalArgumentException exception) {
			player.sendMessage("出价无效：" + exception.getMessage());
			return false;
		}
		BigDecimal minimum = listing.hasBid()
			? listing.highestBid().add(config.minIncrement())
			: listing.startingPrice();
		if (bid.compareTo(minimum) < 0) {
			player.sendMessage("出价至少为 " + ledger.format(minimum) + "。");
			return false;
		}
		if (listing.hasBuyout() && bid.compareTo(listing.buyoutPrice()) >= 0) {
			player.sendMessage("出价已达一口价，请直接使用 /auction buy " + id + "。");
			return false;
		}
		if (ledger.balance(player.getUniqueId()).compareTo(bid) < 0) {
			player.sendMessage("余额不足，当前余额 " + ledger.format(balance(player)) + "。");
			return false;
		}

		List<JournalRecord.Entry> entries = new ArrayList<>();
		entries.add(new JournalRecord.Entry(LedgerService.playerKey(player.getUniqueId()), bid.negate()));
		entries.add(new JournalRecord.Entry(LedgerService.ESCROW_AUCTION, bid));
		UUID previousBidder = listing.highestBidder();
		BigDecimal previousBid = listing.highestBid();
		if (previousBidder != null && previousBid.signum() > 0) {
			entries.add(new JournalRecord.Entry(LedgerService.ESCROW_AUCTION, previousBid.negate()));
			entries.add(new JournalRecord.Entry(LedgerService.playerKey(previousBidder), previousBid));
		}

		AuctionListing updated = listing.withBid(player.getUniqueId(), player.getName(), bid);
		if (!ledger.commitEntries(
			entries,
			List.of(new JournalRecord.StateOp("AUCTION_UPDATE", Long.toString(id), GSON.toJson(updated.toDto()))),
			"AUCTION_BID",
			Long.toString(id)
		)) {
			player.sendMessage("出价失败，请稍后再试。");
			return false;
		}

		player.sendMessage("已出价 " + ledger.format(bid) + " 竞拍 #" + id + "。");
		if (previousBidder != null) {
			Player previous = plugin.getServer().getPlayer(previousBidder);
			if (previous != null) {
				previous.sendMessage("你在拍卖 #" + id + " 的出价已被超过，" + ledger.format(previousBid) + " 已退回。");
			}
		}
		return true;
	}

	public boolean buyout(Player player, long id) {
		if (!config.enabled()) {
			player.sendMessage("拍卖行暂未开放。");
			return false;
		}
		AuctionListing listing = listing(id);
		if (listing == null || !listing.active() || !listing.hasBuyout()) {
			player.sendMessage("该拍卖不存在或不支持一口价。");
			return false;
		}
		if (listing.seller().equals(player.getUniqueId())) {
			player.sendMessage("不能购买自己的拍卖。");
			return false;
		}
		if (!mail.canAccept(player.getUniqueId())) {
			player.sendMessage("你的邮箱已满，无法接收物品。");
			return false;
		}

		BigDecimal price = listing.buyoutPrice();
		Settlement settlement = Settlement.of(price, config.taxRate(), ledger.decimalPlaces());
		BigDecimal tax = settlement.tax();
		BigDecimal sellerNet = settlement.net();

		List<JournalRecord.Entry> entries = new ArrayList<>();
		entries.add(new JournalRecord.Entry(LedgerService.playerKey(player.getUniqueId()), price.negate()));
		entries.add(new JournalRecord.Entry(LedgerService.playerKey(listing.seller()), sellerNet));
		if (tax.signum() > 0) {
			entries.add(new JournalRecord.Entry(LedgerService.TAX, tax));
		}
		UUID previousBidder = listing.highestBidder();
		BigDecimal previousBid = listing.highestBid();
		if (previousBidder != null && previousBid.signum() > 0) {
			entries.add(new JournalRecord.Entry(LedgerService.ESCROW_AUCTION, previousBid.negate()));
			entries.add(new JournalRecord.Entry(LedgerService.playerKey(previousBidder), previousBid));
		}

		MailService.PreparedMail prepared = mail.prepareItem(player.getUniqueId(), listing.item(), "AUCTION_BUYOUT");
		List<JournalRecord.StateOp> ops = List.of(
			new JournalRecord.StateOp("AUCTION_REMOVE", Long.toString(id), null),
			prepared.stateOp()
		);
		if (!ledger.commitEntries(entries, ops, "AUCTION_BUYOUT", Long.toString(id))) {
			player.sendMessage("余额不足，当前余额 " + ledger.format(balance(player)) + "。");
			return false;
		}

		mail.tryAutoClaim(player, prepared.entry().id());
		player.sendMessage("已用一口价 " + ledger.format(price) + " 拍下 #" + id + "。");
		if (previousBidder != null) {
			Player previous = plugin.getServer().getPlayer(previousBidder);
			if (previous != null) {
				previous.sendMessage("拍卖 #" + id + " 已被一口价拍下，" + ledger.format(previousBid) + " 已退回。");
			}
		}
		notifySeller(listing, "你的拍卖 #" + id + " 已一口价成交，收入 " + ledger.format(sellerNet) + "。");
		return true;
	}

	public boolean cancel(Player player, long id) {
		AuctionListing listing = listing(id);
		if (listing == null || !listing.active() || !listing.seller().equals(player.getUniqueId())) {
			player.sendMessage("找不到你的这个拍卖。");
			return false;
		}
		if (listing.hasBid()) {
			player.sendMessage("已有玩家出价，无法撤拍；请等待拍卖结束。");
			return false;
		}
		if (!mail.canAccept(player.getUniqueId())) {
			player.sendMessage("你的邮箱已满，无法退回物品。");
			return false;
		}
		MailService.PreparedMail prepared = mail.prepareItem(player.getUniqueId(), listing.item(), "AUCTION_CANCEL");
		ledger.commitState(
			List.of(
				new JournalRecord.StateOp("AUCTION_REMOVE", Long.toString(id), null),
				prepared.stateOp()
			),
			"AUCTION_CANCEL",
			Long.toString(id)
		);
		mail.tryAutoClaim(player, prepared.entry().id());
		player.sendMessage("已撤拍 #" + id + "，物品已退回。");
		return true;
	}

	private void settleExpired() {
		long now = System.currentTimeMillis();
		for (EconomyStore.AuctionDto dto : store.auctions().values()) {
			if (!"ACTIVE".equals(dto.status)) {
				continue;
			}
			AuctionListing listing;
			try {
				listing = AuctionListing.fromDto(dto);
			} catch (RuntimeException exception) {
				plugin.getLogger().warning("拍卖结算无法读取 " + dto.id + "：" + exception.getMessage());
				continue;
			}
			if (!listing.expired(now)) {
				continue;
			}
			try {
				if (listing.hasBid()) {
					settleWinner(listing);
				} else {
					settleUnsold(listing);
				}
			} catch (RuntimeException exception) {
				plugin.getLogger().warning("拍卖结算失败 " + dto.id + "：" + exception.getMessage());
			}
		}
	}

	private void settleWinner(AuctionListing listing) {
		UUID winnerId = listing.highestBidder();
		if (!mail.canAccept(winnerId)) {
			return;
		}
		BigDecimal price = listing.highestBid();
		Settlement settlement = Settlement.of(price, config.taxRate(), ledger.decimalPlaces());
		BigDecimal tax = settlement.tax();
		BigDecimal sellerNet = settlement.net();

		MailService.PreparedMail prepared = mail.prepareItem(winnerId, listing.item(), "AUCTION_WIN");
		List<JournalRecord.Entry> entries = new ArrayList<>();
		entries.add(new JournalRecord.Entry(LedgerService.ESCROW_AUCTION, price.negate()));
		entries.add(new JournalRecord.Entry(LedgerService.playerKey(listing.seller()), sellerNet));
		if (tax.signum() > 0) {
			entries.add(new JournalRecord.Entry(LedgerService.TAX, tax));
		}
		List<JournalRecord.StateOp> ops = List.of(
			new JournalRecord.StateOp("AUCTION_REMOVE", Long.toString(listing.id()), null),
			prepared.stateOp()
		);
		if (!ledger.commitEntries(entries, ops, "AUCTION_SETTLE", Long.toString(listing.id()))) {
			plugin.getLogger().warning("拍卖结算资金不足，稍后重试：" + listing.id());
			return;
		}

		Player winner = plugin.getServer().getPlayer(winnerId);
		if (winner != null) {
			mail.tryAutoClaim(winner, prepared.entry().id());
			winner.sendMessage("你以 " + ledger.format(price) + " 拍得拍卖 #" + listing.id()
				+ "（" + listing.item().getType().name() + "）。");
		}
		notifySeller(listing, "你的拍卖 #" + listing.id() + " 已成交，收入 " + ledger.format(sellerNet) + "。");
	}

	private void settleUnsold(AuctionListing listing) {
		if (!mail.canAccept(listing.seller())) {
			return;
		}
		MailService.PreparedMail prepared = mail.prepareItem(listing.seller(), listing.item(), "AUCTION_EXPIRE");
		ledger.commitState(
			List.of(
				new JournalRecord.StateOp("AUCTION_REMOVE", Long.toString(listing.id()), null),
				prepared.stateOp()
			),
			"AUCTION_EXPIRE",
			Long.toString(listing.id())
		);
		Player seller = plugin.getServer().getPlayer(listing.seller());
		if (seller != null) {
			mail.tryAutoClaim(seller, prepared.entry().id());
		}
		notifySeller(listing, "你的拍卖 #" + listing.id() + " 无人出价，物品已退回邮箱。");
	}

	private void notifySeller(AuctionListing listing, String message) {
		Player seller = plugin.getServer().getPlayer(listing.seller());
		if (seller != null) {
			seller.sendMessage(message);
		}
	}

	/**
	 * 校验拍卖物品编码可用，避免把无法序列化的物品托管进拍卖。
	 */
	public boolean encodable(ItemStack item) {
		try {
			ItemCodec.encode(item);
			return true;
		} catch (RuntimeException exception) {
			return false;
		}
	}
}
