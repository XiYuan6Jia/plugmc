package com.xiyuan.plugmc.megastructures;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 巨构观景传送列表：实体交互、/mega menu 与服务端菜单子页共用的界面。 */
public final class MegaTeleportMenu implements InventoryHolder {
	static final int CLOSE_SLOT = 49;
	private static final int[] SLOTS = {20, 22, 24, 29, 31, 33};

	private final Inventory inventory;
	private final Map<Integer, MegaViewpoint> viewpointBySlot = new HashMap<>();

	public MegaTeleportMenu() {
		this.inventory = Bukkit.createInventory(this, 54, MegaText.component("&8星际巨构 · 观景传送"));
		ItemStack filler = named(Material.BLACK_STAINED_GLASS_PANE, " ", List.of());
		for (int slot = 0; slot < 54; slot++) {
			inventory.setItem(slot, filler.clone());
		}
		inventory.setItem(4, named(Material.NETHER_STAR, "&6星际巨构观景传送", List.of(
			"&7五座超级巨构，独立维度，2048 格高度",
			"&7点击任意观景传送点开始参观",
			"&8每个传送点都会介绍景观名称"
		)));
		List<MegaViewpoint> viewpoints = MegaStructures.viewpoints();
		for (int i = 0; i < viewpoints.size() && i < SLOTS.length; i++) {
			MegaViewpoint viewpoint = viewpoints.get(i);
			int slot = SLOTS[i];
			viewpointBySlot.put(slot, viewpoint);
			inventory.setItem(slot, viewpointItem(viewpoint));
		}
		inventory.setItem(CLOSE_SLOT, named(Material.BARRIER, "&c关闭", List.of("&7关闭传送列表")));
	}

	public static void open(Player player) {
		player.openInventory(new MegaTeleportMenu().getInventory());
	}

	public MegaViewpoint viewpointAt(int slot) {
		return viewpointBySlot.get(slot);
	}

	private static ItemStack viewpointItem(MegaViewpoint viewpoint) {
		List<String> lore = new ArrayList<>();
		lore.add("&7" + viewpoint.description());
		lore.add("&8坐标 " + (int) viewpoint.x() + ", " + (int) viewpoint.y() + ", " + (int) viewpoint.z());
		lore.add("&e点击传送");
		return named(viewpoint.icon(), "&6" + viewpoint.label(), lore);
	}

	private static ItemStack named(Material material, String name, List<String> lore) {
		ItemStack item = new ItemStack(material);
		ItemMeta meta = item.getItemMeta();
		if (meta != null) {
			meta.displayName(MegaText.component(name));
			List<Component> lines = new ArrayList<>();
			for (String line : lore) {
				lines.add(MegaText.component(line));
			}
			meta.lore(lines);
			meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
			item.setItemMeta(meta);
		}
		return item;
	}

	@Override
	public Inventory getInventory() {
		return inventory;
	}
}
