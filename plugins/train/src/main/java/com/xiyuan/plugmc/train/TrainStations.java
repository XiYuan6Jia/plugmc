package com.xiyuan.plugmc.train;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 站点：一个铁轨方块 + 一个名字 + 停靠时长。
 *
 * <p>站点不占方块、不靠特殊方块识别，纯粹记坐标 —— 管理员以后想改站台外观、
 * 换站牌样式都不会把站点弄丢，拆掉铁轨也只是这条站暂时不可用。</p>
 */
public final class TrainStations {
	private final File file;
	private final Map<String, TrainStation> stations = new LinkedHashMap<>();

	public TrainStations(File dataFolder) {
		this.file = new File(dataFolder, "stations.yml");
	}

	/** 一个停靠点。坐标是铁轨方块本身的整数坐标。 */
	public record TrainStation(String name, java.util.UUID worldId, int x, int y, int z, int dwellSeconds) {
		public BlockKeyRef key() {
			return new BlockKeyRef(x, y, z);
		}

		public World world() {
			return Bukkit.getWorld(worldId);
		}

		public Location location() {
			World world = world();
			return world == null ? null : new Location(world, x + 0.5, y + 0.0625, z + 0.5);
		}

		public boolean usable() {
			World world = world();
			if (world == null) {
				return false;
			}
			return RailPath.isRail(world.getBlockAt(x, y, z).getType());
		}
	}

	/** 与 {@link RailPath.BlockKey} 同形的轻量坐标，避免这里依赖寻路类。 */
	public record BlockKeyRef(int x, int y, int z) {
	}

	public void load() {
		stations.clear();
		if (!file.isFile()) {
			return;
		}
		YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
		ConfigurationSection root = yaml.getConfigurationSection("stations");
		if (root == null) {
			return;
		}
		for (String key : root.getKeys(false)) {
			ConfigurationSection section = root.getConfigurationSection(key);
			if (section == null) {
				continue;
			}
			String world = section.getString("world");
			if (world == null) {
				continue;
			}
			java.util.UUID worldId;
			try {
				worldId = java.util.UUID.fromString(world);
			} catch (IllegalArgumentException ex) {
				continue;
			}
			String name = section.getString("name", key);
			stations.put(key.toLowerCase(Locale.ROOT), new TrainStation(
				name,
				worldId,
				section.getInt("x"),
				section.getInt("y"),
				section.getInt("z"),
				Math.max(1, section.getInt("dwell", 8))
			));
		}
	}

	public void save() {
		YamlConfiguration yaml = new YamlConfiguration();
		for (Map.Entry<String, TrainStation> entry : stations.entrySet()) {
			TrainStation station = entry.getValue();
			String path = "stations." + entry.getKey();
			yaml.set(path + ".name", station.name());
			yaml.set(path + ".world", station.worldId().toString());
			yaml.set(path + ".x", station.x());
			yaml.set(path + ".y", station.y());
			yaml.set(path + ".z", station.z());
			yaml.set(path + ".dwell", station.dwellSeconds());
		}
		try {
			File parent = file.getParentFile();
			if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
				return;
			}
			yaml.save(file);
		} catch (IOException ex) {
			Bukkit.getLogger().warning("[train] 保存站点失败：" + ex.getMessage());
		}
	}

	public TrainStation get(String name) {
		return name == null ? null : stations.get(name.toLowerCase(Locale.ROOT));
	}

	public void put(TrainStation station) {
		stations.put(station.name().toLowerCase(Locale.ROOT), station);
	}

	public TrainStation remove(String name) {
		return name == null ? null : stations.remove(name.toLowerCase(Locale.ROOT));
	}

	public List<String> names() {
		List<String> list = new ArrayList<>();
		for (TrainStation station : stations.values()) {
			list.add(station.name());
		}
		return list;
	}

	public List<TrainStation> all() {
		return List.copyOf(stations.values());
	}

	public int size() {
		return stations.size();
	}
}
