package com.xiyuan.plugmc.ride;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * 骑乘配置。数值都钳制到安全范围。
 */
public record RideConfig(
	boolean enabled,
	int cooldownSeconds,
	double maxCarrierSpeed,
	boolean dismountOnDamage,
	boolean dismountOnDeath,
	boolean dismountOnTeleport,
	boolean dismountOnWorldChange,
	boolean denyMutualDamage,
	boolean allowSneakDismount
) {
	public RideConfig {
		cooldownSeconds = Math.max(0, Math.min(600, cooldownSeconds));
		maxCarrierSpeed = maxCarrierSpeed <= 0 ? 0 : Math.min(200, maxCarrierSpeed);
	}

	public static RideConfig load(FileConfiguration config) {
		return new RideConfig(
			config.getBoolean("enabled", true),
			config.getInt("cooldown-seconds", 5),
			config.getDouble("max-carrier-speed", 0),
			config.getBoolean("dismount-on-damage", true),
			config.getBoolean("dismount-on-death", true),
			config.getBoolean("dismount-on-teleport", true),
			config.getBoolean("dismount-on-world-change", true),
			config.getBoolean("deny-mutual-damage", true),
			config.getBoolean("allow-sneak-dismount", true)
		);
	}
}
