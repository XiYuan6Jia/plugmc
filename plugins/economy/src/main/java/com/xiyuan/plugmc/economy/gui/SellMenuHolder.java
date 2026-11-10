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
 * 批量回收界面：输入区允许玩家自由放置物品，其它槽位点击交给按钮处理。
 */
public final class SellMenuHolder implements InventoryHolder {
	private final Inventory inventory;
	private final Map<Integer, Consumer<Player>> actions = new HashMap<>();
	private final int inputEnd;

	public SellMenuHolder(int rows, String title, int inputSlots) {
		this.inventory = Bukkit.createInventory(this, rows * 9, MenuText.component(title));
		this.inputEnd = inputSlots;
	}

	@Override
	public Inventory getInventory() {
		return inventory;
	}

	public boolean isInputSlot(int slot) {
		return slot >= 0 && slot < inputEnd;
	}

	/**
	 * 输入区以外的槽位（按钮/填充板）是否有空位；用于判断 Shift 移入是否会落到按钮上。
	 */
	public boolean hasEmptyOutsideInput() {
		for (int slot = inputEnd; slot < inventory.getSize(); slot++) {
			if (inventory.getItem(slot) == null) {
				return true;
			}
		}
		return false;
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
		for (int slot = inputEnd; slot < inventory.getSize(); slot++) {
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

	/**
	 * 取出输入区的全部物品，并把输入区清空。
	 */
	public java.util.List<ItemStack> drainInput() {
		java.util.List<ItemStack> items = new java.util.ArrayList<>();
		for (int slot = 0; slot < inputEnd; slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (stack != null && !stack.getType().isAir() && stack.getAmount() > 0) {
				items.add(stack.clone());
				inventory.setItem(slot, null);
			}
		}
		return items;
	}
}
