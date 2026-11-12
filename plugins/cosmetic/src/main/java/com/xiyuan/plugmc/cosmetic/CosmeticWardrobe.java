package com.xiyuan.plugmc.cosmetic;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 装饰槽内容的持久化。
 *
 * <p>存储的是物品的字节序列化 + Base64（见 {@link CosmeticItems}），所以取下来的时候
 * 能原样还原成放进去的那件物品，包括自定义名字、附魔、组件。</p>
 *
 * <p>写法与 economy 模块一致：JSON + 临时文件 + ATOMIC_MOVE，避免半写坏文件。</p>
 */
final class CosmeticWardrobe {
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
	private static final Type DATA_TYPE = new TypeToken<Map<String, Map<String, String>>>() {
	}.getType();
	private static final int SCHEMA_VERSION = 1;

	private final JavaPlugin plugin;
	private final Path file;
	private final Map<UUID, Map<String, String>> data = new HashMap<>();
	private boolean dirty;

	CosmeticWardrobe(JavaPlugin plugin) {
		this.plugin = plugin;
		this.file = plugin.getDataFolder().toPath().resolve("wardrobe.json");
		load();
	}

	private void load() {
		if (!Files.isRegularFile(file)) {
			return;
		}
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			Root root = GSON.fromJson(reader, Root.class);
			if (root == null || root.players == null) {
				return;
			}
			if (root.version != null && root.version != SCHEMA_VERSION) {
				plugin.getLogger().warning("wardrobe.json 版本为 " + root.version + "，当前插件期望 " + SCHEMA_VERSION
					+ "；仍会按当前格式读取，若有异常请先备份该文件。");
			}
			for (Map.Entry<String, Map<String, String>> entry : root.players.entrySet()) {
				UUID id = parseUuid(entry.getKey());
				if (id == null) {
					continue;
				}
				Map<String, String> slots = new LinkedHashMap<>();
				if (entry.getValue() != null) {
					for (Map.Entry<String, String> one : entry.getValue().entrySet()) {
						if (one.getValue() != null && !one.getValue().isBlank()) {
							slots.put(one.getKey(), one.getValue());
						}
					}
				}
				if (!slots.isEmpty()) {
					data.put(id, slots);
				}
			}
			plugin.getLogger().info("已载入 " + data.size() + " 名玩家的装备外观数据");
		} catch (IOException | RuntimeException exception) {
			plugin.getLogger().severe("读取 wardrobe.json 失败，本次将按空数据运行（原文件未被覆盖）：" + exception.getMessage());
		}
	}

	/** 读某个槽里的物品；没有则返回 null。 */
	ItemStack get(UUID playerId, String slotId) {
		Map<String, String> slots = data.get(playerId);
		if (slots == null) {
			return null;
		}
		String encoded = slots.get(slotId);
		if (encoded == null) {
			return null;
		}
		try {
			return CosmeticItems.decode(encoded);
		} catch (IllegalArgumentException exception) {
			plugin.getLogger().warning("玩家 " + playerId + " 的部位 " + slotId + " 数据损坏，已跳过：" + exception.getMessage());
			return null;
		}
	}

	/** 该玩家所有有内容的槽位 id。 */
	List<String> slotIds(UUID playerId) {
		Map<String, String> slots = data.get(playerId);
		if (slots == null || slots.isEmpty()) {
			return List.of();
		}
		List<String> ids = new ArrayList<>(slots.keySet());
		ids.sort(String::compareTo);
		return ids;
	}

	/** 放进去（覆盖原内容）。返回被替换掉的旧物品，可能为 null。 */
	ItemStack put(UUID playerId, String slotId, ItemStack stack) {
		ItemStack previous = get(playerId, slotId);
		String encoded = CosmeticItems.encode(stack);
		data.computeIfAbsent(playerId, key -> new LinkedHashMap<>()).put(slotId, encoded);
		dirty = true;
		return previous;
	}

	/** 取出来并清空该槽。 */
	ItemStack take(UUID playerId, String slotId) {
		Map<String, String> slots = data.get(playerId);
		if (slots == null) {
			return null;
		}
		String encoded = slots.remove(slotId);
		if (slots.isEmpty()) {
			data.remove(playerId);
		}
		if (encoded == null) {
			return null;
		}
		dirty = true;
		try {
			return CosmeticItems.decode(encoded);
		} catch (IllegalArgumentException exception) {
			plugin.getLogger().warning("玩家 " + playerId + " 的部位 " + slotId + " 数据损坏：" + exception.getMessage());
			return null;
		}
	}

	/** 清空该玩家的全部槽位，返回被清出来的物品（用于归还）。 */
	List<ItemStack> clear(UUID playerId) {
		Map<String, String> slots = data.remove(playerId);
		if (slots == null || slots.isEmpty()) {
			return List.of();
		}
		dirty = true;
		List<ItemStack> out = new ArrayList<>();
		for (Map.Entry<String, String> entry : slots.entrySet()) {
			try {
				out.add(CosmeticItems.decode(entry.getValue()));
			} catch (IllegalArgumentException exception) {
				plugin.getLogger().warning("清空时跳过损坏的部位 " + entry.getKey() + "：" + exception.getMessage());
			}
		}
		return out;
	}

	boolean has(UUID playerId, String slotId) {
		Map<String, String> slots = data.get(playerId);
		return slots != null && slots.containsKey(slotId);
	}

	int count(UUID playerId) {
		Map<String, String> slots = data.get(playerId);
		return slots == null ? 0 : slots.size();
	}

	void saveIfDirty() {
		if (dirty) {
			save();
		}
	}

	synchronized void save() {
		try {
			Files.createDirectories(file.getParent());
			Root root = new Root();
			root.version = SCHEMA_VERSION;
			root.players = new LinkedHashMap<>();
			for (Map.Entry<UUID, Map<String, String>> entry : data.entrySet()) {
				root.players.put(entry.getKey().toString(), entry.getValue());
			}
			Path temp = file.resolveSibling(file.getFileName() + ".tmp");
			try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
				GSON.toJson(root, Root.class, writer);
			}
			try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
				channel.force(true);
			}
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			dirty = false;
		} catch (IOException exception) {
			plugin.getLogger().severe("保存 wardrobe.json 失败：" + exception.getMessage());
		}
	}

	private static UUID parseUuid(String raw) {
		try {
			return UUID.fromString(raw);
		} catch (IllegalArgumentException exception) {
			return null;
		}
	}

	private static final class Root {
		Integer version;
		Map<String, Map<String, String>> players;
	}
}
