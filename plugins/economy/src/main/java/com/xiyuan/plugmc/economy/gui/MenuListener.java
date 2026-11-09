package com.xiyuan.plugmc.economy.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

public final class MenuListener implements Listener {
	@EventHandler(priority = EventPriority.HIGHEST)
	public void onInventoryClick(InventoryClickEvent event) {
		if (event.getView().getTopInventory().getHolder(false) instanceof SellMenuHolder sell) {
			handleSellClick(event, sell);
			return;
		}
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

	private void handleSellClick(InventoryClickEvent event, SellMenuHolder sell) {
		if (!(event.getWhoClicked() instanceof Player player)) {
			event.setCancelled(true);
			return;
		}
		// 禁止 Shift 点击，避免物品被移到输入区之外或与按钮交换。
		if (event.isShiftClick()) {
			event.setCancelled(true);
			return;
		}
		int rawSlot = event.getRawSlot();
		boolean topClicked = event.getClickedInventory() != null
			&& event.getClickedInventory().equals(event.getView().getTopInventory());
		if (topClicked && !sell.isInputSlot(rawSlot)) {
			event.setCancelled(true);
			sell.handle(player, rawSlot);
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onInventoryDrag(InventoryDragEvent event) {
		if (event.getView().getTopInventory().getHolder(false) instanceof SellMenuHolder sell) {
			for (int rawSlot : event.getRawSlots()) {
				if (rawSlot < event.getView().getTopInventory().getSize() && !sell.isInputSlot(rawSlot)) {
					event.setCancelled(true);
					return;
				}
			}
			return;
		}
		if (event.getView().getTopInventory().getHolder(false) instanceof EconomyMenuHolder) {
			event.setCancelled(true);
		}
	}

	@EventHandler
	public void onInventoryClose(InventoryCloseEvent event) {
		if (!(event.getInventory().getHolder(false) instanceof SellMenuHolder sell)) {
			return;
		}
		if (event.getPlayer() instanceof Player player) {
			ShopSellGui.returnInput(player, sell);
		}
	}
}
