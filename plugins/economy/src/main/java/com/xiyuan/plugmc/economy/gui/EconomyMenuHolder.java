package com.xiyuan.plugmc.economy.gui;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 经济系统通用 GUI 容器。所有槽位点击先被取消，再按 actions 分发。
 */
public final class EconomyMenuHolder implements InventoryHolder {
	private final Inventory inventory;
	private final Map<Integer, Consumer<Player>> actions = new HashMap<>();

	public EconomyMenuHolder(int rows, String title) {
		this.inventory = Bukkit.createInventory(this, rows * 9, MenuText.component(title));
	}

	@Override
	public Inventory getInventory() {
		return inventory;
	}

	public void set(int slot, ItemStack item, Consumer<Player> action) {
		inventory.setItem(slot, item);
		if (action == null) {
			actions.remove(slot);
		} else {
			actions.put(slot, action);
		}
	}

	public void fill(ItemStack item) {
		for (int slot = 0; slot < inventory.getSize(); slot++) {
			if (inventory.getItem(slot) == null) {
				inventory.setItem(slot, item.clone());
			}
		}
	}

	public void handle(Player player, int slot) {
		Consumer<Player> action = actions.get(slot);
		if (action != null) {
			action.accept(player);
		}
	}
}
