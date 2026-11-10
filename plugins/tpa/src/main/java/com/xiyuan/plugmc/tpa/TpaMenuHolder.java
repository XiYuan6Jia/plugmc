package com.xiyuan.plugmc.tpa;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 申请传送列表的容器：所有点击都被取消，再按槽位分发。
 */
public final class TpaMenuHolder implements InventoryHolder {
	private final Inventory inventory;
	private final Map<Integer, Consumer<Player>> actions = new HashMap<>();

	public TpaMenuHolder(int rows, String title) {
		this.inventory = Bukkit.createInventory(this, rows * 9, TpaText.of(title));
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
