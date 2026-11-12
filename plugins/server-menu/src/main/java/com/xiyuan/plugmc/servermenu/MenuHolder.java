package com.xiyuan.plugmc.servermenu;

import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.Map;

public final class MenuHolder implements InventoryHolder {
	public enum Page {
		MAIN,
		PLUGINS,
		ADMIN,
		HUB,
		COSMETIC
	}

	private final Inventory inventory;
	private final Map<Integer, MenuButton> buttons;
	private final Page page;
	private final int pageNumber;

	public MenuHolder(int size, String title, Map<Integer, MenuButton> buttons, Page page, int pageNumber) {
		this.buttons = buttons;
		this.page = page;
		this.pageNumber = pageNumber;
		this.inventory = Bukkit.createInventory(this, size, MenuText.component(title));
	}

	@Override
	public Inventory getInventory() {
		return inventory;
	}

	public MenuButton getButton(int slot) {
		return buttons.get(slot);
	}

	public Page getPage() {
		return page;
	}

	public int getPageNumber() {
		return pageNumber;
	}

	public void setButtonItem(int slot, ItemStack item) {
		inventory.setItem(slot, item);
	}
}
