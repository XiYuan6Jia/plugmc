package com.xiyuan.plugmc.tpa;

import com.xiyuan.plugmc.sethome.api.HomeService;
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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 申请传送的玩家列表：点击玩家头颅即可发送申请，无需输入指令。
 *
 * <p>三种模式共用同一屏：传到对方身边、把对方拉过来、传到对方家。
 * 第三种模式点头像不会立刻发申请，而是先列出那个人的家让你选一层。</p>
 */
public final class TpaGui implements Listener {

	private enum Mode {
		TARGET("&8申请传送到玩家", "&7点击申请传送到他身边"),
		HERE("&8请对方传送到我这里", "&7点击申请把他传送到你这里"),
		HOME("&8申请传送到别人的家", "&7点击选择对方的家");

		private final String title;
		private final String hint;

		Mode(String title, String hint) {
			this.title = title;
			this.hint = hint;
		}

		Mode next() {
			return switch (this) {
				case TARGET -> HERE;
				case HERE -> HOME;
				case HOME -> TARGET;
			};
		}

		String toggleLabel() {
			return switch (this) {
				case TARGET -> "&e切换：请对方传送到我这里";
				case HERE -> "&e切换：传送到别人的家";
				case HOME -> "&e切换：申请传送到对方";
			};
		}
	}

	private final TpaManager manager;

	public TpaGui(TpaManager manager) {
		this.manager = manager;
	}

	private static HomeService homes() {
		return Bukkit.getServicesManager().load(HomeService.class);
	}

	public void open(Player viewer, boolean here) {
		open(viewer, here ? Mode.HERE : Mode.TARGET);
	}

	private void open(Player viewer, Mode mode) {
		List<Player> targets = new ArrayList<>();
		for (Player online : Bukkit.getOnlinePlayers()) {
			if (online.getUniqueId().equals(viewer.getUniqueId())) {
				continue;
			}
			// 「传送到他家」模式下，没家的人列出来只会让人白点
			if (mode == Mode.HOME && manager.homeNamesOf(online).isEmpty()) {
				continue;
			}
			targets.add(online);
		}
		targets.sort(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));

		TpaMenuHolder holder = new TpaMenuHolder(6, mode.title);
		holder.fill(filler());

		if (targets.isEmpty()) {
			holder.set(22, button(Material.BARRIER,
				mode == Mode.HOME ? "&7没有其他在线玩家设过家" : "&7当前没有其他在线玩家",
				List.of()), null);
		} else {
			for (int index = 0; index < Math.min(45, targets.size()); index++) {
				Player target = targets.get(index);
				holder.set(index, head(target, mode), p -> {
					p.closeInventory();
					if (mode == Mode.HOME) {
						openHomes(p, target);
						return;
					}
					manager.request(p, target, mode == Mode.HERE
						? TpaRequest.Direction.TO_SENDER
						: TpaRequest.Direction.TO_TARGET);
				});
			}
		}

		holder.set(48, button(Material.COMPASS, mode.toggleLabel(),
			List.of("&7点击切换传送方向或目标")), p -> open(p, mode.next()));
		holder.set(49, button(Material.BARRIER, "&c关闭", List.of()), Player::closeInventory);
		holder.set(53, button(Material.ARROW, "&e返回菜单", List.of()), p ->
			p.performCommand("menu"));
		viewer.openInventory(holder.getInventory());
	}

	/**
	 * 某个人的家园列表。只有一个家时直接发申请，省掉这一屏。
	 */
	private void openHomes(Player viewer, Player target) {
		List<String> names = manager.homeNamesOf(target);
		if (names.isEmpty()) {
			viewer.sendMessage(TpaText.of("&c" + target.getName() + " 还没有设置家园。"));
			return;
		}
		if (names.size() == 1) {
			manager.request(viewer, target, TpaRequest.Direction.TO_HOME, names.get(0));
			return;
		}
		TpaMenuHolder holder = new TpaMenuHolder(3, "&8" + target.getName() + " 的家园");
		holder.fill(filler());
		for (int index = 0; index < Math.min(18, names.size()); index++) {
			String home = names.get(index);
			holder.set(index, button(Material.OAK_DOOR, "&f" + home,
				List.of("&7点击申请传送到 &f" + target.getName() + " &7的 &f" + home,
					"&8对方会收到一键接受按钮")),
				p -> manager.request(p, target, TpaRequest.Direction.TO_HOME, home));
		}
		holder.set(22, button(Material.ARROW, "&e返回玩家列表", List.of()), p -> {
			p.closeInventory();
			open(p, Mode.HOME);
		});
		viewer.openInventory(holder.getInventory());
	}

	private ItemStack head(Player target, Mode mode) {
		ItemStack head = new ItemStack(Material.PLAYER_HEAD);
		if (head.getItemMeta() instanceof SkullMeta meta) {
			meta.setOwningPlayer(target);
			meta.displayName(TpaText.of("&f" + target.getName()));
			List<String> lore = new ArrayList<>();
			lore.add(mode.hint);
			if (mode == Mode.HOME) {
				List<String> homes = manager.homeNamesOf(target);
				lore.add("&7家园：&f" + String.join("&7、&f", homes));
			}
			lore.add("&8对方会收到一键接受按钮");
			meta.lore(lore.stream().map(TpaText::of).toList());
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
