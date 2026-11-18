package com.xiyuan.plugmc.goathorn;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * 插件配置。
 *
 * @param enabled          总开关
 * @param cooldownSeconds  吹奏冷却（秒）
 * @param maxAnimalsPerHorn 单只羊角最多绑多少只动物（0 = 不限）
 * @param playSound        吹奏成功后补一声钟声
 * @param particles        吹奏传送时放粒子
 */
record HornConfig(boolean enabled, int cooldownSeconds, int maxAnimalsPerHorn, boolean playSound, boolean particles) {

	static HornConfig load(FileConfiguration config) {
		boolean enabled = config.getBoolean("goathorn.enabled", true);
		int cooldown = clamp(config.getInt("goathorn.cooldown-seconds", 5), 0, 3600);
		int maxAnimals = Math.max(0, config.getInt("goathorn.max-animals-per-horn", 0));
		boolean sound = config.getBoolean("goathorn.play-sound", true);
		boolean particles = config.getBoolean("goathorn.particles", true);
		return new HornConfig(enabled, cooldown, maxAnimals, sound, particles);
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}
}
