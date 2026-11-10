package com.xiyuan.plugmc.tpa;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.List;

/**
 * 申请传送的玩家列表：点击玩家头颅即可发送申请，无需输入指令。
 */
public final class TpaGui implements Listener {
	private final TpaManager manager;

	public TpaGui(TpaManager manager) {
		this.manager = manager;
	}

	public void open(Player viewer, boolean here) {
		List<Player> targets = new java.util.ArrayList<>();
		for (Player online : Bukkit.getOnlinePlayers()) {
			if (!online.getUniqueId().equals(viewer.getUniqueId())) {
				targets.add(online);
			}
		}
		targets.sort(java.util.Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));
		TpaMenuHolder holder = new TpaMenuHolder(
			6,
			here ? "&8请对方传送到我这里" : "&8申请传送到玩家"
		);
		holder.fill(filler());

		if (targets.isEmpty()) {
			holder.set(22, button(Material.BARRIER, "&7当前没有其他在线玩家", List.of()), null);
		} else {
			for (int index = 0; index < Math.min(45, targets.size()); index++) {
				Player target = targets.get(index);
				holder.set(index, head(target, here), p -> {
					p.closeInventory();
					manager.request(p, target, here
						? TpaRequest.Direction.TO_SENDER
						: TpaRequest.Direction.TO_TARGET);
				});
			}
		}

		holder.set(48, button(
			Material.COMPASS,
			here ? "&e切换：申请传送到对方" : "&e切换：请对方传送到我这里",
			List.of("&7点击切换传送方向")
		), p -> open(p, !here));
		holder.set(49, button(Material.BARRIER, "&c关闭", List.of()), Player::closeInventory);
		holder.set(53, button(Material.ARROW, "&e返回菜单", List.of()), p ->
			p.performCommand("menu"));
		viewer.openInventory(holder.getInventory());
	}

	private ItemStack head(Player target, boolean here) {
		ItemStack head = new ItemStack(Material.PLAYER_HEAD);
		if (head.getItemMeta() instanceof SkullMeta meta) {
			meta.setOwningPlayer(target);
			meta.displayName(TpaText.of("&f" + target.getName()));
			meta.lore(List.of(
				TpaText.of("&7" + (here ? "点击申请把他传送到你这里" : "点击申请传送到他身边")),
				TpaText.of("&8对方会收到一键接受按钮")
			));
			head.setItemMeta(meta);
		}
		return head;
	}

	private ItemStack button(Material material, String name, List<String> lore) {
		ItemStack item = new ItemStack(material);
		ItemMeta meta = item.getItemMeta();
		if (meta != null) {
			meta.displayName(TpaText.of(name));
			if (!lore.isEmpty()) {
				meta.lore(lore.stream().map(TpaText::of).toList());
			}
			item.setItemMeta(meta);
		}
		return item;
	}

	private ItemStack filler() {
		return button(Material.BLACK_STAINED_GLASS_PANE, "&8 ", List.of());
	}

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onClick(InventoryClickEvent event) {
		if (!(event.getView().getTopInventory().getHolder(false) instanceof TpaMenuHolder holder)) {
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
	public void onDrag(InventoryDragEvent event) {
		if (event.getView().getTopInventory().getHolder(false) instanceof TpaMenuHolder) {
			event.setCancelled(true);
		}
	}
}
