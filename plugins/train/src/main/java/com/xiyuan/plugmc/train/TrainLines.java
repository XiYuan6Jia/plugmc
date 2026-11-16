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
import java.util.UUID;

/**
 * 命名列车：谁的车、叫什么、跑哪条线、车厢间距多少。
 *
 * <p>为什么要单独一张表：拴绳连接只说明「这几台矿车是一起的」，没法区分两条不同的列车，
 * 也没法回答「这列是谁的」。所以玩家把一列连好的矿车登记成一个有名字的列车，
 * 菜单里按名字列出来，配置与开关都记在这张表上。</p>
 *
 * <p>存的是<b>某一节车厢的 UUID</b>而不是车头：双向列车跑起来会把车头换到另一端
 * （{@code flipHead}），车头 UUID 会变，但那一节永远是这列车的一部分。查找时统一
 * 用 {@code consistOf} 判「这节在不在这列里」。</p>
 */
public final class TrainLines {
	/** 名字 → 列车。键是小写名字，避免「快车」和「快車」之外再多出大小写重复。 */
	private final Map<String, TrainLine> lines = new LinkedHashMap<>();
	private final File file;

	public TrainLines(File dataFolder) {
		this.file = new File(dataFolder, "trains.yml");
	}

	/**
	 * 一条列车。
	 *
	 * @param cart      登记时用的那一节车厢（任取一节，够用来认出整列）
	 * @param routeName 绑定的路线名，空串表示还没绑
	 * @param spacing  这条列车自己的车厢间距（格），0 表示用配置里的默认值
	 */
	public record TrainLine(
		String name,
		UUID ownerId,
		String ownerName,
		UUID cart,
		String routeName,
		double spacing
	) {
		public TrainLine withRoute(String route) {
			return new TrainLine(name, ownerId, ownerName, cart, route == null ? "" : route, spacing);
		}

		public TrainLine withSpacing(double value) {
			return new TrainLine(name, ownerId, ownerName, cart, routeName, value);
		}

		public TrainLine withCart(UUID value) {
			return new TrainLine(name, ownerId, ownerName, value, routeName, spacing);
		}
	}

	public void load() {
		lines.clear();
		if (!file.isFile()) {
			return;
		}
		YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
		ConfigurationSection root = yaml.getConfigurationSection("trains");
		if (root == null) {
			return;
		}
		for (String key : root.getKeys(false)) {
			ConfigurationSection section = root.getConfigurationSection(key);
			if (section == null) {
				continue;
			}
			try {
				UUID owner = UUID.fromString(section.getString("owner", ""));
				UUID cart = UUID.fromString(section.getString("cart", ""));
				String name = section.getString("name", key);
				TrainLine line = new TrainLine(
					name,
					owner,
					section.getString("owner-name", "?"),
					cart,
					section.getString("route", ""),
					section.getDouble("spacing", 0.0)
				);
				lines.put(key.toLowerCase(Locale.ROOT), line);
			} catch (IllegalArgumentException | NullPointerException ignored) {
				// 手改坏了一行就当没这条，别让整份文件读不出来
			}
		}
	}

	public void save() {
		YamlConfiguration yaml = new YamlConfiguration();
		for (Map.Entry<String, TrainLine> entry : lines.entrySet()) {
			TrainLine line = entry.getValue();
			String path = "trains." + entry.getKey();
			yaml.set(path + ".name", line.name());
			yaml.set(path + ".owner", line.ownerId().toString());
			yaml.set(path + ".owner-name", line.ownerName());
			yaml.set(path + ".cart", line.cart().toString());
			yaml.set(path + ".route", line.routeName());
			yaml.set(path + ".spacing", line.spacing());
		}
		try {
			File parent = file.getParentFile();
			if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
				return;
			}
			yaml.save(file);
		} catch (IOException ex) {
			Bukkit.getLogger().warning("[train] 保存列车失败：" + ex.getMessage());
		}
	}

	public TrainLine get(String name) {
		return name == null ? null : lines.get(name.toLowerCase(Locale.ROOT));
	}

	public boolean exists(String name) {
		return get(name) != null;
	}

	public void put(TrainLine line) {
		lines.put(line.name().toLowerCase(Locale.ROOT), line);
	}

	public TrainLine remove(String name) {
		return name == null ? null : lines.remove(name.toLowerCase(Locale.ROOT));
	}

	/** 改了名字要连键一起换，否则旧名字还能查到。 */
	public void rename(TrainLine old, TrainLine updated) {
		lines.remove(old.name().toLowerCase(Locale.ROOT));
		put(updated);
	}

	public List<TrainLine> all() {
		return List.copyOf(lines.values());
	}

	/** 某个玩家的全部列车，按名字排序，保证菜单每次打开顺序一致。 */
	public List<TrainLine> of(UUID owner) {
		List<TrainLine> mine = new ArrayList<>();
		for (TrainLine line : lines.values()) {
			if (line.ownerId().equals(owner)) {
				mine.add(line);
			}
		}
		mine.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.name(), b.name()));
		return mine;
	}

	public int size() {
		return lines.size();
	}
}
