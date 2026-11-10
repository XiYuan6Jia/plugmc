package com.xiyuan.plugmc.minigames;

import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/** 小游戏界面的状态载体。 */
public final class GamesHolder implements InventoryHolder {
	public enum View {
		MENU,
		BLACKJACK,
		WHEEL,
		SLOTS
	}

	private final Inventory inventory;
	private final View view;
	private final GameType game;
	private int bet;
	private BlackjackTable table;
	private boolean spinning;
	private final int[] reels = {0, 0, 0};
	private int highlight = -1;
	private String status = "";
	private boolean settled = true;

	public GamesHolder(View view, GameType game, String title, int size, int bet) {
		this.view = view;
		this.game = game;
		this.bet = bet;
		this.inventory = Bukkit.createInventory(this, size, Text.of(title));
	}

	@Override
	public Inventory getInventory() {
		return inventory;
	}

	public View view() {
		return view;
	}

	public GameType game() {
		return game;
	}

	public int bet() {
		return bet;
	}

	public void bet(int bet) {
		this.bet = bet;
	}

	public BlackjackTable table() {
		return table;
	}

	public void table(BlackjackTable table) {
		this.table = table;
	}

	public boolean spinning() {
		return spinning;
	}

	public void spinning(boolean spinning) {
		this.spinning = spinning;
	}

	public int[] reels() {
		return reels;
	}

	public int highlight() {
		return highlight;
	}

	public void highlight(int highlight) {
		this.highlight = highlight;
	}

	public String status() {
		return status;
	}

	public void status(String status) {
		this.status = status == null ? "" : status;
	}

	public boolean settled() {
		return settled;
	}

	public void settled(boolean settled) {
		this.settled = settled;
	}
}
