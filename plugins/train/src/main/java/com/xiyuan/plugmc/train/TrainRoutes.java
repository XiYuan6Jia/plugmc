package com.xiyuan.plugmc.train;

import org.bukkit.Bukkit;
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
 * 路线：一串站点名字，循环顺序就是停靠顺序。
 *
 * <p>站点本身单独存（{@link TrainStations}），路线只记名字。这样同一个站可以被
 * 好几条路线共用，改站名时只需要小心一处。</p>
 */
public final class TrainRoutes {
	private final File file;
	private final Map<String, TrainRoute> routes = new LinkedHashMap<>();

	public TrainRoutes(File dataFolder) {
		this.file = new File(dataFolder, "routes.yml");
	}

	/** 一条线路。{@code dwellSeconds} 为空表示各站用站点自己的停靠时长。 */
	public record TrainRoute(String name, List<String> stations, boolean loop, List<Integer> dwellSeconds) {
		public int dwellAt(int index) {
			if (dwellSeconds == null || index < 0 || index >= dwellSeconds.size()) {
				return -1;
			}
			Integer value = dwellSeconds.get(index);
			return value == null ? -1 : value;
		}
	}

	public void load() {
		routes.clear();
		if (!file.isFile()) {
			return;
		}
		YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
		ConfigurationSection root = yaml.getConfigurationSection("routes");
		if (root == null) {
			return;
		}
		for (String key : root.getKeys(false)) {
			ConfigurationSection section = root.getConfigurationSection(key);
			if (section == null) {
				continue;
			}
			List<String> stations = new ArrayList<>(section.getStringList("stations"));
			List<Integer> dwell = new ArrayList<>();
			for (Object raw : section.getList("dwell", List.of())) {
				dwell.add(raw instanceof Number number ? number.intValue() : Integer.valueOf(-1));
			}
			routes.put(key.toLowerCase(Locale.ROOT), new TrainRoute(
				section.getString("name", key),
				List.copyOf(stations),
				section.getBoolean("loop", true),
				List.copyOf(dwell)
			));
		}
	}

	public void save() {
		YamlConfiguration yaml = new YamlConfiguration();
		for (Map.Entry<String, TrainRoute> entry : routes.entrySet()) {
			TrainRoute route = entry.getValue();
			String path = "routes." + entry.getKey();
			yaml.set(path + ".name", route.name());
			yaml.set(path + ".stations", route.stations());
			yaml.set(path + ".loop", route.loop());
			yaml.set(path + ".dwell", route.dwellSeconds());
		}
		try {
			File parent = file.getParentFile();
			if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
				return;
			}
			yaml.save(file);
		} catch (IOException ex) {
			Bukkit.getLogger().warning("[train] 保存路线失败：" + ex.getMessage());
		}
	}

	public TrainRoute get(String name) {
		return name == null ? null : routes.get(name.toLowerCase(Locale.ROOT));
	}

	public void put(TrainRoute route) {
		routes.put(route.name().toLowerCase(Locale.ROOT), route);
	}

	public TrainRoute remove(String name) {
		return name == null ? null : routes.remove(name.toLowerCase(Locale.ROOT));
	}

	public List<String> names() {
		List<String> list = new ArrayList<>();
		for (TrainRoute route : routes.values()) {
			list.add(route.name());
		}
		return list;
	}

	public List<TrainRoute> all() {
		return List.copyOf(routes.values());
	}

	public int size() {
		return routes.size();
	}
}
