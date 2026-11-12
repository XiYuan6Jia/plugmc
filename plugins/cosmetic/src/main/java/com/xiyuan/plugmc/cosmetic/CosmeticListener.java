package com.xiyuan.plugmc.cosmetic;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * 事件入口：界面交互 + 生命周期清理。
 *
 * <p>生命周期这块是评估文档里点名的风险 3「状态重建面广」——
 * 登录、重生、换世界、死亡、下线五个口子都要收，漏一个就会留下漂在半空的装饰实体。</p>
 */
final class CosmeticListener implements Listener {
	private final CosmeticPlugin plugin;

	CosmeticListener(CosmeticPlugin plugin) {
		this.plugin = plugin;
	}

	// ---------- 界面交互 ----------

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onClick(InventoryClickEvent event) {
		if (!(event.getInventory().getHolder() instanceof CosmeticGui.Holder holder)) {
			return;
		}
		event.setCancelled(true);
		if (!(event.getWhoClicked() instanceof Player player)) {
			return;
		}
		if (!holder.owner().equals(player)) {
			return;
		}
		int raw = event.getRawSlot();
		if (raw < 0 || raw >= event.getInventory().getSize()) {
			// 点的是自己的背包：直接忽略，避免物品被拖进装饰界面
			return;
		}

		CosmeticConfig.Slot slot = plugin.gui().slotAt(raw);
		if (slot != null) {
			handleSlot(player, slot);
			return;
		}
		if (plugin.gui().isClearButton(raw)) {
			handleClear(player);
			return;
		}
		if (plugin.gui().isCloseButton(raw)) {
			player.closeInventory();
		}
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onDrag(InventoryDragEvent event) {
		if (event.getInventory().getHolder() instanceof CosmeticGui.Holder) {
			event.setCancelled(true);
		}
	}

	@EventHandler
	public void onClose(InventoryCloseEvent event) {
		if (!(event.getInventory().getHolder() instanceof CosmeticGui.Holder holder)) {
			return;
		}
		if (event.getPlayer() instanceof Player player) {
			plugin.wardrobe().saveIfDirty();
			plugin.attributes().refresh(player);
			plugin.refresh(player);
		}
	}

	private void handleSlot(Player player, CosmeticConfig.Slot slot) {
		if (slot.permission() != null && !slot.permission().isBlank() && !player.hasPermission(slot.permission())) {
			player.sendMessage(CosmeticText.component("&c这个部位还没有解锁"));
			return;
		}

		if (plugin.wardrobe().has(player.getUniqueId(), slot.id())) {
			// 取下：物品还回背包，放不下就掉在脚下
			ItemStack removed = plugin.wardrobe().take(player.getUniqueId(), slot.id());
			plugin.wardrobe().save();
			if (removed != null) {
				var leftovers = player.getInventory().addItem(removed);
				for (ItemStack drop : leftovers.values()) {
					player.getWorld().dropItemNaturally(player.getLocation(), drop);
				}
				player.sendMessage(CosmeticText.component("&e已取下 " + slot.name() + " &7的物品"));
			}
			plugin.attributes().refresh(player);
			plugin.refresh(player);
			player.openInventory(plugin.gui().build(player));
			return;
		}

		// 放入：取主手物品
		ItemStack inHand = player.getInventory().getItemInMainHand();
		if (inHand.getType().isAir()) {
			player.sendMessage(CosmeticText.component("&7先把要挂的物品拿在手里，再点这个部位"));
			return;
		}
		ItemStack single = inHand.clone();
		single.setAmount(1);
		try {
			plugin.wardrobe().put(player.getUniqueId(), slot.id(), single);
		} catch (IllegalArgumentException exception) {
			player.sendMessage(CosmeticText.component("&c放不进去：" + exception.getMessage()));
			return;
		}
		// 从主手扣掉一个（只是拿走一个，不是整叠）
		if (inHand.getAmount() <= 1) {
			player.getInventory().setItemInMainHand(null);
		} else {
			inHand.setAmount(inHand.getAmount() - 1);
			player.getInventory().setItemInMainHand(inHand);
		}
		plugin.wardrobe().save();
		plugin.attributes().refresh(player);
		plugin.refresh(player);
		player.sendMessage(CosmeticText.component("&a已放入 " + slot.name() + " &7的物品"));
		player.openInventory(plugin.gui().build(player));
	}

	private void handleClear(Player player) {
		List<ItemStack> returned = plugin.wardrobe().clear(player.getUniqueId());
		if (returned.isEmpty()) {
			player.sendMessage(CosmeticText.component("&7身上没有装饰"));
			return;
		}
		plugin.wardrobe().save();
		int count = 0;
		for (ItemStack stack : returned) {
			var leftovers = player.getInventory().addItem(stack);
			for (ItemStack drop : leftovers.values()) {
				player.getWorld().dropItemNaturally(player.getLocation(), drop);
			}
			count++;
		}
		player.sendMessage(CosmeticText.component("&e已取下 " + count + " 件装饰"));
		plugin.attributes().refresh(player);
		plugin.refresh(player);
		player.openInventory(plugin.gui().build(player));
	}

	// ---------- 生命周期 ----------

	@EventHandler
	public void onJoin(PlayerJoinEvent event) {
		Player player = event.getPlayer();
		plugin.attributes().refresh(player);
		plugin.refresh(player);
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		Player player = event.getPlayer();
		plugin.displayDisplays().removeAll(player.getUniqueId(), true);
		plugin.attributes().strip(player);
		plugin.attributes().forget(player.getUniqueId());
		plugin.wardrobe().saveIfDirty();
	}

	@EventHandler
	public void onRespawn(PlayerRespawnEvent event) {
		Player player = event.getPlayer();
		plugin.displayDisplays().removeAll(player.getUniqueId(), true);
		plugin.attributes().refresh(player);
		// 重生后玩家位置会变，下一轮同步器自然会重建实体
		plugin.runSyncLater(player);
	}

	@EventHandler
	public void onChangedWorld(PlayerChangedWorldEvent event) {
		Player player = event.getPlayer();
		plugin.displayDisplays().removeAll(player.getUniqueId(), true);
		plugin.attributes().refresh(player);
		plugin.runSyncLater(player);
	}

	@EventHandler
	public void onTeleport(PlayerTeleportEvent event) {
		// 跨世界传送时实体留在旧世界，必须先清掉
		if (event.getFrom().getWorld() != null && event.getTo() != null
			&& event.getFrom().getWorld() != event.getTo().getWorld()) {
			plugin.displayDisplays().removeAll(event.getPlayer().getUniqueId(), true);
		}
	}

	@EventHandler
	public void onDeath(PlayerDeathEvent event) {
		Player player = event.getEntity();
		plugin.displayDisplays().removeAll(player.getUniqueId(), true);
		plugin.attributes().strip(player);
	}

	@EventHandler
	public void onDamage(EntityDamageEvent event) {
		// 装饰实体是 Invulnerable，这里只是兜底：万一被别的插件改成可燃/可伤
		if (event.getEntity() instanceof Player) {
			return;
		}
		if (event.getEntity().getScoreboardTags().contains(CosmeticDisplays.TAG)) {
			event.setCancelled(true);
		}
	}
}
