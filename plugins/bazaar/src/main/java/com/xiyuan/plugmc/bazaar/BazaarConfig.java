package com.xiyuan.plugmc.bazaar;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 商业街配置。
 *
 * <p>摊位分两类：<b>固定摊位</b>（官方/银行，由 {@code stalls.fixed} 显式列出，永远存在、不参与分配）
 * 与 <b>玩家摊位</b>（{@code stalls.player} 的矩形区域内按网格自动铺出，玩家发布交易时占用）。
 * 分开配是因为固定摊位的坐标要和建筑对齐、需要人工摆，而玩家摊位是一整片区域，按格网自动生成更省事。</p>
 */
public record BazaarConfig(
	String worldName,
	int floorY,
	int refreshTicks,
	List<FixedStall> fixedStalls,
	PlayerStalls playerStalls,
	List<Decor> decors,
	int maxDeals
) {
	public BazaarConfig {
		worldName = worldName == null || worldName.isBlank() ? "hub" : worldName.trim();
		refreshTicks = clamp(refreshTicks, 10, 1200);
		fixedStalls = List.copyOf(fixedStalls);
		decors = List.copyOf(decors);
		maxDeals = clamp(maxDeals, 1, 24);
	}

	public static BazaarConfig load(FileConfiguration config) {
		List<FixedStall> fixed = new ArrayList<>();
		ConfigurationSection fixedSection = config.getConfigurationSection("stalls.fixed");
		if (fixedSection != null) {
			for (String key : fixedSection.getKeys(false)) {
				ConfigurationSection one = fixedSection.getConfigurationSection(key);
				if (one == null) {
					continue;
				}
				fixed.add(new FixedStall(
					key,
					one.getString("kind", "official"),
					one.getInt("x"),
					one.getInt("z"),
					(one.getInt("facing", 180) % 360 + 360) % 360
				));
			}
		}

		PlayerStalls player = new PlayerStalls(
			config.getInt("stalls.player.x0", 0),
			config.getInt("stalls.player.z0", 0),
			config.getInt("stalls.player.x1", -1),
			config.getInt("stalls.player.z1", -1),
			config.getInt("stalls.player.spacing", 6),
			config.getInt("stalls.player.facing", 180)
		);

		List<Decor> decors = new ArrayList<>();
		for (Map<?, ?> raw : config.getMapList("stalls.decor")) {
			Map<String, Object> map = new LinkedHashMap<>();
			for (Map.Entry<?, ?> entry : raw.entrySet()) {
				map.put(String.valueOf(entry.getKey()), entry.getValue());
			}
			Decor decor = Decor.fromMap(map);
			if (decor != null) {
				decors.add(decor);
			}
		}
		if (decors.isEmpty()) {
			decors = Decor.defaults();
		}

		List<String> lines = config.getStringList("stalls.sign-lines");
		if (lines.isEmpty()) {
			lines = List.of("&f&l%player%", "&7%kind_line%", "&e%item_line%", "&8%count_line%");
		}

		return new BazaarConfig(
			config.getString("world.name", "hub"),
			config.getInt("world.floor-y", 64),
			config.getInt("refresh.ticks", 40),
			fixed,
			player,
			decors,
			config.getInt("stalls.sign-max-deals", 6)
		);
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	/** 固定摊位：官方摊位、银行摊位等，坐标写死，不参与分配。 */
	public record FixedStall(String id, String kind, int x, int z, int facing) {
	}

	/** 玩家摊位区域：x0/z0 起点、x1/z1 终点、spacing 格距，按网格铺开。 */
	public record PlayerStalls(int x0, int z0, int x1, int z1, int spacing, int facing) {
		public PlayerStalls {
			spacing = Math.max(3, spacing);
		}

		/** 区域是否有效（终点必须严格大于起点），配置没写时返回空列表。 */
		public List<int[]> slots() {
			List<int[]> result = new ArrayList<>();
			if (x1 <= x0 || z1 <= z0) {
				return result;
			}
			for (int x = x0; x + 2 <= x1; x += spacing) {
				for (int z = z0; z + 2 <= z1; z += spacing) {
					result.add(new int[] {x, z});
				}
			}
			return result;
		}
	}

	/** 一套摊位装饰：石台下垫、四角柱、顶棚、地毯。每次分配随机挑一套。 */
	public record Decor(String id, String base, String pillar, String canopy, String carpet) {
		static Decor fromMap(Map<String, Object> map) {
			String id = String.valueOf(map.getOrDefault("id", "")).trim().toLowerCase(Locale.ROOT);
			if (id.isEmpty()) {
				return null;
			}
			return new Decor(
				id,
				String.valueOf(map.getOrDefault("base", "minecraft:stone_bricks")),
				String.valueOf(map.getOrDefault("pillar", "minecraft:quartz_pillar[axis=y]")),
				String.valueOf(map.getOrDefault("canopy", "minecraft:smooth_quartz")),
				String.valueOf(map.getOrDefault("carpet", "minecraft:white_wool"))
			);
		}

		static List<Decor> defaults() {
			return List.of(
				new Decor("quartz", "minecraft:stone_bricks", "minecraft:quartz_pillar[axis=y]",
					"minecraft:smooth_quartz", "minecraft:white_wool"),
				new Decor("oak", "minecraft:stone_bricks", "minecraft:oak_log[axis=y]",
					"minecraft:oak_planks", "minecraft:oak_carpet"),
				new Decor("spruce", "minecraft:stone_bricks", "minecraft:spruce_log[axis=y]",
					"minecraft:spruce_planks", "minecraft:spruce_carpet"),
				new Decor("garden", "minecraft:moss_block", "minecraft:oak_log[axis=y]",
					"minecraft:oak_leaves[persistent=true]", "minecraft:green_carpet"),
				new Decor("nether", "minecraft:polished_blackstone_bricks", "minecraft:blackstone",
					"minecraft:polished_blackstone", "minecraft:red_carpet"),
				new Decor("copper", "minecraft:smooth_stone", "minecraft:oxidized_copper",
					"minecraft:waxed_oxidized_cut_copper", "minecraft:cyan_carpet")
			);
		}
	}
}
