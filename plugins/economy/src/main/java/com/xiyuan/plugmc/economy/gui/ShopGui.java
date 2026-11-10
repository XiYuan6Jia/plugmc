package com.xiyuan.plugmc.economy.gui;

import com.xiyuan.plugmc.economy.shop.ShopCategory;
import com.xiyuan.plugmc.economy.shop.ShopItem;
import com.xiyuan.plugmc.economy.shop.ShopService;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class ShopGui {
	private final ShopService service;
	private final ShopSellGui sellGui;

	public ShopGui(ShopService service) {
		this.service = service;
		this.sellGui = new ShopSellGui(service);
	}

	public void openSell(Player player) {
		sellGui.open(player);
	}

	public void openMain(Player player) {
		if (!service.settings().enabled()) {
			player.sendMessage("系统商店暂未开放。");
			return;
		}

		EconomyMenuHolder holder = new EconomyMenuHolder(5, "&8系统商店");
		holder.fill(filler());

		List<ShopCategory> categories = service.goods().categories();
		int[] slots = {10, 11, 12, 13, 14, 15, 16};
		boolean hasMore = categories.size() > slots.length;
		int shown = hasMore ? slots.length - 1 : categories.size();
		for (int index = 0; index < shown; index++) {
			ShopCategory category = categories.get(index);
			ItemStack icon = button(
				category.icon(),
				"&6" + category.displayName(),
				List.of(
					"&7浏览该分类下的商品",
					"&8点击打开"
				)
			);
			holder.set(slots[index], icon, p -> openCategory(p, category, 0, ShopGui.this::openMain));
		}
		if (hasMore) {
			holder.set(slots[shown], button(
				Material.BOOKSHELF,
				"&6更多分类 &7(" + categories.size() + ")",
				List.of("&7查看全部商品分类")
			), p -> openCategoryList(p, 0));
		}

		holder.set(
			29,
			button(Material.HOPPER, "&a出售手持物品", List.of(
				"&7把主手物品卖给系统商店",
				"&7先看价格：&e/shop price"
			)),
			p -> {
				service.sell(p, 0, true);
				openMain(p);
			}
		);
		holder.set(
			28,
			button(Material.CHEST_MINECART, "&6批量回收", List.of(
				"&7打开回收页，放入物品一键出售"
			)),
			p -> {
				p.closeInventory();
				sellGui.open(p);
			}
		);
		holder.set(
			31,
			button(Material.GOLD_INGOT, "&6当前余额", List.of(
				"&e" + service.format(service.balance(player)),
				"&7购买商品会直接扣款"
			)),
			null
		);
		holder.set(
			33,
			button(Material.COMPASS, "&b搜索商品", List.of(
				"&7用法：&e/shop search <关键词>"
			)),
			p -> p.sendMessage("用法：/shop search <关键词>")
		);
		holder.set(36, GuiNavigation.backButton(), GuiNavigation::back);
		holder.set(44, button(Material.BARRIER, "&c关闭", List.of()), Player::closeInventory);
		player.openInventory(holder.getInventory());
	}

	private void openCategory(Player player, ShopCategory category, int page, Consumer<Player> back) {
		List<ShopItem> items = service.goods().itemsForCategory(category.id());
		openItemList(player, "&8" + category.displayName(), items, page, back);
	}

	private void openCategoryList(Player player, int page) {
		List<ShopCategory> categories = service.goods().categories();
		int pageSize = 45;
		int pageCount = Math.max(1, (categories.size() + pageSize - 1) / pageSize);
		int current = Math.max(0, Math.min(page, pageCount - 1));

		EconomyMenuHolder holder = new EconomyMenuHolder(
			6,
			"&8商品分类 &7(" + (current + 1) + "/" + pageCount + ")"
		);
		holder.fill(filler());

		int start = current * pageSize;
		for (int index = 0; index < pageSize && start + index < categories.size(); index++) {
			ShopCategory category = categories.get(start + index);
			holder.set(index, button(
				category.icon(),
				"&6" + category.displayName(),
				List.of(
					"&7浏览该分类下的商品",
					"&8点击打开"
				)
			), p -> openCategory(p, category, 0, target -> openCategoryList(target, current)));
		}

		if (current > 0) {
			holder.set(45, button(Material.ARROW, "&e上一页", List.of()), p ->
				openCategoryList(p, current - 1)
			);
		}
		holder.set(49, button(Material.ARROW, "&e返回", List.of()), ShopGui.this::openMain);
		if (current + 1 < pageCount) {
			holder.set(53, button(Material.ARROW, "&e下一页", List.of()), p ->
				openCategoryList(p, current + 1)
			);
		}
		player.openInventory(holder.getInventory());
	}

	private void openItemList(
		Player player,
		String title,
		List<ShopItem> items,
		int page,
		Consumer<Player> back
	) {
		int pageSize = 45;
		int pageCount = Math.max(1, (items.size() + pageSize - 1) / pageSize);
		int current = Math.max(0, Math.min(page, pageCount - 1));

		EconomyMenuHolder holder = new EconomyMenuHolder(
			6,
			title + " &7(" + (current + 1) + "/" + pageCount + ")"
		);
		holder.fill(filler());

		int start = current * pageSize;
		for (int index = 0; index < pageSize && start + index < items.size(); index++) {
			ShopItem item = items.get(start + index);
			int slot = index;
			holder.set(slot, itemIcon(item), p -> openItem(p, item, current, back));
		}

		if (current > 0) {
			holder.set(45, button(Material.ARROW, "&e上一页", List.of()), p ->
				openItemList(p, title, items, current - 1, back)
			);
		}
		holder.set(49, button(Material.ARROW, "&e返回", List.of()), back::accept);
		if (current + 1 < pageCount) {
			holder.set(53, button(Material.ARROW, "&e下一页", List.of()), p ->
				openItemList(p, title, items, current + 1, back)
			);
		} else {
			holder.set(53, button(Material.BARRIER, "&c关闭", List.of()), Player::closeInventory);
		}

		player.openInventory(holder.getInventory());
	}

	private void openItem(Player player, ShopItem item, int page, Consumer<Player> back) {
		EconomyMenuHolder holder = new EconomyMenuHolder(5, "&8商品：" + item.id());
		holder.fill(filler());
		holder.set(13, itemIcon(item), null);
		holder.set(4, button(Material.GOLD_INGOT, "&6余额", List.of(
			"&e" + service.format(service.balance(player))
		)), null);
		holder.set(12, button(Material.CHEST, "&f背包中的数量", List.of(
			"&e" + service.countInInventory(player, item) + " &7个 " + item.id(),
			"&8出售会从背包（含快捷栏）扣除"
		)), null);

		int buyRemaining = service.remainingBuy(player, item);
		int sellRemaining = service.remainingSell(player, item);

		holder.set(20, buyButton(item, 1, buyRemaining), p -> {
			service.buy(p, item, 1);
			openItem(p, item, page, back);
		});
		holder.set(21, buyButton(item, 16, buyRemaining), p -> {
			service.buy(p, item, 16);
			openItem(p, item, page, back);
		});
		holder.set(22, buyButton(item, 64, buyRemaining), p -> {
			service.buy(p, item, 64);
			openItem(p, item, page, back);
		});

		if (item.sellable()) {
			holder.set(29, sellButton(item, 1, sellRemaining), p -> {
				service.sellFromInventory(p, item, 1, false);
				openItem(p, item, page, back);
			});
			holder.set(30, sellButton(item, 16, sellRemaining), p -> {
				service.sellFromInventory(p, item, 16, false);
				openItem(p, item, page, back);
			});
			holder.set(31, sellButton(item, -1, sellRemaining), p -> {
				service.sellFromInventory(p, item, 0, true);
				openItem(p, item, page, back);
			});
		} else {
			holder.set(31, button(Material.BARRIER, "&8商店不回收此物品", List.of(
				"&7系统商店只回收矿石原矿"
			)), null);
		}

		holder.set(36, button(Material.ARROW, "&e返回", List.of()), back::accept);
		holder.set(44, button(Material.BARRIER, "&c关闭", List.of()), Player::closeInventory);
		player.openInventory(holder.getInventory());
	}

	public void openSearch(Player player, String query, int page) {
		List<ShopItem> items = service.search(query);
		if (items.isEmpty()) {
			player.sendMessage("没有找到商品：" + query);
			return;
		}
		openItemList(player, "&8搜索：" + query, items, page, p -> openMain(p));
	}

	private ItemStack itemIcon(ShopItem item) {
		ItemStack icon = item.createStack(1);
		ItemMeta meta = icon.getItemMeta();
		if (meta != null) {
			meta.displayName(MenuText.component("&f" + item.id()));
			List<String> lore = new ArrayList<>();
			lore.add("&7买价：&e" + service.format(item.buyPrice())
				+ " &8(+" + service.taxPercent() + "% 税)");
			if (item.sellable()) {
				lore.add("&7回收：&e" + service.format(item.sellPrice())
					+ " &8(-" + service.taxPercent() + "% 税)");
			} else {
				lore.add("&7回收：&8不回收（只收矿石原矿）");
			}
			lore.add("&8点击查看详情");
			meta.lore(MenuText.components(lore));
			icon.setItemMeta(meta);
		}
		return icon;
	}

	private ItemStack buyButton(ShopItem item, int amount, int remaining) {
		BigDecimal cost = item.buyPrice().multiply(BigDecimal.valueOf(amount));
		BigDecimal tax = service.taxFor(cost);
		return button(Material.LIME_STAINED_GLASS_PANE, "&a购买 " + amount + " 个", List.of(
			"&7花费：&e" + service.format(cost.add(tax)) + " &8(含税 " + service.format(tax) + ")",
			"&7今日剩余：&f" + remainingText(remaining)
		));
	}

	private ItemStack sellButton(ShopItem item, int amount, int remaining) {
		String label = amount < 0 ? "出售背包内全部" : "出售 " + amount + " 个";
		BigDecimal gross = amount < 0
			? item.sellPrice()
			: item.sellPrice().multiply(BigDecimal.valueOf(amount));
		BigDecimal tax = service.taxFor(gross);
		return button(Material.RED_STAINED_GLASS_PANE, "&c" + label, List.of(
			"&7从背包（含快捷栏）扣除",
			"&7单价：&e" + service.format(item.sellPrice()),
			"&7到手：&e" + service.format(gross.subtract(tax)) + " &8(含税 " + service.format(tax) + ")",
			"&7今日剩余：&f" + remainingText(remaining)
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

	private String remainingText(int remaining) {
		return remaining == Integer.MAX_VALUE ? "不限" : Integer.toString(remaining);
	}
}
