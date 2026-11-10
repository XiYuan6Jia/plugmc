package com.xiyuan.plugmc.economy.order;

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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 收购订单：买方托管资金挂单，卖方交付物品后立即收款。
 * 托管资金记入 {@link LedgerService#ESCROW_ORDER}，退款直接回买方余额。
 */
public final class OrderService {
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
	private static final int MAX_ORDER_AMOUNT = 2304;

	private final JavaPlugin plugin;
	private final LedgerService ledger;
	private final EconomyStore store;
	private final MailService mail;
	private final java.util.Set<Long> remindedExpiring = java.util.concurrent.ConcurrentHashMap.newKeySet();
	private volatile OrderConfig config;

	public OrderService(
		JavaPlugin plugin,
		LedgerService ledger,
		EconomyStore store,
		MailService mail,
		OrderConfig config
	) {
		this.plugin = plugin;
		this.ledger = ledger;
		this.store = store;
		this.mail = mail;
		this.config = config;
	}

	public OrderConfig config() {
		return config;
	}

	public void reload() {
		plugin.reloadConfig();
		this.config = OrderConfig.load(plugin.getConfig(), ledger.decimalPlaces());
		plugin.getLogger().info("收购订单配置已重载：槽位 " + config.slots()
			+ "，税率 " + config.taxRate());
	}

	public BukkitTask startExpiryTask() {
		return plugin.getServer().getScheduler().runTaskTimer(plugin, this::expire, 20L * 20L, 20L * 20L);
	}

	public BukkitTask startReminderTask() {
		return plugin.getServer().getScheduler().runTaskTimer(
			plugin,
			() -> notifyExpiringSoon(60),
			20L * 60L,
			20L * 60L
		);
	}

	/**
	 * 提醒在线买家：自己的收购订单即将到期，剩余托管资金将退回。同一订单只提醒一次。
	 */
	public void notifyExpiringSoon(int minutes) {
		long now = System.currentTimeMillis();
		long threshold = now + Math.max(1, minutes) * 60_000L;
		for (BuyOrder order : orders()) {
			if (order.expiresAt() > threshold) {
				continue;
			}
			if (!remindedExpiring.add(order.id())) {
				continue;
			}
			Player buyer = plugin.getServer().getPlayer(order.buyer());
			if (buyer != null) {
				buyer.sendMessage("你的收购订单 #" + order.id() + "（" + order.template().getType().name()
					+ " ×" + order.remaining() + "）将在 " + minutes + " 分钟内到期，剩余托管资金会退回。");
			}
		}
	}

	public List<BuyOrder> orders() {
		List<BuyOrder> result = new ArrayList<>();
		for (EconomyStore.OrderDto dto : store.orders().values()) {
			if (!"ACTIVE".equals(dto.status) || dto.remaining <= 0) {
				continue;
			}
			try {
				BuyOrder order = BuyOrder.fromDto(dto);
				if (!order.expired(System.currentTimeMillis())) {
					result.add(order);
				}
			} catch (RuntimeException exception) {
				plugin.getLogger().warning("收购订单无法读取 " + dto.id + "：" + exception.getMessage());
			}
		}
		result.sort(Comparator.comparingLong(BuyOrder::createdAt).reversed());
		return result;
	}

	public BuyOrder order(long id) {
		EconomyStore.OrderDto dto = store.order(Long.toString(id));
		if (dto == null) {
			return null;
		}
		try {
			return BuyOrder.fromDto(dto);
		} catch (RuntimeException exception) {
			plugin.getLogger().warning("收购订单无法读取 " + id + "：" + exception.getMessage());
			return null;
		}
	}

	public List<BuyOrder> mine(Player player) {
		return orders().stream()
			.filter(order -> order.buyer().equals(player.getUniqueId()))
			.toList();
	}

	public List<BuyOrder> search(String query) {
		String needle = query == null ? "" : query.toLowerCase(Locale.ROOT).trim();
		List<BuyOrder> all = orders();
		if (needle.isEmpty()) {
			return all;
		}
		return all.stream()
			.filter(order -> order.template().getType().name().toLowerCase(Locale.ROOT).contains(needle)
				|| order.buyerName().toLowerCase(Locale.ROOT).contains(needle)
				|| Long.toString(order.id()).contains(needle))
			.toList();
	}

	public BigDecimal balance(Player player) {
		return ledger.balance(player.getUniqueId());
	}

	public String format(BigDecimal amount) {
		return ledger.format(amount);
	}

	public int decimalPlaces() {
		return ledger.decimalPlaces();
	}

	public BigDecimal estimateFee(BigDecimal unitPrice, int amount) {
		return config.listingFee(total(unitPrice, amount), ledger.decimalPlaces());
	}

	/**
	 * 按当前余额与单价推算最多能收购多少个（不超过 2304）。
	 */
	public int maxAffordable(Player player, BigDecimal unitPrice) {
		if (unitPrice == null || unitPrice.signum() <= 0) {
			return 1;
		}
		BigDecimal balance = ledger.balance(player.getUniqueId());
		int byBalance = balance
			.divide(unitPrice, 0, java.math.RoundingMode.FLOOR)
			.intValue();
		return Math.max(1, Math.min(MAX_ORDER_AMOUNT, byBalance));
	}

	public int usedSlots(Player player) {
		return (int) store.orders().values().stream()
			.filter(dto -> player.getUniqueId().toString().equals(dto.buyer))
			.filter(dto -> "ACTIVE".equals(dto.status))
			.count();
	}

	public boolean create(Player player, BigDecimal unitPrice, int requestedAmount, boolean all) {
		ItemStack hand = player.getInventory().getItemInMainHand();
		if (hand.getType().isAir() || hand.getAmount() <= 0) {
			player.sendMessage("请把要收购的物品拿在主手，或在界面中选择物品。");
			return false;
		}
		int amount = all ? hand.getAmount() : Math.max(1, requestedAmount);
		return create(player, hand, unitPrice, amount);
	}

	/**
	 * 用指定物品作为收购样本创建订单，供界面选物使用。
	 */
	public boolean create(Player player, ItemStack templateStack, BigDecimal unitPrice, int amount) {
		if (!config.enabled()) {
			player.sendMessage("收购订单功能已关闭。");
			return false;
		}
		if (templateStack == null || templateStack.getType().isAir()) {
			player.sendMessage("收购物品无效。");
			return false;
		}
		if (usedSlots(player) >= config.slots()) {
			player.sendMessage("你的收购槽位已满（" + config.slots() + " 个）。");
			return false;
		}

		int finalAmount = Math.max(1, Math.min(amount, MAX_ORDER_AMOUNT));
		BigDecimal price;
		try {
			price = Money.positive(unitPrice, ledger.decimalPlaces());
		} catch (IllegalArgumentException exception) {
			player.sendMessage("单价无效：" + exception.getMessage());
			return false;
		}

		BigDecimal total = total(price, finalAmount);
		BigDecimal fee = config.listingFee(total, ledger.decimalPlaces());
		BigDecimal cost = total.add(fee);
		if (ledger.balance(player.getUniqueId()).compareTo(cost) < 0) {
			player.sendMessage("余额不足，挂单需要托管 " + ledger.format(cost) + "。");
			return false;
		}

		long now = System.currentTimeMillis();
		long id = store.nextOrderId();
		ItemStack template = templateStack.clone();
		template.setAmount(1);
		try {
			ItemCodec.encodeVerified(template);
		} catch (RuntimeException exception) {
			player.sendMessage("物品无法托管：" + exception.getMessage());
			return false;
		}

		BuyOrder order = new BuyOrder(
			id,
			player.getUniqueId(),
			player.getName(),
			template,
			price,
			finalAmount,
			now,
			now + config.durationHours() * 60L * 60L * 1000L,
			BuyOrder.Status.ACTIVE
		);

		List<JournalRecord.Entry> entries = new ArrayList<>();
		entries.add(new JournalRecord.Entry(LedgerService.playerKey(player.getUniqueId()), cost.negate()));
		entries.add(new JournalRecord.Entry(LedgerService.ESCROW_ORDER, total));
		if (fee.signum() > 0) {
			entries.add(new JournalRecord.Entry(LedgerService.TAX, fee));
		}
		List<JournalRecord.StateOp> ops = List.of(new JournalRecord.StateOp(
			"ORDER_ADD",
			Long.toString(id),
			GSON.toJson(order.toDto())
		));
		if (!ledger.commitEntries(entries, ops, "ORDER_CREATE", Long.toString(id))) {
			player.sendMessage("余额不足，挂单已取消。");
			return false;
		}

		player.sendMessage("已发布收购订单 #" + id + "：" + finalAmount + " 个 "
			+ template.getType().name() + "，单价 " + ledger.format(price)
			+ "，已托管 " + ledger.format(total) + "（手续费 " + ledger.format(fee) + "）。"
			+ "有人交付后物品会送达你的邮箱（背包有空位时自动放入）。");
		return true;
	}

	public boolean fill(Player player, long id, int requestedAmount, boolean all) {
		if (!config.enabled()) {
			player.sendMessage("收购订单功能已关闭。");
			return false;
		}
		BuyOrder order = order(id);
		if (order == null || !order.active()) {
			player.sendMessage("该收购订单不存在或已结束。");
			return false;
		}
		if (order.buyer().equals(player.getUniqueId())) {
			player.sendMessage("不能交付自己的收购订单。");
			return false;
		}
		if (!mail.canAccept(order.buyer())) {
			player.sendMessage("买方邮箱已满，暂时无法交付，请稍后再试。");
			return false;
		}

		int amount = all ? order.remaining() : Math.min(Math.max(1, requestedAmount), order.remaining());
		List<int[]> picked = new ArrayList<>();
		int available = collect(player, order.template(), amount, picked);
		if (available <= 0) {
			player.sendMessage("背包里没有 " + order.template().getType().name() + "。");
			return false;
		}
		if (available < amount) {
			player.sendMessage("物品不足：需要 " + amount + " 个，背包只有 " + available + " 个。");
			return false;
		}

		ItemStack delivered = order.template().clone();
		delivered.setAmount(amount);
		MailService.PreparedMail prepared = mail.prepareItem(order.buyer(), delivered, "ORDER_FILL");

		ItemStack[] snapshot = cloneContents(player.getInventory().getStorageContents());
		withdraw(player, picked);
		if (!paySeller(player, order, amount, prepared)) {
			player.getInventory().setStorageContents(snapshot);
			player.sendMessage("交付失败，物品已退回。");
			return false;
		}

		player.sendMessage("已交付 " + amount + " 个 " + order.template().getType().name()
			+ "，获得 " + ledger.format(netAmount(order.unitPrice(), amount)) + "。");
		Player buyer = plugin.getServer().getPlayer(order.buyer());
		if (buyer != null) {
			mail.tryAutoClaim(buyer, prepared.entry().id());
			buyer.sendMessage("你的收购订单 #" + id + " 已收到 " + amount + " 个 "
				+ order.template().getType().name() + "，剩余 " + Math.max(0, order.remaining() - amount)
				+ " 个（背包有空位时自动放入，否则请用 /mail 领取）。");
		}
		return true;
	}

	public boolean cancel(Player player, long id) {
		BuyOrder order = order(id);
		if (order == null || !order.active() || !order.buyer().equals(player.getUniqueId())) {
			player.sendMessage("找不到你的这个收购订单。");
			return false;
		}
		refund(order, "ORDER_CANCEL");
		player.sendMessage("已取消收购订单 #" + id + "，托管资金 "
			+ ledger.format(total(order.unitPrice(), order.remaining())) + " 已退回。");
		return true;
	}

	private boolean paySeller(Player seller, BuyOrder order, int amount, MailService.PreparedMail prepared) {
		BigDecimal gross = total(order.unitPrice(), amount);
		Settlement settlement = Settlement.of(gross, config.taxRate(), ledger.decimalPlaces());
		BigDecimal tax = settlement.tax();
		BigDecimal net = settlement.net();

		List<JournalRecord.Entry> entries = new ArrayList<>();
		entries.add(new JournalRecord.Entry(LedgerService.ESCROW_ORDER, gross.negate()));
		entries.add(new JournalRecord.Entry(LedgerService.playerKey(seller.getUniqueId()), net));
		if (tax.signum() > 0) {
			entries.add(new JournalRecord.Entry(LedgerService.TAX, tax));
		}

		int remaining = order.remaining() - amount;
		List<JournalRecord.StateOp> ops = new ArrayList<>();
		if (remaining <= 0) {
			ops.add(new JournalRecord.StateOp("ORDER_REMOVE", Long.toString(order.id()), null));
		} else {
			ops.add(new JournalRecord.StateOp(
				"ORDER_UPDATE",
				Long.toString(order.id()),
				GSON.toJson(order.withRemaining(remaining, BuyOrder.Status.ACTIVE).toDto())
			));
		}
		ops.add(prepared.stateOp());
		return ledger.commitEntries(entries, ops, "ORDER_FILL", Long.toString(order.id()));
	}

	private BigDecimal netAmount(BigDecimal unitPrice, int amount) {
		BigDecimal gross = total(unitPrice, amount);
		return Settlement.of(gross, config.taxRate(), ledger.decimalPlaces()).net();
	}

	private void refund(BuyOrder order, String reason) {
		BigDecimal refund = total(order.unitPrice(), order.remaining());
		List<JournalRecord.Entry> entries = refund.signum() > 0
			? List.of(
				new JournalRecord.Entry(LedgerService.ESCROW_ORDER, refund.negate()),
				new JournalRecord.Entry(LedgerService.playerKey(order.buyer()), refund)
			)
			: List.of();
		ledger.commitEntries(
			entries,
			List.of(new JournalRecord.StateOp("ORDER_REMOVE", Long.toString(order.id()), null)),
			reason,
			Long.toString(order.id())
		);
	}

	private void expire() {
		long now = System.currentTimeMillis();
		for (EconomyStore.OrderDto dto : store.orders().values()) {
			if (!"ACTIVE".equals(dto.status) || dto.expiresAt <= 0 || dto.expiresAt > now) {
				continue;
			}
			try {
				BuyOrder order = BuyOrder.fromDto(dto);
				refund(order, "ORDER_EXPIRE");
				Player buyer = plugin.getServer().getPlayer(order.buyer());
				if (buyer != null) {
					buyer.sendMessage("你的收购订单 #" + order.id() + " 已过期，托管资金 "
						+ ledger.format(total(order.unitPrice(), order.remaining())) + " 已退回。");
				}
			} catch (RuntimeException exception) {
				plugin.getLogger().warning("收购订单过期处理失败 " + dto.id + "：" + exception.getMessage());
			}
		}
	}

	private int collect(Player player, ItemStack template, int wanted, List<int[]> picked) {
		ItemStack[] contents = player.getInventory().getStorageContents();
		int collected = 0;
		for (int slot = 0; slot < contents.length && collected < wanted; slot++) {
			ItemStack stack = contents[slot];
			if (stack == null || stack.getType().isAir() || !stack.isSimilar(template)) {
				continue;
			}
			int take = Math.min(wanted - collected, stack.getAmount());
			picked.add(new int[]{slot, take});
			collected += take;
		}
		return collected;
	}

	private void withdraw(Player player, List<int[]> picked) {
		ItemStack[] contents = player.getInventory().getStorageContents();
		for (int[] entry : picked) {
			int slot = entry[0];
			int take = entry[1];
			ItemStack stack = contents[slot];
			if (stack == null) {
				continue;
			}
			int left = stack.getAmount() - take;
			if (left <= 0) {
				contents[slot] = null;
			} else {
				stack.setAmount(left);
			}
		}
		player.getInventory().setStorageContents(contents);
	}

	private ItemStack[] cloneContents(ItemStack[] contents) {
		ItemStack[] copy = new ItemStack[contents.length];
		for (int slot = 0; slot < contents.length; slot++) {
			copy[slot] = contents[slot] == null ? null : contents[slot].clone();
		}
		return copy;
	}

	private BigDecimal total(BigDecimal unitPrice, int amount) {
		return unitPrice.multiply(BigDecimal.valueOf(amount)).setScale(
			ledger.decimalPlaces(),
			java.math.RoundingMode.UNNECESSARY
		);
	}
}
