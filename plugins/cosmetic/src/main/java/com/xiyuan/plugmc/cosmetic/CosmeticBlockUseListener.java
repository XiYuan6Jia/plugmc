package com.xiyuan.plugmc.cosmetic;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.ItemStack;

/**
 * 拦住"边看边用"：装饰槽里的物品只能当外观，不能吃掉、不能倒出来、不能放成方块。
 *
 * <p>为什么需要这个：装饰槽里的物品是从玩家主手拿走的一个副本，
 * 实体只是个 {@code ItemDisplay} 渲染体，但玩家手里如果还拿着同样的东西，
 * 右键就会真的触发原版行为（喝药水、倒水、放置方块），
 * 而这些行为会和"这件东西正挂在我头顶上"的观感冲突。</p>
 *
 * <p>策略：只拦「手里这件和某个槽里的完全一致（{@code isSimilar}）」的情况，
 * 避免误伤玩家手里另一件同名但内容不同的物品。</p>
 */
final class CosmeticBlockUseListener implements Listener {
	private final CosmeticPlugin plugin;
	private final CosmeticConfig config;

	CosmeticBlockUseListener(CosmeticPlugin plugin, CosmeticConfig config) {
		this.plugin = plugin;
		this.config = config;
	}

	@EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
	public void onConsume(PlayerItemConsumeEvent event) {
		if (!config.blockConsume) {
			return;
		}
		if (isCosmeticCopy(event.getPlayer(), event.getItem())) {
			event.setCancelled(true);
		}
	}

	@EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
	public void onInteract(PlayerInteractEvent event) {
		Action action = event.getAction();
		boolean right = action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK;
		if (!right) {
			return;
		}
		ItemStack item = event.getItem();
		if (item == null || item.getType().isAir()) {
			return;
		}
		if (!isCosmeticCopy(event.getPlayer(), item)) {
			return;
		}
		boolean isBlock = item.getType().isBlock();
		if (isBlock && config.blockPlace) {
			event.setCancelled(true);
			return;
		}
		if (!isBlock && config.blockInteract) {
			event.setCancelled(true);
			return;
		}
		// 空桶/水桶之类：既不是方块也不是可食用，靠 blockInteract 兜住
		if (isBucket(item.getType()) && config.blockInteract) {
			event.setCancelled(true);
		}
	}

	private boolean isBucket(Material material) {
		return material.name().endsWith("_BUCKET") || material == Material.BUCKET;
	}

	private boolean isCosmeticCopy(Player player, ItemStack candidate) {
		if (candidate == null || candidate.getType().isAir()) {
			return false;
		}
		for (String slotId : plugin.wardrobe().slotIds(player.getUniqueId())) {
			ItemStack stored = plugin.wardrobe().get(player.getUniqueId(), slotId);
			if (stored != null && stored.isSimilar(candidate)) {
				return true;
			}
		}
		return false;
	}
}
