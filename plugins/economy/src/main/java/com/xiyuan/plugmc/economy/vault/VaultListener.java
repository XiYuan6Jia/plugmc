package com.xiyuan.plugmc.economy.vault;

import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.block.Container;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 回收箱的事件接线。
 *
 * <p>三条入口都要管：人手动往箱子里放东西（关界面时结算）、漏斗往里灌（
 * {@link InventoryMoveItemEvent}）、以及别的东西被搬进去的其它形式 —— 全部延后一 tick 结算，
 * 免得在别人的事件回调里改容器引发 ConcurrentModification。</p>
 *
 * <p>另外两条是保护：回收箱被拆时要提醒内容会被一起带走；被爆炸波及时直接从
 * 爆炸方块列表里摘掉，免得玩家的回收点被苦力怕顺手炸没。</p>
 */
public final class VaultListener implements Listener {
	private final VaultService vault;

	public VaultListener(VaultService vault) {
		this.vault = vault;
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onClose(InventoryCloseEvent event) {
		Block block = blockOf(event.getInventory().getHolder());
		if (block == null) {
			return;
		}
		vault.settleLater(block);
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onMove(InventoryMoveItemEvent event) {
		// 只有「往回收箱里灌」需要结算；从回收箱往外抽（漏斗抽走）不动账
		Block target = blockOf(event.getDestination().getHolder());
		if (target == null) {
			return;
		}
		vault.settleLater(target);
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onBreak(BlockBreakEvent event) {
		Block block = event.getBlock();
		if (!vault.isVault(block)) {
			return;
		}
		// 不拦着拆，只是说清楚后果：箱子掉了，里面的东西和绑定一起没
		if (block.getState() instanceof Container container
			&& !container.getInventory().isEmpty()) {
			event.getPlayer().sendMessage("这个回收箱里还有东西，拆掉就一起没了。");
		}
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onExplode(BlockExplodeEvent event) {
		removeVaults(event.blockList());
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onEntityExplode(EntityExplodeEvent event) {
		removeVaults(event.blockList());
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onPlace(BlockPlaceEvent event) {
		// 玩家把别的容器（比如带 PDC 的箱子物品）摆下来时，不继承任何绑定；
		// 这里只是把「摆下来的方块绝不可能已经是回收箱」这件事写清楚。
		if (vault.isVault(event.getBlock())) {
			event.getPlayer().sendMessage("这个位置上的容器状态异常，已按普通容器处理。");
		}
	}

	private void removeVaults(List<Block> blocks) {
		List<Block> hit = new ArrayList<>();
		for (Block block : blocks) {
			if (vault.isVault(block)) {
				hit.add(block);
			}
		}
		if (hit.isEmpty()) {
			return;
		}
		Iterator<Block> iterator = blocks.iterator();
		while (iterator.hasNext()) {
			Block block = iterator.next();
			if (hit.contains(block)) {
				iterator.remove();
			}
		}
	}

	private Block blockOf(InventoryHolder holder) {
		if (holder instanceof Container container) {
			return container.getBlock();
		}
		return null;
	}
}
