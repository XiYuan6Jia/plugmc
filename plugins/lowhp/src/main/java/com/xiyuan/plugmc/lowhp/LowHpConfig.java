package com.xiyuan.plugmc.lowhp;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 低血量提示音配置。阈值按最大生命值的百分比计算，播放间隔用冷却控制。
 */
public record LowHpConfig(
	boolean enabled,
	double thresholdPercent,
	double healPercent,
	int cooldownSeconds,
	double volume,
	float pitch,
	boolean stopWhenHealed,
	boolean notifyPlayer,
	Set<Alert> alerts,
	String packUrl,
	String bindAddress,
	int port,
	List<Integer> minFormat,
	List<Integer> maxFormat,
	int joinDelayTicks,
	boolean required,
	String prompt
) {
	public LowHpConfig {
		thresholdPercent = clamp(thresholdPercent, 0.05, 0.95);
		healPercent = clamp(healPercent, thresholdPercent, 1.0);
		cooldownSeconds = Math.max(0, Math.min(3600, cooldownSeconds));
		volume = Math.max(0.1, Math.min(4.0, volume));
		pitch = (float) Math.max(0.5, Math.min(2.0, pitch));
		port = Math.max(1, Math.min(65535, port));
		minFormat = normalizeFormat(minFormat, 97, 0);
		maxFormat = normalizeFormat(maxFormat, 97, 99);
		joinDelayTicks = Math.max(1, Math.min(600, joinDelayTicks));
		alerts = Set.copyOf(alerts);
	}

	/**
	 * 资源包格式统一成 [major, minor] 两个整数。
	 */
	private static List<Integer> normalizeFormat(List<Integer> raw, int major, int minor) {
		if (raw == null || raw.isEmpty()) {
			return List.of(major, minor);
		}
		int first = Math.max(1, raw.get(0));
		int second = raw.size() > 1 ? Math.max(0, raw.get(1)) : minor;
		return List.of(first, second);
	}

	public static LowHpConfig load(FileConfiguration config) {
		Set<Alert> alerts = new LinkedHashSet<>();
		for (String raw : config.getStringList("alerts.enabled")) {
			Alert alert = Alert.byId(raw);
			if (alert != null) {
				alerts.add(alert);
			}
		}
		if (alerts.isEmpty()) {
			alerts.add(Alert.A);
			alerts.add(Alert.B);
		}
		double thresholdPercent = config.getDouble("trigger.threshold-percent", 0.3);
		return new LowHpConfig(
			config.getBoolean("enabled", true),
			thresholdPercent,
			config.getDouble("trigger.heal-percent", thresholdPercent + 0.15),
			config.getInt("trigger.cooldown-seconds", 120),
			config.getDouble("playback.volume", 1.0),
			(float) config.getDouble("playback.pitch", 1.0),
			config.getBoolean("playback.stop-when-healed", true),
			config.getBoolean("playback.notify-player", true),
			alerts,
			config.getString("pack.public-url", "http://q1.minekuai.com:20103/lowhp-alert.zip"),
			config.getString("pack.bind-address", ""),
			config.getInt("pack.port", 20103),
			config.getIntegerList("pack.min-format"),
			config.getIntegerList("pack.max-format"),
			config.getInt("pack.join-delay-ticks", 60),
			config.getBoolean("pack.required", false),
			config.getString("pack.prompt", "&6服务器提示音资源包（约 2.5 MB）")
		);
	}

	public List<Alert> alertList() {
		return List.copyOf(alerts);
	}

	private static double clamp(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}
}
