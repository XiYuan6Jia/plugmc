package com.xiyuan.plugmc.bazaar;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * 摊位交互：点人偶或点装饰方块，直接打开这个人对应的交易界面。
 *
 * <p>打开的是经济系统自己的界面（售卖 / 拍卖 / 求购 / 银行 / 官方商店），
 * 商业街这边只负责「点谁 → 开哪个」，不复制任何一套交易逻辑，
 * 玩家在界面里的每一步仍然走经济系统原有的校验（余额、税费、托管、限购）。</p>
 */
final class BazaarListener implements Listener {
	private final BazaarPlugin plugin;
	private final BazaarStalls stalls;

	BazaarListener(BazaarPlugin plugin, BazaarStalls stalls) {
		this.plugin = plugin;
		this.stalls = stalls;
	}

	/** 点方块（柱子、货台、地毯）：只认真正被摊位占住的格子，广场铺装不拦。 */
	@EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
	public void onInteract(PlayerInteractEvent event) {
		if (event.getHand() != EquipmentSlot.HAND) {
			return; // 副手会重复触发一次
		}
		if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
			return;
		}
		if (event.getClickedBlock() == null) {
			return;
		}
		String owner = stalls.tenantAtBlock(event.getClickedBlock().getLocation());
		if (owner == null) {
			String fixed = stalls.fixedAtBlock(event.getClickedBlock().getLocation());
			if (fixed != null) {
				event.setCancelled(true);
				openFixed(event.getPlayer(), fixed);
			}
			return;
		}
		event.setCancelled(true);
		open(event.getPlayer(), owner);
	}

	/** 点人偶：打开摊主的交易界面。 */
	@EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
	public void onInteractEntity(PlayerInteractEntityEvent event) {
		if (event.getHand() != EquipmentSlot.HAND) {
			return;
		}
		String owner = stalls.tenantAtEntity(event.getRightClicked().getUniqueId());
		if (owner == null) {
			String fixed = stalls.fixedAtEntity(event.getRightClicked().getUniqueId());
			if (fixed != null) {
				event.setCancelled(true);
				openFixed(event.getPlayer(), fixed);
			}
			return;
		}
		event.setCancelled(true);
		open(event.getPlayer(), owner);
	}

	private void open(Player viewer, String ownerName) {
		BazaarTenants tenants = plugin.currentTenants();
		if (tenants == null) {
			return;
		}
		BazaarTenants.Tenant tenant = tenants.byName(ownerName);
		if (tenant == null) {
			// 牌子还在、交易已经结束了 —— 下一次刷新会把这个摊位撤掉
			viewer.sendMessage(BazaarText.component("&7这个摊位已经空了。"));
			return;
		}
		BazaarMenu.open(viewer, tenant);
	}

	/** 固定摊位的用途名 → 对应的经济系统界面。 */
	private static void openFixed(Player viewer, String label) {
		switch (label) {
			case "银行" -> BazaarMenu.openFixed(viewer, "bank");
			case "官方商店" -> BazaarMenu.openFixed(viewer, "official");
			default -> BazaarMenu.openFixed(viewer, "event");
		}
	}
}
