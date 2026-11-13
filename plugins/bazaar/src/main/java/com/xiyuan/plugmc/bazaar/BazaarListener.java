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
 * 摊位交互：点人偶或点装饰方块，都弹出这个人当前的全部在售项。
 *
 * <p>只做提示、不做操作 —— 真正的买/出价/交货还是走经济系统自己的命令，
 * 这样商业街不复制一套交易逻辑，也不会和经济系统的校验规则跑偏
 * （余额、税费、托管、限购这些都在经济系统里）。</p>
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
				event.getPlayer().sendMessage(BazaarText.component("&f&l" + fixed));
				event.getPlayer().sendMessage(BazaarText.component(
					"&7这是常设摊位，不需要交易 —— 里面的东西随时都能买/存。"));
			}
			return;
		}
		event.setCancelled(true);
		show(event.getPlayer(), owner);
	}

	/** 点人偶：同样弹出摊主的在售项。 */
	@EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
	public void onInteractEntity(PlayerInteractEntityEvent event) {
		if (event.getHand() != EquipmentSlot.HAND) {
			return;
		}
		String owner = stalls.tenantAtEntity(event.getRightClicked().getUniqueId());
		if (owner == null) {
			return;
		}
		event.setCancelled(true);
		show(event.getPlayer(), owner);
	}

	private void show(Player viewer, String ownerName) {
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
		viewer.sendMessage(BazaarText.component(
			"&f&l" + tenant.name() + " &7的在售项（" + tenant.kindSummary() + "）"));
		for (BazaarDeal deal : tenant.deals()) {
			viewer.sendMessage(BazaarText.component(BazaarText.dealLine(deal)));
			viewer.sendMessage(BazaarText.component(BazaarText.hintFor(deal.kind())));
		}
	}
}
