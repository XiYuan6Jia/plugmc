package com.xiyuan.plugmc.economy.gui;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.util.List;

/**
 * 子页面导航：优先回到 server-menu 的服务器菜单，未安装该插件时退化为关闭界面。
 */
public final class GuiNavigation {
	private static final String MENU_PLUGIN = "server-menu";
	private static final String MENU_COMMAND = "menu";

	private GuiNavigation() {
	}

	public static boolean hasParentMenu() {
		Plugin menu = Bukkit.getPluginManager().getPlugin(MENU_PLUGIN);
		return menu != null && menu.isEnabled();
	}

	public static void back(Player player) {
		player.closeInventory();
		if (hasParentMenu()) {
			player.performCommand(MENU_COMMAND);
		}
	}

	public static ItemStack backButton() {
		return button(hasParentMenu()
			? List.of("&7回到服务器菜单")
			: List.of("&7关闭当前界面"));
	}

	private static ItemStack button(List<String> lore) {
		ItemStack item = new ItemStack(Material.ARROW);
		ItemMeta meta = item.getItemMeta();
		if (meta != null) {
			meta.displayName(MenuText.component("&e返回上一级"));
			meta.lore(MenuText.components(lore));
			item.setItemMeta(meta);
		}
		return item;
	}
}
