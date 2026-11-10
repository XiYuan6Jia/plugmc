package com.xiyuan.plugmc.economy.gui;

import com.xiyuan.plugmc.economy.bank.BankItems;
import com.xiyuan.plugmc.economy.bank.BankService;
import com.xiyuan.plugmc.economy.shop.ShopCategory;
import com.xiyuan.plugmc.economy.shop.ShopItem;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * 建材银行界面：主界面按分类进入，商品页左键兑换、右键存入。
 */
public final class BankGui {
	private final BankService bank;

	public BankGui(BankService bank) {
		this.bank = bank;
	}

	public void openMain(Player player) {
		if (!bank.enabled()) {
			player.sendMessage("建材银行未开启。");
			return;
		}
		EconomyMenuHolder holder = new EconomyMenuHolder(6, "&8建材银行");
		holder.fill(filler());
		holder.set(4, button(Material.GOLD_INGOT, "&6建材银行额度", List.of(
			"&7当前额度：&e" + bank.format(bank.creditOf(player.getUniqueId())),
			"&7额度 = 建材按合成表折算的价值",
			"&7在系统商店购买建材时自动抵扣金币"
		)), null);
		holder.setClick(20, button(Material.CHEST, "&a存入主手物品", List.of(
			"&7把主手整组建材存入银行",
			"&7Shift+点击只存 1 个",
			"&7只收商店目录内的普通物品"
		)), (p, click) -> {
			bank.depositHand(p, click.isShiftClick() ? 1 : 0);
			openMain(p);
		});
		holder.set(24, button(Material.BOOK, "&b兑换规则", List.of(
			"&7· 存入 1 个石砖可获得石砖的额度",
			"&7· 同价值建材可互相兑换，例如石砖 ↔ 裂纹石砖",
			"&7· 台阶/楼梯按合成比例低于整块建材",
			"&7· 所有换算整数取整，不会凭空产生价值"
		)), null);

		List<ShopCategory> categories = bank.categories();
		int[] slots = {28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43};
		for (int index = 0; index < categories.size() && index < slots.length; index++) {
			ShopCategory category = categories.get(index);
			holder.set(slots[index], button(category.icon(), "&6" + category.displayName(), List.of(
				"&7浏览该分类下的建材",
				"&8左键兑换 · 右键存入"
			)), p -> openCategory(p, category, 0));
		}

		holder.set(49, button(Material.BARRIER, "&c关闭", List.of()), Player::closeInventory);
		player.openInventory(holder.getInventory());
	}

	public void openCategory(Player player, ShopCategory category, int page) {
		List<ShopItem> items = bank.itemsForCategory(category.id());
		int pageSize = 45;
		int pageCount = Math.max(1, (items.size() + pageSize - 1) / pageSize);
		int current = Math.max(0, Math.min(page, pageCount - 1));

		EconomyMenuHolder holder = new EconomyMenuHolder(
			6,
			"&8建材银行：" + category.displayName() + " &7(" + (current + 1) + "/" + pageCount + ")"
		);
		holder.fill(filler());

		int start = current * pageSize;
		for (int index = 0; index < pageSize && start + index < items.size(); index++) {
			ShopItem item = items.get(start + index);
			int slot = index;
			holder.setClick(slot, itemIcon(player, item), (p, click) -> {
				handleItemClick(p, item, click);
				openCategory(p, category, current);
			});
		}

		if (current > 0) {
			holder.set(45, button(Material.ARROW, "&e上一页", List.of()), p ->
				openCategory(p, category, current - 1)
			);
		}
		holder.set(49, button(Material.ARROW, "&e返回", List.of()), this::openMain);
		if (current + 1 < pageCount) {
			holder.set(53, button(Material.ARROW, "&e下一页", List.of()), p ->
				openCategory(p, category, current + 1)
			);
		}
		player.openInventory(holder.getInventory());
	}

	private void handleItemClick(Player player, ShopItem item, ClickType click) {
		int stackSize = Math.max(1, item.material().getMaxStackSize());
		switch (click) {
			case LEFT -> bank.withdraw(player, item, 1);
			case SHIFT_LEFT -> bank.withdraw(player, item, stackSize);
			case RIGHT -> bank.deposit(player, item, 0);
			case SHIFT_RIGHT -> bank.deposit(player, item, 1);
			default -> {
			}
		}
	}

	private ItemStack itemIcon(Player player, ShopItem item) {
		long unit = bank.valueOf(item);
		long credit = bank.creditOf(player.getUniqueId());
		int stackSize = Math.max(1, item.material().getMaxStackSize());
		ItemStack icon = item.createStack(1);
		ItemMeta meta = icon.getItemMeta();
		if (meta != null) {
			meta.displayName(MenuText.component("&f" + item.id()));
			List<String> lore = new ArrayList<>();
			lore.add("&7估值：&e" + bank.format(unit) + " &7/ 个");
			lore.add("&7当前额度：&e" + bank.format(credit));
			lore.add("&7背包内可存：&f" + countPlain(player, item) + " 个");
			lore.add("&7单次最多可兑：&f"
				+ (int) Math.min(credit / Math.max(1, unit), bank.config().maxPerOperation()) + " 个");
			lore.add("&8");
			lore.add("&a左键&7 兑换 1 个");
			lore.add("&aShift+左键&7 兑换 1 组 (" + stackSize + ")");
			lore.add("&b右键&7 存入背包内该物品");
			lore.add("&bShift+右键&7 存入 1 个");
			meta.lore(MenuText.components(lore));
			icon.setItemMeta(meta);
		}
		return icon;
	}

	private int countPlain(Player player, ShopItem item) {
		int total = 0;
		for (ItemStack stack : player.getInventory().getStorageContents()) {
			if (BankItems.isPlain(item, stack)) {
				total += stack.getAmount();
			}
		}
		return total;
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
