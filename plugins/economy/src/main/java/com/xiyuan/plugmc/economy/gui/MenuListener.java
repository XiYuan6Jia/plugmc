package com.xiyuan.plugmc.economy.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

public final class MenuListener implements Listener {
	@EventHandler(priority = EventPriority.HIGHEST)
	public void onInventoryClick(InventoryClickEvent event) {
		if (!(event.getView().getTopInventory().getHolder(false) instanceof EconomyMenuHolder holder)) {
			return;
		}
		event.setCancelled(true);
		if (!(event.getWhoClicked() instanceof Player player)) {
			return;
		}
		int rawSlot = event.getRawSlot();
		if (rawSlot >= 0 && rawSlot < event.getView().getTopInventory().getSize()) {
			holder.handle(player, rawSlot);
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onInventoryDrag(InventoryDragEvent event) {
		if (event.getView().getTopInventory().getHolder(false) instanceof EconomyMenuHolder) {
			event.setCancelled(true);
		}
	}
}
