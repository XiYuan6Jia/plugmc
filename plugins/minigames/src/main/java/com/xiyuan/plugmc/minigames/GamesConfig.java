package com.xiyuan.plugmc.minigames;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * 小游戏配置：注额范围、冷却、三款游戏开关与主城实体店坐标。
 */
public record GamesConfig(
	int minBet,
	int maxBet,
	int cooldownSeconds,
	boolean blackjackEnabled,
	int blackjackBet,
	double blackjackPayout,
	boolean wheelEnabled,
	int wheelBet,
	boolean slotsEnabled,
	int slotsBet,
	boolean venueEnabled,
	String venueWorld,
	int venueFloorY,
	int venueCenterX,
	int venueCenterZ,
	int venueSpacing
) {
	public GamesConfig {
		minBet = Math.max(1, minBet);
		maxBet = Math.max(minBet, maxBet);
		cooldownSeconds = Math.max(0, cooldownSeconds);
		venueSpacing = Math.max(8, venueSpacing);
		venueWorld = venueWorld == null || venueWorld.isBlank() ? "hub" : venueWorld.trim();
	}

	public static GamesConfig load(FileConfiguration config) {
		return new GamesConfig(
			config.getInt("limits.min-bet", 1),
			config.getInt("limits.max-bet", 100),
			config.getInt("limits.cooldown-seconds", 2),
			config.getBoolean("games.blackjack.enabled", true),
			config.getInt("games.blackjack.default-bet", 10),
			config.getDouble("games.blackjack.blackjack-payout", 1.5),
			config.getBoolean("games.wheel.enabled", true),
			config.getInt("games.wheel.default-bet", 10),
			config.getBoolean("games.slots.enabled", true),
			config.getInt("games.slots.default-bet", 5),
			config.getBoolean("venue.enabled", true),
			config.getString("venue.world", "hub"),
			config.getInt("venue.floor-y", 64),
			config.getInt("venue.center-x", 0),
			config.getInt("venue.center-z", -40),
			config.getInt("venue.spacing", 14)
		);
	}

	public int clampBet(int bet) {
		return Math.max(minBet, Math.min(maxBet, bet));
	}

	public boolean enabled(GameType game) {
		return switch (game) {
			case BLACKJACK -> blackjackEnabled;
			case WHEEL -> wheelEnabled;
			case SLOTS -> slotsEnabled;
		};
	}

	public int defaultBet(GameType game) {
		return clampBet(switch (game) {
			case BLACKJACK -> blackjackBet;
			case WHEEL -> wheelBet;
			case SLOTS -> slotsBet;
		});
	}
}
