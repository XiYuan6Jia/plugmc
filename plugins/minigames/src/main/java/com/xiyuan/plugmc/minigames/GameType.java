package com.xiyuan.plugmc.minigames;

import org.bukkit.Material;

/** 三款小游戏的固定元数据。 */
public enum GameType {
	BLACKJACK("21点", Material.PAPER, "blackjack", Material.GOLD_BLOCK, "&6&l21点"),
	WHEEL("幸运转盘", Material.TARGET, "wheel", Material.LODESTONE, "&e&l幸运转盘"),
	SLOTS("老虎机", Material.LEVER, "slots", Material.IRON_BLOCK, "&b&l老虎机");

	private final String displayName;
	private final Material icon;
	private final String command;
	private final Material counterMaterial;
	private final String venueTitle;

	GameType(
		String displayName,
		Material icon,
		String command,
		Material counterMaterial,
		String venueTitle
	) {
		this.displayName = displayName;
		this.icon = icon;
		this.command = command;
		this.counterMaterial = counterMaterial;
		this.venueTitle = venueTitle;
	}

	public String displayName() {
		return displayName;
	}

	public Material icon() {
		return icon;
	}

	public String command() {
		return command;
	}

	/** 实体店里可以右键的柜台方块。 */
	public Material counterMaterial() {
		return counterMaterial;
	}

	public String venueTitle() {
		return venueTitle;
	}

	/** 每局下注/派彩用的账本原因。 */
	public String betReason() {
		return "GAME_" + name() + "_BET";
	}

	public String winReason() {
		return "GAME_" + name() + "_WIN";
	}

	public static GameType parse(String raw) {
		if (raw == null) {
			return null;
		}
		for (GameType game : values()) {
			if (game.command.equalsIgnoreCase(raw) || game.name().equalsIgnoreCase(raw)) {
				return game;
			}
		}
		return null;
	}
}
