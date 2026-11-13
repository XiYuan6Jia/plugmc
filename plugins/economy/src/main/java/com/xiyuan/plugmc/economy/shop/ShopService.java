package com.xiyuan.plugmc.economy.shop;

import com.xiyuan.plugmc.economy.bank.BankService;
import com.xiyuan.plugmc.economy.internal.EconomyStore;
import com.xiyuan.plugmc.economy.internal.InventoryUtil;
import com.xiyuan.plugmc.economy.internal.JournalRecord;
import com.xiyuan.plugmc.economy.internal.LedgerService;
import com.xiyuan.plugmc.economy.internal.Money;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class ShopService {
	private static final int MAX_TRANSACTION_AMOUNT = 2304;

	private final JavaPlugin plugin;
	private final LedgerService ledger;
	private final EconomyStore store;
	private final DailyLimitService dailyLimits;
	private volatile ShopConfig settings;
	private volatile GoodsConfig goods;
	private BankService bank;

	public ShopService(
		JavaPlugin plugin,
		LedgerService ledger,
		EconomyStore store,
		ShopConfig settings,
		GoodsConfig goods
	) {
		this.plugin = plugin;
		this.ledger = ledger;
		this.store = store;
		this.dailyLimits = new DailyLimitService(store);
		this.settings = settings;
		this.goods = goods;
	}

	public ShopConfig settings() {
		return settings;
	}

	public GoodsConfig goods() {
		return goods;
	}

	public void setBank(BankService bank) {
		this.bank = bank;
	}

	public void reload() {
		plugin.reloadConfig();
		ShopConfig reloadedSettings = ShopConfig.load(plugin.getConfig());
		GoodsConfig reloadedGoods = GoodsConfig.load(plugin, reloadedSettings, ledger.decimalPlaces());
		this.settings = reloadedSettings;
		this.goods = reloadedGoods;
		if (bank != null) {
			bank.rebuildValues();
		}
		plugin.getLogger().info("商店配置已重载：" + reloadedGoods.items().size() + " 种商品");
	}

	public boolean buy(Player player, ShopItem item, int requestedAmount) {
		if (!checkAvailable(player)) {
			return false;
		}
		int amount = Math.max(1, Math.min(requestedAmount, MAX_TRANSACTION_AMOUNT));
		int limit = settings.effectiveBuyLimit(item.dailyBuyLimit());
		if (!dailyLimits.canUse(player, "buy", item.id(), amount, limit)) {
			player.sendMessage("今日购买限额不足，剩余 " + remainingText(
				dailyLimits.remaining(player, "buy", item.id(), limit)
			) + " 个。");
			return false;
		}

		BigDecimal total = total(item.buyPrice(), amount);
		BigDecimal tax = settings.tax(total, ledger.decimalPlaces());
		BigDecimal charge = total.add(tax);

		List<ItemStack> stacks = split(item, amount);
		if (!InventoryUtil.canFit(player, stacks)) {
			player.sendMessage("背包空间不足，请先清理背包。");
			return false;
		}

		BigDecimal bankPaid = BigDecimal.ZERO;
		BankService.BankPayment bankPayment = null;
		if (bank != null) {
			bankPayment = bank.debitForShop(player, item, charge);
			if (!bankPayment.isEmpty()) {
				bankPaid = bankPayment.amount();
			}
		}
		BigDecimal moneyDue = charge.subtract(bankPaid);
		if (ledger.balance(player.getUniqueId()).compareTo(moneyDue) < 0) {
			if (bank != null && !bankPayment.isEmpty()) {
				bank.refund(player, bankPayment, bankPaid);
			}
			player.sendMessage("余额不足，本次需要 " + ledger.format(moneyDue)
				+ "（货款 " + ledger.format(total) + " + 交易税 " + ledger.format(tax)
				+ (bankPaid.signum() > 0 ? "，建材银行已抵扣 " + ledger.format(bankPaid) : "")
				+ "），当前余额 " + ledger.format(ledger.balance(player.getUniqueId())) + "。");
			return false;
		}

		BigDecimal moneyTotal = moneyDue.signum() <= 0
			? BigDecimal.ZERO
			: moneyDue.multiply(total).divide(charge, ledger.decimalPlaces(), RoundingMode.DOWN);
		BigDecimal moneyTax = moneyDue.subtract(moneyTotal);
		if (moneyDue.signum() > 0 && !ledger.commitEntries(
			List.of(
				new JournalRecord.Entry(LedgerService.playerKey(player.getUniqueId()), moneyDue.negate()),
				new JournalRecord.Entry(LedgerService.MINT, moneyTotal),
				new JournalRecord.Entry(LedgerService.TAX, moneyTax)
			),
			List.of(),
			"SHOP_BUY",
			item.id()
		)) {
			if (bank != null && !bankPayment.isEmpty()) {
				bank.refund(player, bankPayment, bankPaid);
			}
			player.sendMessage("余额不足，购买已取消。");
			return false;
		}

		Map<Integer, ItemStack> leftovers = player.getInventory().addItem(stacks.toArray(ItemStack[]::new));
		int leftoverCount = InventoryUtil.count(leftovers);
		int delivered = amount - leftoverCount;
		if (leftoverCount > 0) {
			BigDecimal deliveredTotal = total(item.buyPrice(), delivered);
			BigDecimal deliveredTax = settings.tax(deliveredTotal, ledger.decimalPlaces());
			BigDecimal refundTotal = charge.subtract(deliveredTotal.add(deliveredTax));
			BigDecimal bankRefund = bankPaid.min(refundTotal);
			BigDecimal moneyRefund = refundTotal.subtract(bankRefund);
			if (bank != null && bankRefund.signum() > 0) {
				bank.refund(player, bankPayment, bankRefund);
			}
			if (moneyRefund.signum() > 0) {
				ledger.commitEntries(
					List.of(
						new JournalRecord.Entry(
							LedgerService.playerKey(player.getUniqueId()),
							moneyRefund
						),
						new JournalRecord.Entry(LedgerService.MINT, moneyRefund.negate())
					),
					List.of(),
					"SHOP_REFUND",
					item.id()
				);
			}
			player.sendMessage("背包空间不足，已退还 " + ledger.format(refundTotal)
				+ (bankRefund.signum() > 0 ? "（其中建材银行额度 "
					+ ledger.format(bankRefund) + "）" : "") + "。");
		}
		if (delivered > 0) {
			dailyLimits.consume(player, "buy", item.id(), delivered);
			BigDecimal deliveredTotal = total(item.buyPrice(), delivered);
			BigDecimal deliveredTax = settings.tax(deliveredTotal, ledger.decimalPlaces());
			player.sendMessage("已购买 " + delivered + " 个 " + item.id() + "，花费 "
				+ ledger.format(deliveredTotal.add(deliveredTax))
				+ (bankPaid.signum() > 0 ? "（其中建材银行抵扣 "
					+ ledger.format(bankPaid) + "）" : "")
				+ "，余额 " + ledger.format(ledger.balance(player.getUniqueId())) + "。");
		}
		return delivered > 0;
	}

	public boolean sell(Player player, int requestedAmount, boolean all) {
		if (!checkAvailable(player)) {
			return false;
		}
		ItemStack hand = player.getInventory().getItemInMainHand();
		if (hand.getType().isAir() || hand.getAmount() <= 0) {
			player.sendMessage("请把要出售的物品拿在主手。");
			return false;
		}

		ShopItem item = goods.matchSellable(hand, settings.allowCustomItems());
		if (item == null) {
			player.sendMessage(sellableHint());
			return false;
		}

		int available = hand.getAmount();
		int amount = all ? available : Math.min(Math.max(1, requestedAmount), available);
		int limit = settings.effectiveSellLimit(item.dailySellLimit());
		if (!dailyLimits.canUse(player, "sell", item.id(), amount, limit)) {
			player.sendMessage("今日出售限额不足，剩余 " + remainingText(
				dailyLimits.remaining(player, "sell", item.id(), limit)
			) + " 个。");
			return false;
		}

		BigDecimal gross = total(item.sellPrice(), amount);
		BigDecimal tax = settings.tax(gross, ledger.decimalPlaces());
		BigDecimal net = gross.subtract(tax);
		ItemStack original = hand.clone();
		if (amount >= available) {
			player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
		} else {
			ItemStack remaining = hand.clone();
			remaining.setAmount(available - amount);
			player.getInventory().setItemInMainHand(remaining);
		}

		try {
			if (!ledger.commitEntries(
				List.of(
					new JournalRecord.Entry(LedgerService.MINT, gross.negate()),
					new JournalRecord.Entry(LedgerService.playerKey(player.getUniqueId()), net),
					new JournalRecord.Entry(LedgerService.TAX, tax)
				),
				List.of(),
				"SHOP_SELL",
				item.id()
			)) {
				player.getInventory().setItemInMainHand(original);
				player.sendMessage("出售失败，请稍后再试。");
				return false;
			}
		} catch (RuntimeException exception) {
			player.getInventory().setItemInMainHand(original);
			throw exception;
		}

		dailyLimits.consume(player, "sell", item.id(), amount);
		player.sendMessage("已出售 " + amount + " 个 " + item.id() + "，获得 "
			+ ledger.format(net) + "（含交易税 " + ledger.format(tax) + "），余额 "
			+ ledger.format(ledger.balance(player.getUniqueId())) + "。");
		return true;
	}

	public boolean sellBatch(Player player, List<ItemStack> offered, List<ItemStack> leftoversLeftInGui) {
		if (!checkAvailable(player)) {
			return false;
		}
		SellResult result = sellBatchFor(player.getUniqueId(), offered, leftoversLeftInGui);
		int soldTotal = result.soldCount();
		if (soldTotal > 0) {
			player.sendMessage("批量回收完成：出售 " + soldTotal + " 件，获得 "
				+ ledger.format(result.earned()) + "（含交易税 " + ledger.format(result.tax()) + "），余额 "
				+ ledger.format(ledger.balance(player.getUniqueId())) + "。");
		} else {
			player.sendMessage("没有可回收的物品。");
		}
		if (!result.unsellable().isEmpty()) {
			player.sendMessage("以下物品商店不回收（只收矿石原矿），已留在原地："
				+ String.join("、", result.unsellable()));
		}
		if (!result.limited().isEmpty()) {
			player.sendMessage("以下商品达到今日回收上限，剩余部分已留在原地："
				+ String.join("、", result.limited()));
		}
		return soldTotal > 0;
	}

	/**
	 * 按玩家 UUID 回收，不要求玩家在线。回收箱的绑定玩家可能早就下线了，
	 * 钱仍然要记进他的账上，限额也按他的账号算。
	 *
	 * <p>与 {@link #sellBatch} 的唯一区别：不发聊天消息、不要求 Player，
	 * 收不掉的物品一律放进 leftovers 由调用方决定怎么还。</p>
	 */
	public SellResult sellBatchFor(UUID ownerId, List<ItemStack> offered, List<ItemStack> leftovers) {
		BigDecimal earned = Money.zero(ledger.decimalPlaces());
		BigDecimal taxTotal = Money.zero(ledger.decimalPlaces());
		int soldTotal = 0;
		Set<String> unsellable = new LinkedHashSet<>();
		Set<String> limited = new LinkedHashSet<>();

		for (ItemStack stack : offered) {
			if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) {
				continue;
			}
			ShopItem item = goods.matchSellable(stack, settings.allowCustomItems());
			if (item == null) {
				unsellable.add(stack.getType().name());
				leftovers.add(stack.clone());
				continue;
			}
			int limit = settings.effectiveSellLimit(item.dailySellLimit());
			int remaining = dailyLimits.remaining(ownerId, "sell", item.id(), limit);
			int sellable = Math.min(stack.getAmount(), remaining);
			if (sellable <= 0) {
				limited.add(item.id());
				leftovers.add(stack.clone());
				continue;
			}

			BigDecimal gross = total(item.sellPrice(), sellable);
			BigDecimal tax = settings.tax(gross, ledger.decimalPlaces());
			BigDecimal net = gross.subtract(tax);
			try {
				if (!ledger.commitEntries(
					List.of(
						new JournalRecord.Entry(LedgerService.MINT, gross.negate()),
						new JournalRecord.Entry(LedgerService.playerKey(ownerId), net),
						new JournalRecord.Entry(LedgerService.TAX, tax)
					),
					List.of(),
					"SHOP_SELL",
					item.id()
				)) {
					leftovers.add(stack.clone());
					continue;
				}
			} catch (RuntimeException exception) {
				leftovers.add(stack.clone());
				continue;
			}
			dailyLimits.consume(ownerId, "sell", item.id(), sellable);
			earned = earned.add(net);
			taxTotal = taxTotal.add(tax);
			soldTotal += sellable;

			int rest = stack.getAmount() - sellable;
			if (rest > 0) {
				ItemStack leftover = stack.clone();
				leftover.setAmount(rest);
				leftovers.add(leftover);
				limited.add(item.id());
			}
		}
		return new SellResult(soldTotal, earned, taxTotal, unsellable, limited);
	}

	/**
	 * 一次批量回收的结果，供不依赖聊天的调用方（回收箱）自行组织文案。
	 */
	public record SellResult(
		int soldCount,
		BigDecimal earned,
		BigDecimal tax,
		Set<String> unsellable,
		Set<String> limited
	) {
		public SellResult {
			unsellable = Set.copyOf(unsellable);
			limited = Set.copyOf(limited);
		}

		public boolean soldAnything() {
			return soldCount > 0;
		}
	}

	public int countInInventory(Player player, ShopItem item) {
		int total = 0;
		for (ItemStack stack : player.getInventory().getStorageContents()) {
			if (stack == null || stack.getType().isAir()) {
				continue;
			}
			if (goods.matchSellable(stack, settings.allowCustomItems()) == item) {
				total += stack.getAmount();
			}
		}
		return total;
	}

	/**
	 * 出售指定商品：从背包（快捷栏 + 主背包）扣除，而不是只看主手。
	 */
	public boolean sellFromInventory(Player player, ShopItem item, int requestedAmount, boolean all) {
		if (!checkAvailable(player)) {
			return false;
		}
		ItemStack[] contents = player.getInventory().getStorageContents();
		int available = 0;
		for (ItemStack stack : contents) {
			if (stack == null || stack.getType().isAir()) {
				continue;
			}
			if (goods.matchSellable(stack, settings.allowCustomItems()) == item) {
				available += stack.getAmount();
			}
		}
		if (available <= 0) {
			player.sendMessage("背包里没有可回收的 " + item.id() + "。");
			return false;
		}

		int limit = settings.effectiveSellLimit(item.dailySellLimit());
		int limitRemaining = dailyLimits.remaining(player, "sell", item.id(), limit);
		if (limitRemaining <= 0) {
			player.sendMessage("今日回收限额已用完（" + item.id() + "）。");
			return false;
		}
		int amount = all
			? Math.min(available, limitRemaining)
			: Math.min(Math.min(requestedAmount, available), limitRemaining);
		if (amount <= 0) {
			player.sendMessage("可回收数量不足。");
			return false;
		}

		ItemStack[] snapshot = player.getInventory().getStorageContents();
		int remaining = amount;
		for (int slot = 0; slot < contents.length && remaining > 0; slot++) {
			ItemStack stack = contents[slot];
			if (stack == null || stack.getType().isAir()
				|| goods.matchSellable(stack, settings.allowCustomItems()) != item) {
				continue;
			}
			int take = Math.min(stack.getAmount(), remaining);
			ItemStack left = stack.clone();
			left.setAmount(stack.getAmount() - take);
			player.getInventory().setItem(slot, left.getAmount() <= 0 ? new ItemStack(Material.AIR) : left);
			remaining -= take;
		}

		BigDecimal gross = total(item.sellPrice(), amount);
		BigDecimal tax = settings.tax(gross, ledger.decimalPlaces());
		BigDecimal net = gross.subtract(tax);
		try {
			if (!ledger.commitEntries(
				List.of(
					new JournalRecord.Entry(LedgerService.MINT, gross.negate()),
					new JournalRecord.Entry(LedgerService.playerKey(player.getUniqueId()), net),
					new JournalRecord.Entry(LedgerService.TAX, tax)
				),
				List.of(),
				"SHOP_SELL",
				item.id()
			)) {
				player.getInventory().setStorageContents(snapshot);
				player.sendMessage("出售失败，请稍后再试。");
				return false;
			}
		} catch (RuntimeException exception) {
			player.getInventory().setStorageContents(snapshot);
			throw exception;
		}

		dailyLimits.consume(player, "sell", item.id(), amount);
		player.sendMessage("已出售 " + amount + " 个 " + item.id() + "（背包扣除），获得 "
			+ ledger.format(net) + "（含交易税 " + ledger.format(tax) + "），余额 "
			+ ledger.format(ledger.balance(player.getUniqueId())) + "。");
		if (amount < available) {
			player.sendMessage("背包中还有 " + (available - amount) + " 个未出售"
				+ (limitRemaining <= amount ? "（今日限额已用完）" : "") + "。");
		}
		return true;
	}

	public void price(Player player) {
		ItemStack hand = player.getInventory().getItemInMainHand();
		if (hand.getType().isAir() || hand.getAmount() <= 0) {
			player.sendMessage("请把要估价的物品拿在主手。");
			return;
		}
		ShopItem item = goods.match(hand, settings.allowCustomItems());
		if (item == null) {
			player.sendMessage("系统商店不出售也不回收这种物品。");
			return;
		}
		int buyLimit = settings.effectiveBuyLimit(item.dailyBuyLimit());
		player.sendMessage("商品：" + item.id() + "，买价 " + ledger.format(item.buyPrice())
			+ " / 件（另收 " + settings.taxPercent() + "% 交易税）。");
		if (bank != null && bank.accepts(item)) {
			player.sendMessage("建材银行估值 " + ledger.format(bank.money(bank.valueOf(item)))
				+ " / 件，本类额度 "
				+ ledger.format(bank.money(bank.availableOf(player.getUniqueId(), item.category())))
				+ "，购买时优先抵扣。");
		}
		if (!item.sellable()) {
			player.sendMessage(sellableHint() + "此物品不回收。");
			return;
		}
		int sellLimit = settings.effectiveSellLimit(item.dailySellLimit());
		BigDecimal tax = settings.tax(item.sellPrice(), ledger.decimalPlaces());
		player.sendMessage("回收价 " + ledger.format(item.sellPrice()) + " / 件（扣 "
			+ settings.taxPercent() + "% 交易税后到手 "
			+ ledger.format(item.sellPrice().subtract(tax)) + "）。");
		player.sendMessage("今日剩余：购买 " + remainingText(dailyLimits.remaining(player, "buy", item.id(), buyLimit))
			+ "，出售 " + remainingText(dailyLimits.remaining(player, "sell", item.id(), sellLimit)) + "。");
	}

	public Optional<ShopItem> find(String idOrAlias) {
		return goods.find(idOrAlias);
	}

	public List<ShopItem> search(String query) {
		return goods.search(query);
	}

	public BigDecimal balance(Player player) {
		return ledger.balance(player.getUniqueId());
	}

	public String format(BigDecimal amount) {
		return ledger.format(amount);
	}

	public BigDecimal taxFor(BigDecimal total) {
		return settings.tax(total, ledger.decimalPlaces());
	}

	public int taxPercent() {
		return settings.taxPercent();
	}

	public String sellableSummary() {
		return goods.sellableSummary();
	}

	public int remainingBuy(Player player, ShopItem item) {
		return dailyLimits.remaining(
			player,
			"buy",
			item.id(),
			settings.effectiveBuyLimit(item.dailyBuyLimit())
		);
	}

	public int remainingSell(Player player, ShopItem item) {
		return dailyLimits.remaining(
			player,
			"sell",
			item.id(),
			settings.effectiveSellLimit(item.dailySellLimit())
		);
	}

	private boolean checkAvailable(Player player) {
		if (!settings.enabled()) {
			player.sendMessage("系统商店暂未开放。");
			return false;
		}
		if (!player.hasPermission("economy.shop.use")) {
			player.sendMessage("你没有权限使用系统商店。");
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

	private List<ItemStack> split(ShopItem item, int amount) {
		List<ItemStack> stacks = new ArrayList<>();
		int remaining = amount;
		int maxStack = Math.max(1, item.material().getMaxStackSize());
		while (remaining > 0) {
			int size = Math.min(maxStack, remaining);
			stacks.add(item.createStack(size));
			remaining -= size;
		}
		return stacks;
	}

	private String remainingText(int remaining) {
		return remaining == Integer.MAX_VALUE ? "不限" : Integer.toString(remaining);
	}

	private String sellableHint() {
		String summary = goods.sellableSummary();
		return summary.isBlank() ? "商店暂不回收任何物品。" : "商店只回收：" + summary + "。";
	}
}
