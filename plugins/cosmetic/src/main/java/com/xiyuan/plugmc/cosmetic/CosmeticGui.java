package com.xiyuan.plugmc.cosmetic;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 装备外观界面。
 *
 * <p>交互语义刻意做成"放进去就收进衣柜、取出来还给你"，不做拖拽排序：
 * 装饰槽不是容器，玩家没有理由在里面堆叠物品。</p>
 */
final class CosmeticGui {

	/** 用于在事件里识别本插件的界面。 */
	static final class Holder implements InventoryHolder {
		private final Player owner;
		private Inventory inventory;

		Holder(Player owner) {
			this.owner = owner;
		}

		Player owner() {
			return owner;
		}

		@Override
		public Inventory getInventory() {
			return inventory;
		}

		void inventory(Inventory inventory) {
			this.inventory = inventory;
		}
	}

	private final CosmeticConfig config;
	private final CosmeticWardrobe wardrobe;

	CosmeticGui(CosmeticConfig config, CosmeticWardrobe wardrobe) {
		this.config = config;
		this.wardrobe = wardrobe;
	}

	Inventory build(Player player) {
		CosmeticConfig.GuiLayout layout = config.gui;
		Holder holder = new Holder(player);
		Inventory inventory = Bukkit.createInventory(holder, layout.rows() * 9, CosmeticText.component(layout.title()));
		holder.inventory(inventory);

		ItemStack filler = buttonRaw(Material.GRAY_STAINED_GLASS_PANE, " ");
		for (int i = 0; i < inventory.getSize(); i++) {
			inventory.setItem(i, filler);
		}

		for (CosmeticConfig.Slot slot : config.slots) {
			Integer index = layout.slotIndex().get(slot.id());
			if (index == null || index < 0 || index >= inventory.getSize()) {
				continue;
			}
			inventory.setItem(index, slotIcon(player, slot));
		}

		if (layout.infoIndex() >= 0 && layout.infoIndex() < inventory.getSize()) {
			inventory.setItem(layout.infoIndex(), infoIcon(player));
		}
		if (layout.clearIndex() >= 0 && layout.clearIndex() < inventory.getSize()) {
			inventory.setItem(layout.clearIndex(), buttonRaw(Material.BARRIER, "&c清空全部",
				"&7把身上所有装饰取下，", "&7物品原样还回背包"));
		}
		if (layout.closeIndex() >= 0 && layout.closeIndex() < inventory.getSize()) {
			inventory.setItem(layout.closeIndex(), buttonRaw(Material.OAK_DOOR, "&7关闭"));
		}
		return inventory;
	}

	private ItemStack slotIcon(Player player, CosmeticConfig.Slot slot) {
		ItemStack current = wardrobe.get(player.getUniqueId(), slot.id());
		if (current != null) {
			ItemStack icon = CosmeticItems.renderCopy(current);
			ItemMeta meta = icon.getItemMeta();
			if (meta != null) {
				meta.lore(loreFor(slot, true));
				icon.setItemMeta(meta);
			}
			return icon;
		}
		return button(Material.LIGHT_GRAY_DYE, slot.name(), loreFor(slot, false));
	}

	private List<Component> loreFor(CosmeticConfig.Slot slot, boolean filled) {
		List<Component> lore = new ArrayList<>();
		lore.add(CosmeticText.component("&7槽位：&f" + slot.id()));
		lore.add(CosmeticText.component(filled ? "&7点击取下，物品还回背包" : "&7把物品拿在手里点击放入"));
		if (slot.permission() != null && !slot.permission().isBlank()) {
			lore.add(CosmeticText.component("&8需要权限：" + slot.permission()));
		}
		return lore;
	}

	private ItemStack infoIcon(Player player) {
		int used = wardrobe.count(player.getUniqueId());
		int total = config.slots.size();
		return buttonRaw(Material.BOOK, "&b装备外观",
			"&7已用槽位：&f" + used + "&7/&f" + total,
			"&7放进去的物品取下后原样归还",
			"&7属性透传：&f" + (config.attributesEnabled ? "开" : "关"),
			"&8不改客户端，不用资源包");
	}

	private ItemStack button(Material material, String name, List<Component> loreLines) {
		ItemStack stack = new ItemStack(material);
		ItemMeta meta = stack.getItemMeta();
		if (meta != null) {
			meta.displayName(CosmeticText.component(name));
			meta.lore(loreLines);
			stack.setItemMeta(meta);
		}
		return stack;
	}

	/** 便捷重载：直接给老式颜色码字符串。 */
	private ItemStack buttonRaw(Material material, String name, String... loreLines) {
		List<Component> lore = new ArrayList<>(loreLines.length);
		for (String line : loreLines) {
			lore.add(CosmeticText.component(line));
		}
		return button(material, name, lore);
	}

	/** 把 index 反查成槽位。 */
	CosmeticConfig.Slot slotAt(int index) {
		for (Map.Entry<String, Integer> entry : config.gui.slotIndex().entrySet()) {
			if (entry.getValue() != null && entry.getValue() == index) {
				return config.slot(entry.getKey());
			}
		}
		return null;
	}

	boolean isClearButton(int index) {
		return config.gui.clearIndex() == index;
	}

	boolean isCloseButton(int index) {
		return config.gui.closeIndex() == index;
	}

	/**
	 * 把主手物品放进槽位（命令路径复用）。
	 */
	boolean wearFromHand(org.bukkit.entity.Player player, CosmeticConfig.Slot slot) {
		org.bukkit.inventory.ItemStack inHand = player.getInventory().getItemInMainHand();
		if (inHand.getType().isAir()) {
			return false;
		}
		org.bukkit.inventory.ItemStack single = inHand.clone();
		single.setAmount(1);
		wardrobe.put(player.getUniqueId(), slot.id(), single);
		if (inHand.getAmount() <= 1) {
			player.getInventory().setItemInMainHand(null);
		} else {
			inHand.setAmount(inHand.getAmount() - 1);
			player.getInventory().setItemInMainHand(inHand);
		}
		wardrobe.save();
		return true;
	}

	/**
	 * 取下槽位物品并归还（命令路径复用）。返回取下的物品，空槽返回 null。
	 */
	org.bukkit.inventory.ItemStack unwear(org.bukkit.entity.Player player, CosmeticConfig.Slot slot) {
		org.bukkit.inventory.ItemStack removed = wardrobe.take(player.getUniqueId(), slot.id());
		if (removed == null) {
			return null;
		}
		wardrobe.save();
		var leftovers = player.getInventory().addItem(removed);
		for (org.bukkit.inventory.ItemStack drop : leftovers.values()) {
			player.getWorld().dropItemNaturally(player.getLocation(), drop);
		}
		return removed;
	}
}
