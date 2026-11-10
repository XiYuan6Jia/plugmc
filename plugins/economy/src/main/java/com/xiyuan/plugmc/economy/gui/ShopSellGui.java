package com.xiyuan.plugmc.economy.gui;

import com.xiyuan.plugmc.economy.shop.ShopService;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * 商店批量回收页：把要卖的物品放进上方格子，一键全部回收。
 */
public final class ShopSellGui {
	private static final int INPUT_SLOTS = 27;

	private final ShopService service;

	public ShopSellGui(ShopService service) {
		this.service = service;
	}

	public void open(Player player) {
		SellMenuHolder holder = new SellMenuHolder(6, "&8商店回收", INPUT_SLOTS);
		holder.fill(filler());
		holder.set(31, info(player), null);
		holder.set(38, button(Material.ARROW, "&e返回商店", List.of()), p -> p.performCommand("shop"));
		holder.set(40, button(Material.LIME_STAINED_GLASS_PANE, "&a一键全部出售", List.of(
			"&7回收上方所有可出售的物品",
			"&7不回收的物品会留在格子里"
		)), p -> sell(p, holder));
		holder.set(42, button(Material.BARRIER, "&c关闭", List.of(
			"&7关闭时格子里的物品会退回背包"
		)), Player::closeInventory);
		player.openInventory(holder.getInventory());
	}

	private void sell(Player player, SellMenuHolder holder) {
		List<ItemStack> offered = holder.drainInput();
		if (offered.isEmpty()) {
			player.sendMessage("请先把要出售的物品放进上方格子。");
			return;
		}
		List<ItemStack> leftovers = new ArrayList<>();
		service.sellBatch(player, offered, leftovers);

		int slot = 0;
		for (ItemStack leftover : leftovers) {
			while (slot < INPUT_SLOTS && holder.getInventory().getItem(slot) != null) {
				slot++;
			}
			if (slot >= INPUT_SLOTS) {
				returnToPlayer(player, leftover);
				continue;
			}
			holder.getInventory().setItem(slot, leftover);
		}
		holder.set(31, info(player), null);
		player.sendMessage("可继续放入物品，或关闭界面取回剩余物品。");
	}

	/**
	 * 关闭界面时把输入区剩余物品退回玩家；背包满了就掉落在脚下。
	 */
	public static void returnInput(Player player, SellMenuHolder holder) {
		for (ItemStack stack : holder.drainInput()) {
			returnToPlayer(player, stack);
		}
	}

	private static void returnToPlayer(Player player, ItemStack stack) {
		var leftovers = player.getInventory().addItem(stack.clone());
		for (ItemStack leftover : leftovers.values()) {
			player.getWorld().dropItemNaturally(player.getLocation(), leftover);
		}
	}

	private ItemStack info(Player player) {
		return button(Material.LANTERN, "&e批量回收", List.of(
			"&7把物品放入上方 " + INPUT_SLOTS + " 格",
			"&7Shift+点击背包物品可整组放入",
			"&7Shift+点击输入区可整组取回",
			"&7点击下方「一键全部出售」",
			"&7只回收：" + service.sellableSummary(),
			"&7当前余额：&e" + service.format(service.balance(player)),
			"&7不回收的物品会留在格子里"
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
