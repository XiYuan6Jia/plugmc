package com.xiyuan.plugmc.minigames;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

/** 界面点击与实体店柜台交互。 */
public final class GamesListener implements Listener {
	private final MinigamesPlugin plugin;
	private final GamesGui gui;

	public GamesListener(MinigamesPlugin plugin, GamesGui gui) {
		this.plugin = plugin;
		this.gui = gui;
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onClick(InventoryClickEvent event) {
		if (!(event.getView().getTopInventory().getHolder(false) instanceof GamesHolder holder)) {
			return;
		}
		event.setCancelled(true);
		if (event.getWhoClicked() instanceof Player player) {
			gui.click(player, holder, event.getRawSlot());
		}
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onDrag(InventoryDragEvent event) {
		if (event.getView().getTopInventory().getHolder(false) instanceof GamesHolder) {
			event.setCancelled(true);
		}
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onInteract(PlayerInteractEvent event) {
		if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK) {
			return;
		}
		Block block = event.getClickedBlock();
		GameType game = plugin.venue().counterAt(block);
		if (game == null) {
			return;
		}
		event.setCancelled(true);
		Player player = event.getPlayer();
		if (!player.hasPermission("minigames.use")) {
			player.sendMessage(Text.of("&c你没有权限玩小游戏。"));
			return;
		}
		gui.open(player, game);
	}

	/** 供 /games build 用。 */
	public static Material accentOf(GameType game) {
		return switch (game) {
			case BLACKJACK -> Material.RED_STAINED_GLASS;
			case WHEEL -> Material.YELLOW_STAINED_GLASS;
			case SLOTS -> Material.LIGHT_BLUE_STAINED_GLASS;
		};
	}
}
