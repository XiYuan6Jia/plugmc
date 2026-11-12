package com.xiyuan.plugmc.cosmetic;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 配置快照。所有字段在 {@link #load} 时一次性解析成不可变对象，
 * 运行期不再读 {@code getConfig()}，避免热路径上的 YAML 查找。
 */
final class CosmeticConfig {

	/** 一个装饰槽的静态定义。 */
	record Slot(
		String id,
		String name,
		double forward,
		double up,
		double right,
		float scale,
		ItemDisplay.ItemDisplayTransform transform,
		boolean pitchFollow,
		boolean orbit,
		double orbitRadius,
		float spinDegrees,
		String permission
	) {
	}

	/** GUI 里每个槽画在哪个位置。 */
	record GuiLayout(String title, int rows, Map<String, Integer> slotIndex, int infoIndex, int clearIndex, int closeIndex) {
	}

	final boolean enabled;
	final int syncIntervalTicks;
	final double moveThreshold;
	final int teleportDuration;
	final double nearPlayersRadius;
	final float viewRange;
	final boolean selfOnly;

	final List<Slot> slots;
	final Map<String, Slot> slotsById;

	final boolean attributesEnabled;
	final List<String> attributeWhitelist;
	final List<String> attributeBlacklist;
	final boolean attributesStack;

	final boolean blockConsume;
	final boolean blockPlace;
	final boolean blockInteract;
	final GuiLayout gui;

	private CosmeticConfig(
		boolean enabled,
		int syncIntervalTicks,
		double moveThreshold,
		int teleportDuration,
		double nearPlayersRadius,
		float viewRange,
		boolean selfOnly,
		List<Slot> slots,
		boolean attributesEnabled,
		List<String> attributeWhitelist,
		List<String> attributeBlacklist,
		boolean attributesStack,
		boolean blockConsume,
		boolean blockPlace,
		boolean blockInteract,
		GuiLayout gui
	) {
		this.enabled = enabled;
		this.syncIntervalTicks = syncIntervalTicks;
		this.moveThreshold = moveThreshold;
		this.teleportDuration = teleportDuration;
		this.nearPlayersRadius = nearPlayersRadius;
		this.viewRange = viewRange;
		this.selfOnly = selfOnly;
		this.slots = List.copyOf(slots);
		Map<String, Slot> byId = new LinkedHashMap<>();
		for (Slot slot : slots) {
			byId.put(slot.id(), slot);
		}
		this.slotsById = Map.copyOf(byId);
		this.attributesEnabled = attributesEnabled;
		this.attributeWhitelist = List.copyOf(attributeWhitelist);
		this.attributeBlacklist = List.copyOf(attributeBlacklist);
		this.attributesStack = attributesStack;
		this.blockConsume = blockConsume;
		this.blockPlace = blockPlace;
		this.blockInteract = blockInteract;
		this.gui = gui;
	}

	Slot slot(String id) {
		return id == null ? null : slotsById.get(id);
	}

	static CosmeticConfig load(JavaPlugin plugin) {
		var cfg = plugin.getConfig();

		List<Slot> slots = new ArrayList<>();
		ConfigurationSection slotSection = cfg.getConfigurationSection("slots");
		if (slotSection != null) {
			for (String id : slotSection.getKeys(false)) {
				ConfigurationSection one = slotSection.getConfigurationSection(id);
				if (one == null) {
					continue;
				}
				List<Double> offset = one.getDoubleList("offset");
				double forward = offset.size() > 0 ? offset.get(0) : 0.0;
				double up = offset.size() > 1 ? offset.get(1) : 0.0;
				double right = offset.size() > 2 ? offset.get(2) : 0.0;
				slots.add(new Slot(
					id.toLowerCase(Locale.ROOT),
					one.getString("name", id),
					forward,
					up,
					right,
					(float) one.getDouble("scale", 0.5),
					parseTransform(one.getString("transform", "GROUND")),
					one.getBoolean("pitch-follow", false),
					one.getBoolean("orbit", false),
					one.getDouble("orbit-radius", 0.8),
					(float) one.getDouble("spin", 0.0),
					one.getString("permission", "")
				));
			}
		}
		if (slots.isEmpty()) {
			plugin.getLogger().warning("配置里没有任何部位定义，装备外观将不可用");
		}

		Map<String, Integer> slotIndex = new LinkedHashMap<>();
		ConfigurationSection indexSection = cfg.getConfigurationSection("gui.slot-index");
		if (indexSection != null) {
			for (String key : indexSection.getKeys(false)) {
				slotIndex.put(key.toLowerCase(Locale.ROOT), indexSection.getInt(key, -1));
			}
		}

		return new CosmeticConfig(
			cfg.getBoolean("enabled", true),
			Math.max(1, cfg.getInt("sync.interval-ticks", 1)),
			Math.max(0.0, cfg.getDouble("sync.move-threshold", 0.05)),
			Math.min(59, Math.max(0, cfg.getInt("sync.teleport-duration", 2))),
			Math.max(0.0, cfg.getDouble("sync.near-players-radius", 0.0)),
			(float) Math.max(0.0, cfg.getDouble("view.range", 48.0)),
			cfg.getBoolean("view.self-only", false),
			slots,
			cfg.getBoolean("attributes.enabled", true),
			toStringList(cfg.getStringList("attributes.whitelist")),
			toStringList(cfg.getStringList("attributes.blacklist")),
			cfg.getBoolean("attributes.stack", true),
			cfg.getBoolean("block-use.consume", true),
			cfg.getBoolean("block-use.block-place", true),
			cfg.getBoolean("block-use.interact", true),
			new GuiLayout(
				cfg.getString("gui.title", "&8装备外观"),
				Math.min(6, Math.max(1, cfg.getInt("gui.rows", 6))),
				slotIndex,
				cfg.getInt("gui.info-index", 4),
				cfg.getInt("gui.clear-index", 49),
				cfg.getInt("gui.close-index", 53)
			)
		);
	}

	private static List<String> toStringList(List<String> raw) {
		List<String> out = new ArrayList<>(raw.size());
		for (String item : raw) {
			if (item != null && !item.isBlank()) {
				out.add(item.trim().toUpperCase(Locale.ROOT));
			}
		}
		return out;
	}

	private static ItemDisplay.ItemDisplayTransform parseTransform(String raw) {
		if (raw == null) {
			return ItemDisplay.ItemDisplayTransform.GROUND;
		}
		try {
			return ItemDisplay.ItemDisplayTransform.valueOf(raw.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException exception) {
			return ItemDisplay.ItemDisplayTransform.GROUND;
		}
	}
}
