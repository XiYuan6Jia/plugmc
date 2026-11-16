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
		COSMETIC,
		VAULT,
		/** 列车列表（{@code subject} 为空）与单条列车配置（{@code subject} 为列车名）。 */
		TRAIN,
		/** 给某条列车挑路线，{@code subject} 为列车名。 */
		TRAIN_ROUTE
	}

	private final Inventory inventory;
	private final Map<Integer, MenuButton> buttons;
	private final Page page;
	private final int pageNumber;
	/**
	 * 这个界面在讲哪一条东西（列车名）。
	 *
	 * <p>点击回调只会拿到「点的是第几格」，详情页需要知道自己在配置哪一列车，
	 * 所以把名字挂在 holder 上，而不是塞进 slot 里做隐式编码 —— 后者一旦格子数变了就全错位。</p>
	 */
	private final String subject;

	public MenuHolder(int size, String title, Map<Integer, MenuButton> buttons, Page page, int pageNumber) {
		this(size, title, buttons, page, pageNumber, "");
	}

	public MenuHolder(int size, String title, Map<Integer, MenuButton> buttons, Page page, int pageNumber,
					  String subject) {
		this.buttons = buttons;
		this.page = page;
		this.pageNumber = pageNumber;
		this.subject = subject == null ? "" : subject;
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

	/** 详情页正在配置的那条列车名；列表页与其它页面为空串。 */
	public String getSubject() {
		return subject;
	}

	public void setButtonItem(int slot, ItemStack item) {
		inventory.setItem(slot, item);
	}
}
