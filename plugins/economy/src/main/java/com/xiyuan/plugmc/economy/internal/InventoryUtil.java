package com.xiyuan.plugmc.economy.internal;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;

public final class InventoryUtil {
	private InventoryUtil() {
	}

	public static boolean canFit(Player player, List<ItemStack> stacks) {
		Inventory probe = Bukkit.createInventory(null, 36);
		probe.setContents(player.getInventory().getStorageContents());
		for (ItemStack stack : stacks) {
			if (!probe.addItem(stack.clone()).isEmpty()) {
				return false;
			}
		}
		return true;
	}

	public static int count(Map<Integer, ItemStack> leftovers) {
		int total = 0;
		for (ItemStack stack : leftovers.values()) {
			total += stack.getAmount();
		}
		return total;
	}
}
