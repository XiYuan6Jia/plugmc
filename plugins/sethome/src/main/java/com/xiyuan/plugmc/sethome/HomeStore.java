package com.xiyuan.plugmc.sethome;

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
import java.util.UUID;
import java.util.logging.Logger;

/**
 * 家园数据：homes.yml 按玩家 UUID 保存，世界按名称引用。
 */
public final class HomeStore {
	private final File file;
	private final Logger logger;
	private final Map<UUID, PlayerHomes> players = new LinkedHashMap<>();

	public HomeStore(File dataFolder, Logger logger) {
		this.file = new File(dataFolder, "homes.yml");
		this.logger = logger;
	}

	public static String normalizeName(String raw) {
		if (raw == null || raw.isBlank()) {
			return "home";
		}
		return raw.trim().toLowerCase(Locale.ROOT);
	}

	public static boolean validName(String name) {
		return name != null && name.matches("[a-z0-9_\\-\\u4e00-\\u9fa5]{1,16}");
	}

	public void load() {
		players.clear();
		if (!file.isFile()) {
			return;
		}
		YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
		ConfigurationSection root = yaml.getConfigurationSection("players");
		if (root == null) {
			return;
		}
		for (String uuidText : root.getKeys(false)) {
			UUID id;
			try {
				id = UUID.fromString(uuidText);
			} catch (IllegalArgumentException exception) {
				logger.warning("homes.yml 中存在无效 UUID：" + uuidText);
				continue;
			}
			ConfigurationSection playerSection = root.getConfigurationSection(uuidText);
			if (playerSection == null) {
				continue;
			}
			Map<String, Location> homes = new LinkedHashMap<>();
			ConfigurationSection homesSection = playerSection.getConfigurationSection("homes");
			if (homesSection != null) {
				for (String name : homesSection.getKeys(false)) {
					Location location = readLocation(homesSection.getConfigurationSection(name));
					if (location == null) {
						logger.warning("家园 " + uuidText + "/" + name + " 的世界不存在，已跳过");
						continue;
					}
					homes.put(normalizeName(name), location);
				}
			}
			players.put(id, new PlayerHomes(playerSection.getString("name", ""), homes));
		}
		logger.info("已加载 " + players.size() + " 名玩家的家园数据");
	}

	private static Location readLocation(ConfigurationSection section) {
		if (section == null) {
			return null;
		}
		String worldName = section.getString("world");
		World world = worldName == null ? null : Bukkit.getWorld(worldName);
		if (world == null) {
			return null;
		}
		return new Location(
			world,
			section.getDouble("x"),
			section.getDouble("y"),
			section.getDouble("z"),
			(float) section.getDouble("yaw"),
			(float) section.getDouble("pitch")
		);
	}

	public void save() {
		YamlConfiguration yaml = new YamlConfiguration();
		ConfigurationSection root = yaml.createSection("players");
		for (Map.Entry<UUID, PlayerHomes> entry : players.entrySet()) {
			ConfigurationSection playerSection = root.createSection(entry.getKey().toString());
			playerSection.set("name", entry.getValue().playerName());
			ConfigurationSection homesSection = playerSection.createSection("homes");
			for (Map.Entry<String, Location> home : entry.getValue().homes().entrySet()) {
				Location location = home.getValue();
				ConfigurationSection homeSection = homesSection.createSection(home.getKey());
				World world = location.getWorld();
				homeSection.set("world", world == null ? "world" : world.getName());
				homeSection.set("x", round(location.getX()));
				homeSection.set("y", round(location.getY()));
				homeSection.set("z", round(location.getZ()));
				homeSection.set("yaw", round(location.getYaw()));
				homeSection.set("pitch", round(location.getPitch()));
			}
		}
		try {
			File parent = file.getParentFile();
			if (parent != null) {
				parent.mkdirs();
			}
			yaml.save(file);
		} catch (IOException exception) {
			logger.warning("保存 homes.yml 失败：" + exception.getMessage());
		}
	}

	private static double round(double value) {
		return Math.round(value * 1000.0) / 1000.0;
	}

	public Location home(UUID playerId, String name) {
		PlayerHomes data = players.get(playerId);
		return data == null ? null : data.homes().get(normalizeName(name));
	}

	public boolean has(UUID playerId, String name) {
		return home(playerId, name) != null;
	}

	public int count(UUID playerId) {
		PlayerHomes data = players.get(playerId);
		return data == null ? 0 : data.homes().size();
	}

	public List<String> names(UUID playerId) {
		PlayerHomes data = players.get(playerId);
		return data == null ? List.of() : List.copyOf(data.homes().keySet());
	}

	public void setHome(UUID playerId, String playerName, String name, Location location) {
		PlayerHomes data = players.computeIfAbsent(playerId, id -> new PlayerHomes(playerName, new LinkedHashMap<>()));
		data.homes().put(normalizeName(name), location.clone());
		data.playerName(playerName);
		save();
	}

	public boolean remove(UUID playerId, String name) {
		PlayerHomes data = players.get(playerId);
		if (data == null || data.homes().remove(normalizeName(name)) == null) {
			return false;
		}
		save();
		return true;
	}

	public static final class PlayerHomes {
		private String playerName;
		private final Map<String, Location> homes;

		public PlayerHomes(String playerName, Map<String, Location> homes) {
			this.playerName = playerName == null ? "" : playerName;
			this.homes = new LinkedHashMap<>(homes);
		}

		public String playerName() {
			return playerName;
		}

		public void playerName(String playerName) {
			this.playerName = playerName == null ? "" : playerName;
		}

		public Map<String, Location> homes() {
			return homes;
		}
	}
}
