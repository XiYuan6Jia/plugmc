package com.xiyuan.plugmc.goathorn;

import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

/**
 * 两个入口：
 * <ul>
 * <li>shift + 右键动物（主手是山羊角）→ 绑定</li>
 * <li>手持山羊角右键（空气/对着方块无交互目标）→ 吹奏召回</li>
 * </ul>
 */
final class HornListener implements Listener {
	private static final long CLICK_GUARD_MILLIS = 250;

	private final GoatHornPlugin plugin;
	private long lastBlow;

	HornListener(GoatHornPlugin plugin) {
		this.plugin = plugin;
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onInteractEntity(PlayerInteractEntityEvent event) {
		Player player = event.getPlayer();
		if (!player.isSneaking()) {
			return;
		}
		ItemStack hand = player.getInventory().getItemInMainHand();
		if (hand.getType() != Material.GOAT_HORN || event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) {
			return;
		}
		Entity clicked = event.getRightClicked();
		// 同一台动物 250ms 内的重复点击只算一次（客户端连点容易双发事件）
		long now = System.currentTimeMillis();
		String guard = clicked.getUniqueId() + "@" + now / CLICK_GUARD_MILLIS;
		if (guard.equals(lastGuard)) {
			return;
		}
		lastGuard = guard;
		event.setCancelled(true);
		plugin.service().bind(player, clicked);
	}

	private String lastGuard = "";

	/**
	 * 山羊角的「吹奏」在原版里就是右键。右键空气一定触发；右键方块时
	 * 只要方块没有可交互面（不吃羊毛染色这类），原版会把事件派到 AIR 分支，
	 * 所以这里只拦 RIGHT_CLICK_AIR，RIGHT_CLICK_BLOCK 留给原版交互。
	 */
	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
	public void onBlow(PlayerInteractEvent event) {
		// ignoreCancelled=false 是必须的：领地/保护插件常把右键空气 setCancelled，
		// 吹奏羊角本来就不该被它们拦掉，否则真人右键也会像之前那样"间歇性失效"。
		if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_AIR) {
			return;
		}
		Player player = event.getPlayer();
		ItemStack hand = event.getItem();
		if (hand == null || hand.getType() != Material.GOAT_HORN
			|| event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) {
			return;
		}
		long now = System.currentTimeMillis();
		if (now - lastBlow < 500L) {
			return;
		}
		lastBlow = now;
		plugin.service().blow(player, hand);
	}
}
