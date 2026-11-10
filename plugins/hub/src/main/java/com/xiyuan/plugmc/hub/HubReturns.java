package com.xiyuan.plugmc.hub;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 「进入主城前的位置」记录，落盘在 plugins/hub/returns.yml，重启后 /hub back 依然可用。
 * 只存世界名与坐标，取用时才解析世界，避免启动顺序导致读不到世界。
 */
public final class HubReturns {
	public record Point(String world, double x, double y, double z, float yaw, float pitch) {
	}

	private final File file;
	private final Logger logger;
	private final Map<UUID, Point> points = new HashMap<>();

	public HubReturns(File folder, Logger logger) {
		this.file = new File(folder, "returns.yml");
		this.logger = logger;
	}

	public void load() {
		points.clear();
		if (!file.isFile()) {
			return;
		}
		YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
		ConfigurationSection players = yaml.getConfigurationSection("players");
		if (players == null) {
			return;
		}
		for (String key : players.getKeys(false)) {
			ConfigurationSection section = players.getConfigurationSection(key);
			if (section == null) {
				continue;
			}
			try {
				points.put(UUID.fromString(key), new Point(
					section.getString("world", ""),
					section.getDouble("x"),
					section.getDouble("y"),
					section.getDouble("z"),
					(float) section.getDouble("yaw"),
					(float) section.getDouble("pitch")
				));
			} catch (IllegalArgumentException exception) {
				logger.warning("返回点记录里的 UUID 无效：" + key);
			}
		}
		logger.info("已恢复 " + points.size() + " 个主城返回点");
	}

	public void remember(UUID playerId, Location location) {
		if (location == null || location.getWorld() == null) {
			return;
		}
		put(playerId, new Point(
			location.getWorld().getName(),
			location.getX(),
			location.getY(),
			location.getZ(),
			location.getYaw(),
			location.getPitch()
		));
		save();
	}

	/** 取出并删除返回点；世界未加载时返回 null。 */
	public Location take(UUID playerId) {
		Point point = points.remove(playerId);
		if (point == null) {
			return null;
		}
		save();
		World world = Bukkit.getWorld(point.world());
		if (world == null) {
			logger.warning("返回点所在世界未加载：" + point.world());
			return null;
		}
		return new Location(world, point.x(), point.y(), point.z(), point.yaw(), point.pitch());
	}

	public void forget(UUID playerId) {
		if (points.remove(playerId) != null) {
			save();
		}
	}

	public int size() {
		return points.size();
	}

	void put(UUID playerId, Point point) {
		points.put(playerId, point);
	}

	Point point(UUID playerId) {
		return points.get(playerId);
	}

	void save() {
		YamlConfiguration yaml = new YamlConfiguration();
		for (Map.Entry<UUID, Point> entry : points.entrySet()) {
			Point point = entry.getValue();
			String path = "players." + entry.getKey() + ".";
			yaml.set(path + "world", point.world());
			yaml.set(path + "x", point.x());
			yaml.set(path + "y", point.y());
			yaml.set(path + "z", point.z());
			yaml.set(path + "yaw", (double) point.yaw());
			yaml.set(path + "pitch", (double) point.pitch());
		}
		try {
			yaml.save(file);
		} catch (IOException exception) {
			logger.log(Level.WARNING, "保存主城返回点失败", exception);
		}
	}
}
