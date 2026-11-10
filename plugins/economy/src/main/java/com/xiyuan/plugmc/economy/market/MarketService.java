package com.xiyuan.plugmc.economy.market;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.xiyuan.plugmc.economy.internal.EconomyStore;
import com.xiyuan.plugmc.economy.internal.ItemCodec;
import com.xiyuan.plugmc.economy.internal.JournalRecord;
import com.xiyuan.plugmc.economy.internal.LedgerService;
import com.xiyuan.plugmc.economy.internal.Money;
import com.xiyuan.plugmc.economy.mail.MailService;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class MarketService {
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

	private final JavaPlugin plugin;
	private final LedgerService ledger;
	private final EconomyStore store;
	private final MailService mail;
	private volatile MarketConfig config;

	public MarketService(
		JavaPlugin plugin,
		LedgerService ledger,
		EconomyStore store,
		MailService mail,
		MarketConfig config
	) {
		this.plugin = plugin;
		this.ledger = ledger;
		this.store = store;
		this.mail = mail;
		this.config = config;
	}

	public MarketConfig config() {
		return config;
	}

	public void reload() {
		plugin.reloadConfig();
		this.config = MarketConfig.load(plugin.getConfig(), ledger.decimalPlaces());
		plugin.getLogger().info("市场配置已重载：槽位 " + config.slots()
			+ "，税率 " + config.taxRate());
	}

	public BukkitTask startExpiryTask() {
		return plugin.getServer().getScheduler().runTaskTimer(
			plugin,
			this::expire,
			20L * 20L,
			20L * 20L
		);
	}

	public List<MarketListing> listings() {
		List<MarketListing> result = new ArrayList<>();
		for (EconomyStore.MarketListingDto dto : store.marketListings().values()) {
			if (!"ACTIVE".equals(dto.status) || dto.escrowPending) {
				continue;
			}
			try {
				result.add(MarketListing.fromDto(dto));
			} catch (RuntimeException exception) {
				plugin.getLogger().warning("市场挂单无法读取 " + dto.id + "：" + exception.getMessage());
			}
		}
		result.sort(Comparator.comparingLong(MarketListing::createdAt).reversed());
		return result;
	}

	public MarketListing listing(long id) {
		EconomyStore.MarketListingDto dto = store.marketListing(Long.toString(id));
		if (dto == null) {
			return null;
		}
		try {
			return MarketListing.fromDto(dto);
		} catch (RuntimeException exception) {
			plugin.getLogger().warning("市场挂单无法读取 " + id + "：" + exception.getMessage());
			return null;
		}
	}

	public BigDecimal balance(Player player) {
		return ledger.balance(player.getUniqueId());
	}

	public String format(BigDecimal amount) {
		return ledger.format(amount);
	}

	public List<MarketListing> search(String query) {
		String needle = query == null ? "" : query.toLowerCase(Locale.ROOT).trim();
		if (needle.isEmpty()) {
			return listings();
		}
		return listings().stream()
			.filter(listing -> listing.item().getType().name().toLowerCase(Locale.ROOT).contains(needle)
				|| listing.sellerName().toLowerCase(Locale.ROOT).contains(needle)
				|| Long.toString(listing.id()).contains(needle))
			.toList();
	}

	public int usedSlots(Player player) {
		return (int) store.marketListings().values().stream()
			.filter(dto -> player.getUniqueId().toString().equals(dto.seller))
			.filter(dto -> "PENDING".equals(dto.status) || "ACTIVE".equals(dto.status))
			.count();
	}

	public boolean create(Player player, BigDecimal unitPrice, int requestedAmount, boolean all) {
		if (!checkAvailable(player)) {
			return false;
		}
		ItemStack hand = player.getInventory().getItemInMainHand();
		if (hand.getType().isAir() || hand.getAmount() <= 0) {
			player.sendMessage("请把要上架的物品拿在主手。");
			return false;
		}
		if (usedSlots(player) >= config.slots()) {
			player.sendMessage("你的市场槽位已满（" + config.slots() + " 个）。");
			return false;
		}

		int amount = all ? hand.getAmount() : Math.min(Math.max(1, requestedAmount), hand.getAmount());
		BigDecimal price;
		try {
			price = Money.positive(unitPrice, ledger.decimalPlaces());
		} catch (IllegalArgumentException exception) {
			player.sendMessage("单价无效：" + exception.getMessage());
			return false;
		}

		BigDecimal total = total(price, amount);
		BigDecimal fee = config.listingFee(total, ledger.decimalPlaces());
		if (ledger.balance(player.getUniqueId()).compareTo(fee) < 0) {
			player.sendMessage("余额不足，上架费需要 " + ledger.format(fee) + "。");
			return false;
		}

		long now = System.currentTimeMillis();
		long id = store.nextMarketId();
		ItemStack escrow = hand.clone();
		escrow.setAmount(amount);

		EconomyStore.MarketListingDto dto = new EconomyStore.MarketListingDto();
		dto.id = Long.toString(id);
		dto.seller = player.getUniqueId().toString();
		dto.sellerName = player.getName();
		try {
			dto.item = ItemCodec.encodeVerified(escrow);
		} catch (RuntimeException exception) {
			player.sendMessage("物品无法托管：" + exception.getMessage());
			return false;
		}
		dto.unitPrice = price.toPlainString();
		dto.createdAt = now;
		dto.expiresAt = now + config.durationHours() * 60L * 60L * 1000L;
		dto.status = "PENDING";
		dto.escrowPending = true;

		List<JournalRecord.Entry> entries = List.of(
			new JournalRecord.Entry(LedgerService.playerKey(player.getUniqueId()), fee.negate()),
			new JournalRecord.Entry(LedgerService.TAX, fee)
		);
		List<JournalRecord.StateOp> ops = List.of(new JournalRecord.StateOp(
			"MARKET_ADD",
			Long.toString(id),
			GSON.toJson(dto)
		));
		if (!ledger.commitEntries(entries, ops, "MARKET_FEE", Long.toString(id))) {
			player.sendMessage("余额不足，上架已取消。");
			return false;
		}

		int remaining = hand.getAmount() - amount;
		if (remaining <= 0) {
			player.getInventory().setItemInMainHand(new ItemStack(org.bukkit.Material.AIR));
		} else {
			ItemStack left = hand.clone();
			left.setAmount(remaining);
			player.getInventory().setItemInMainHand(left);
		}

		dto.status = "ACTIVE";
		dto.escrowPending = false;
		ledger.commitState(
			List.of(new JournalRecord.StateOp("MARKET_UPDATE", Long.toString(id), GSON.toJson(dto))),
			"MARKET_ACTIVATE",
			Long.toString(id)
		);
		player.sendMessage("已上架 " + amount + " 个 " + escrow.getType().name()
			+ "，单价 " + ledger.format(price) + "，上架费 " + ledger.format(fee) + "。");
		return true;
	}

	public boolean buy(Player player, long id, int requestedAmount) {
		if (!checkAvailable(player)) {
			return false;
		}
		MarketListing listing = listing(id);
		if (listing == null || !listing.active()) {
			player.sendMessage("该挂单不存在或已结束。");
			return false;
		}
		if (listing.seller().equals(player.getUniqueId())) {
			player.sendMessage("不能购买自己的挂单。");
			return false;
		}
		int amount = Math.min(Math.max(1, requestedAmount), listing.amount());
		BigDecimal total = total(listing.unitPrice(), amount);
		BigDecimal tax = config.tax(total, ledger.decimalPlaces());
		BigDecimal sellerNet = total.subtract(tax).setScale(ledger.decimalPlaces(), java.math.RoundingMode.UNNECESSARY);
		if (!mail.canAccept(player.getUniqueId())) {
			player.sendMessage("你的邮箱已满，无法接收商品。");
			return false;
		}

		ItemStack bought = listing.item().clone();
		bought.setAmount(amount);
		MailService.PreparedMail prepared = mail.prepareItem(
			player.getUniqueId(),
			bought,
			"MARKET_BUY"
		);

		List<JournalRecord.Entry> entries = new ArrayList<>();
		entries.add(new JournalRecord.Entry(LedgerService.playerKey(player.getUniqueId()), total.negate()));
		entries.add(new JournalRecord.Entry(LedgerService.playerKey(listing.seller()), sellerNet));
		if (tax.signum() > 0) {
			entries.add(new JournalRecord.Entry(LedgerService.TAX, tax));
		}

		List<JournalRecord.StateOp> ops = new ArrayList<>();
		int remaining = listing.amount() - amount;
		if (remaining <= 0) {
			ops.add(new JournalRecord.StateOp("MARKET_REMOVE", Long.toString(id), null));
		} else {
			EconomyStore.MarketListingDto updated = listing.withRemaining(remaining).toDto();
			ops.add(new JournalRecord.StateOp("MARKET_UPDATE", Long.toString(id), GSON.toJson(updated)));
		}
		ops.add(prepared.stateOp());

		if (!ledger.commitEntries(entries, ops, "MARKET_BUY", Long.toString(id))) {
			player.sendMessage("余额不足，购买已取消。");
			return false;
		}

		mail.tryAutoClaim(player, prepared.entry().id());
		Player seller = plugin.getServer().getPlayer(listing.seller());
		if (seller != null) {
			seller.sendMessage("你的市场挂单 #" + id + " 卖出 " + amount + " 个，收入 "
				+ ledger.format(sellerNet) + "（税 " + ledger.format(tax) + "）。");
		}
		player.sendMessage("已购买 " + amount + " 个，花费 " + ledger.format(total) + "。");
		return true;
	}

	public boolean cancel(Player player, long id) {
		MarketListing listing = listing(id);
		if (listing == null || !listing.active() || !listing.seller().equals(player.getUniqueId())) {
			player.sendMessage("找不到你的这个挂单。");
			return false;
		}
		if (!mail.canAccept(player.getUniqueId())) {
			player.sendMessage("你的邮箱已满，无法退回商品。");
			return false;
		}
		MailService.PreparedMail prepared = mail.prepareItem(
			player.getUniqueId(),
			listing.item(),
			"MARKET_CANCEL"
		);
		List<JournalRecord.StateOp> ops = List.of(
			new JournalRecord.StateOp("MARKET_REMOVE", Long.toString(id), null),
			prepared.stateOp()
		);
		ledger.commitState(ops, "MARKET_CANCEL", Long.toString(id));
		mail.tryAutoClaim(player, prepared.entry().id());
		player.sendMessage("已下架挂单 #" + id + "，商品已退回。");
		return true;
	}

	private void expire() {
		long now = System.currentTimeMillis();
		for (EconomyStore.MarketListingDto dto : store.marketListings().values()) {
			if (!"ACTIVE".equals(dto.status) || dto.escrowPending || dto.expiresAt > now) {
				continue;
			}
			UUID sellerId;
			try {
				sellerId = UUID.fromString(dto.seller);
			} catch (RuntimeException exception) {
				continue;
			}
			if (!mail.canAccept(sellerId)) {
				continue;
			}
			try {
				MarketListing listing = MarketListing.fromDto(dto);
				MailService.PreparedMail prepared = mail.prepareItem(
					sellerId,
					listing.item(),
					"MARKET_EXPIRE"
				);
				ledger.commitState(
					List.of(
						new JournalRecord.StateOp("MARKET_REMOVE", dto.id, null),
						prepared.stateOp()
					),
					"MARKET_EXPIRE",
					dto.id
				);
				Player seller = plugin.getServer().getPlayer(sellerId);
				if (seller != null) {
					mail.tryAutoClaim(seller, prepared.entry().id());
					seller.sendMessage("你的市场挂单 #" + dto.id + " 已过期，商品已退回邮箱。");
				}
			} catch (RuntimeException exception) {
				plugin.getLogger().warning("市场挂单过期处理失败 " + dto.id + "：" + exception.getMessage());
			}
		}
	}

	public void reconcile(Player player) {
		for (EconomyStore.MarketListingDto dto : store.marketListings().values()) {
			if (!player.getUniqueId().toString().equals(dto.seller) || !dto.escrowPending) {
				continue;
			}
			try {
				MarketListing listing = MarketListing.fromDto(dto);
				player.getInventory().removeItem(listing.item().clone());
				dto.status = "ACTIVE";
				dto.escrowPending = false;
				ledger.commitState(
					List.of(new JournalRecord.StateOp("MARKET_UPDATE", dto.id, GSON.toJson(dto))),
					"MARKET_RECONCILE",
					dto.id
				);
			} catch (RuntimeException exception) {
				plugin.getLogger().warning("市场挂单对账失败 " + dto.id + "：" + exception.getMessage());
			}
		}
	}

	private boolean checkAvailable(Player player) {
		if (!config.enabled()) {
			player.sendMessage("玩家市场暂未开放。");
			return false;
		}
		if (!player.hasPermission("economy.market.use")) {
			player.sendMessage("你没有权限使用玩家市场。");
			return false;
		}
		return true;
	}

	private BigDecimal total(BigDecimal unitPrice, int amount) {
		return Money.scale(
			unitPrice.multiply(BigDecimal.valueOf(amount)),
			ledger.decimalPlaces()
		);
	}
}
