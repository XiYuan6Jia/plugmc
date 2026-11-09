package com.xiyuan.plugmc.economy.shop;

import com.xiyuan.plugmc.economy.internal.EconomyStore;
import com.xiyuan.plugmc.economy.internal.InventoryUtil;
import com.xiyuan.plugmc.economy.internal.LedgerService;
import com.xiyuan.plugmc.economy.internal.Money;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class ShopService {
	private static final int MAX_TRANSACTION_AMOUNT = 2304;

	private final JavaPlugin plugin;
	private final LedgerService ledger;
	private final EconomyStore store;
	private final DailyLimitService dailyLimits;
	private volatile ShopConfig settings;
	private volatile GoodsConfig goods;

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

	public void reload() {
		plugin.reloadConfig();
		ShopConfig reloadedSettings = ShopConfig.load(plugin.getConfig());
		GoodsConfig reloadedGoods = GoodsConfig.load(plugin, reloadedSettings, ledger.decimalPlaces());
		this.settings = reloadedSettings;
		this.goods = reloadedGoods;
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
		if (ledger.balance(player.getUniqueId()).compareTo(total) < 0) {
			player.sendMessage("余额不足，本次需要 " + ledger.format(total)
				+ "，当前余额 " + ledger.format(ledger.balance(player.getUniqueId())) + "。");
			return false;
		}

		List<ItemStack> stacks = split(item, amount);
		if (!InventoryUtil.canFit(player, stacks)) {
			player.sendMessage("背包空间不足，请先清理背包。");
			return false;
		}

		if (!ledger.withdraw(player.getUniqueId(), total, "SHOP_BUY", item.id())) {
			player.sendMessage("余额不足，购买已取消。");
			return false;
		}

		Map<Integer, ItemStack> leftovers = player.getInventory().addItem(stacks.toArray(ItemStack[]::new));
		int leftoverCount = InventoryUtil.count(leftovers);
		int delivered = amount - leftoverCount;
		if (leftoverCount > 0) {
			BigDecimal refund = total(item.buyPrice(), leftoverCount);
			ledger.deposit(player.getUniqueId(), refund, "SHOP_REFUND", item.id());
			player.sendMessage("背包空间不足，已退还 " + ledger.format(refund) + "。");
		}
		if (delivered > 0) {
			dailyLimits.consume(player, "buy", item.id(), delivered);
			player.sendMessage("已购买 " + delivered + " 个 " + item.id() + "，花费 "
				+ ledger.format(total(item.buyPrice(), delivered))
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

		ShopItem item = goods.match(hand, settings.allowCustomItems());
		if (item == null) {
			player.sendMessage("商店不回收这种物品。");
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

		BigDecimal total = total(item.sellPrice(), amount);
		ItemStack original = hand.clone();
		if (amount >= available) {
			player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
		} else {
			ItemStack remaining = hand.clone();
			remaining.setAmount(available - amount);
			player.getInventory().setItemInMainHand(remaining);
		}

		try {
			ledger.deposit(player.getUniqueId(), total, "SHOP_SELL", item.id());
		} catch (RuntimeException exception) {
			player.getInventory().setItemInMainHand(original);
			throw exception;
		}

		dailyLimits.consume(player, "sell", item.id(), amount);
		player.sendMessage("已出售 " + amount + " 个 " + item.id() + "，获得 "
			+ ledger.format(total) + "，余额 " + ledger.format(ledger.balance(player.getUniqueId())) + "。");
		return true;
	}

	public boolean sellBatch(Player player, List<ItemStack> offered, List<ItemStack> leftoversLeftInGui) {
		if (!checkAvailable(player)) {
			return false;
		}
		BigDecimal earned = Money.zero(ledger.decimalPlaces());
		int soldTotal = 0;
		Set<String> unsellable = new LinkedHashSet<>();
		Set<String> limited = new LinkedHashSet<>();

		for (ItemStack stack : offered) {
			if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) {
				continue;
			}
			ShopItem item = goods.match(stack, settings.allowCustomItems());
			if (item == null) {
				unsellable.add(stack.getType().name());
				leftoversLeftInGui.add(stack.clone());
				continue;
			}
			int limit = settings.effectiveSellLimit(item.dailySellLimit());
			int remaining = dailyLimits.remaining(player, "sell", item.id(), limit);
			int sellable = Math.min(stack.getAmount(), remaining);
			if (sellable <= 0) {
				limited.add(item.id());
				leftoversLeftInGui.add(stack.clone());
				continue;
			}

			ItemStack sold = stack.clone();
			sold.setAmount(sellable);
			BigDecimal value = total(item.sellPrice(), sellable);
			try {
				ledger.deposit(player.getUniqueId(), value, "SHOP_SELL", item.id());
			} catch (RuntimeException exception) {
				leftoversLeftInGui.add(stack.clone());
				continue;
			}
			dailyLimits.consume(player, "sell", item.id(), sellable);
			earned = earned.add(value);
			soldTotal += sellable;

			int rest = stack.getAmount() - sellable;
			if (rest > 0) {
				ItemStack leftover = stack.clone();
				leftover.setAmount(rest);
				leftoversLeftInGui.add(leftover);
				limited.add(item.id());
			}
		}

		if (soldTotal > 0) {
			player.sendMessage("批量回收完成：出售 " + soldTotal + " 件，获得 "
				+ ledger.format(earned) + "，余额 "
				+ ledger.format(ledger.balance(player.getUniqueId())) + "。");
		} else {
			player.sendMessage("没有可回收的物品。");
		}
		if (!unsellable.isEmpty()) {
			player.sendMessage("以下物品商店不回收，已留在原地：" + String.join("、", unsellable));
		}
		if (!limited.isEmpty()) {
			player.sendMessage("以下商品达到今日回收上限，剩余部分已留在原地："
				+ String.join("、", limited));
		}
		return soldTotal > 0;
	}

	public void price(Player player) {
		ItemStack hand = player.getInventory().getItemInMainHand();
		if (hand.getType().isAir() || hand.getAmount() <= 0) {
			player.sendMessage("请把要估价的物品拿在主手。");
			return;
		}
		ShopItem item = goods.match(hand, settings.allowCustomItems());
		if (item == null) {
			player.sendMessage("商店不回收这种物品。");
			return;
		}
		int buyLimit = settings.effectiveBuyLimit(item.dailyBuyLimit());
		int sellLimit = settings.effectiveSellLimit(item.dailySellLimit());
		player.sendMessage("商品：" + item.id()
			+ "，买价 " + ledger.format(item.buyPrice())
			+ " / 件，回收价 " + ledger.format(item.sellPrice()) + " / 件。");
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
}
