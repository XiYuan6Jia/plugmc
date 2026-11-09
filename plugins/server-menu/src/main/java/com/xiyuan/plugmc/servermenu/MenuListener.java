package com.xiyuan.plugmc.servermenu;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;

public final class MenuListener implements Listener {
	private final ServerMenuPlugin plugin;
	private final MenuManager menuManager;

	public MenuListener(ServerMenuPlugin plugin, MenuManager menuManager) {
		this.plugin = plugin;
		this.menuManager = menuManager;
	}

	@EventHandler
	public void onInventoryClick(InventoryClickEvent event) {
		if (!(event.getView().getTopInventory().getHolder(false) instanceof MenuHolder holder)) {
			return;
		}
		event.setCancelled(true);
		if (!(event.getWhoClicked() instanceof Player player)
			|| event.getRawSlot() < 0
			|| event.getRawSlot() >= event.getView().getTopInventory().getSize()) {
			return;
		}
		menuManager.handleClick(player, holder, event.getRawSlot());
	}

	@EventHandler
	public void onInventoryDrag(InventoryDragEvent event) {
		if (event.getView().getTopInventory().getHolder(false) instanceof MenuHolder) {
			event.setCancelled(true);
		}
	}

	@EventHandler
	public void onPlayerSwapHandItems(PlayerSwapHandItemsEvent event) {
		if (!event.getPlayer().isSneaking()) {
			return;
		}
		event.setCancelled(true);
		menuManager.open(event.getPlayer());
	}

	@EventHandler
	public void onPlayerJoin(PlayerJoinEvent event) {
		event.getPlayer().sendMessage(MenuText.component("&a菜单用法：&e按住 Shift 并按 F &7或输入 &e/menu"));
		plugin.handleJoin(event.getPlayer());
	}

	@EventHandler
	public void onPlayerQuit(PlayerQuitEvent event) {
		plugin.handleQuit(event.getPlayer());
	}
}
