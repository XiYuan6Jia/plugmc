package com.xiyuan.plugmc.goathorn;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import io.papermc.paper.persistence.PersistentDataContainerView;

/**
 * 绑定关系的两个落点：
 *
 * <ul>
 * <li>羊角一侧：物品 PDC 里存 {@code bound-animals}（逗号分隔的 UUID 列表）
 *     —— 一只羊角可以绑多只动物，跟着物品走（放进箱子、丢在地上、给别人都成立）。
 *     另存 {@code horn-id} 作为这只羊角的稳定 key，动物侧的 {@code bound-horn} 指向它。
 *     <b>26.3 的 {@code ItemStack.getPersistentDataContainer()} 返回只读视图</b>，
 *     所以读走视图、写必须走 {@code editMeta} 里的 meta PDC。</li>
 * <li>动物一侧：实体 PDC 里存 {@code bound-horn}（UUID 的 16 字节）+
 *     {@code bound-owner}（主人 UUID），动物只绑一次就查这里。</li>
 * </ul>
 *
 * <p>两边同时写、同时校验，任何一边残留都按「已绑定」处理，避免一物多角。</p>
 */
final class HornBindings {
	static final String KEY_ANIMALS = "bound-animals";
	static final String KEY_HORN = "bound-horn";
	static final String KEY_OWNER = "bound-owner";
	static final String KEY_HORN_ID = "horn-id";

	private final Plugin plugin;
	private final org.bukkit.NamespacedKey animalsKey;
	private final org.bukkit.NamespacedKey hornKey;
	private final org.bukkit.NamespacedKey ownerKey;
	private final org.bukkit.NamespacedKey hornIdKey;

	HornBindings(Plugin plugin) {
		this.plugin = plugin;
		this.animalsKey = new org.bukkit.NamespacedKey(plugin, KEY_ANIMALS);
		this.hornKey = new org.bukkit.NamespacedKey(plugin, KEY_HORN);
		this.ownerKey = new org.bukkit.NamespacedKey(plugin, KEY_OWNER);
		this.hornIdKey = new org.bukkit.NamespacedKey(plugin, KEY_HORN_ID);
	}

	// ===== 列表读写（共享：容器与只读视图都能读） =====

	List<UUID> animalsOf(PersistentDataContainerView view) {
		String raw = view.get(animalsKey, PersistentDataType.STRING);
		return parse(raw);
	}

	List<UUID> animalsOf(PersistentDataContainer container) {
		String raw = container.get(animalsKey, PersistentDataType.STRING);
		return parse(raw);
	}

	private static List<UUID> parse(String raw) {
		List<UUID> result = new ArrayList<>();
		if (raw == null || raw.isBlank()) {
			return result;
		}
		for (String part : raw.split(",")) {
			try {
				result.add(UUID.fromString(part.trim()));
			} catch (IllegalArgumentException exception) {
				// 脏数据忽略
			}
		}
		return result;
	}

	private static String join(List<UUID> animals) {
		StringBuilder builder = new StringBuilder();
		for (UUID animal : animals) {
			if (builder.length() > 0) {
				builder.append(',');
			}
			builder.append(animal);
		}
		return builder.toString();
	}

	// ===== 羊角物品侧（读走视图；写走 editMeta 的 meta PDC） =====

	/** 羊角物品上已绑的动物 UUID 列表。 */
	List<UUID> animalsOfItem(ItemStack hand) {
		PersistentDataContainerView view = hand.getPersistentDataContainer();
		return view == null ? List.of() : animalsOf(view);
	}

	/** 羊角是否绑过这只动物。 */
	boolean containsItem(ItemStack hand, UUID animalId) {
		return animalsOfItem(hand).contains(animalId);
	}

	/** 羊角自身的稳定 key（horn-id）。 */
	UUID hornKeyOfItem(ItemStack hand) {
		PersistentDataContainerView view = hand.getPersistentDataContainer();
		if (view == null) {
			return null;
		}
		String raw = view.get(hornIdKey, PersistentDataType.STRING);
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			return UUID.fromString(raw);
		} catch (IllegalArgumentException exception) {
			return null;
		}
	}

	/** 在 editMeta 回调里把绑定清单写进 meta PDC。 */
	void writeAnimalsMeta(ItemMeta meta, List<UUID> animals) {
		meta.getPersistentDataContainer().set(animalsKey, PersistentDataType.STRING, join(animals));
	}

	/** 在 editMeta 回调里写羊角稳定 key（只写一次）。 */
	void writeHornIdMeta(ItemMeta meta, UUID hornId) {
		meta.getPersistentDataContainer().set(hornIdKey, PersistentDataType.STRING, hornId.toString());
	}

	// ===== 动物实体侧 =====

	/** 动物是否已经绑过羊角（每只动物只能绑一次）。 */
	UUID hornIdOf(PersistentDataContainer container) {
		byte[] raw = container.get(hornKey, PersistentDataType.BYTE_ARRAY);
		if (raw == null || raw.length != 16) {
			return null;
		}
		java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(raw);
		return new UUID(buffer.getLong(), buffer.getLong());
	}

	void setHorn(PersistentDataContainer container, UUID hornId) {
		java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(16);
		buffer.putLong(hornId.getMostSignificantBits());
		buffer.putLong(hornId.getLeastSignificantBits());
		container.set(hornKey, PersistentDataType.BYTE_ARRAY, buffer.array());
	}

	UUID ownerOf(PersistentDataContainer container) {
		String raw = container.get(ownerKey, PersistentDataType.STRING);
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			return UUID.fromString(raw);
		} catch (IllegalArgumentException exception) {
			return null;
		}
	}

	void setOwner(PersistentDataContainer container, UUID ownerId) {
		container.set(ownerKey, PersistentDataType.STRING, ownerId.toString());
	}

	/** 动物 UUID 的短显示（lore 里不在场时用）。 */
	static String shortId(UUID animalId) {
		return animalId.toString().substring(0, 8);
	}

	/** 调试用：把物品上的绑定键与附魔状态逐项打到日志与玩家聊天。 */
	void dump(ItemStack hand, Player player) {
		PersistentDataContainerView view = hand.getPersistentDataContainer();
		String animals = view == null ? "(null)" : view.get(animalsKey, PersistentDataType.STRING);
		String hornId = view == null ? "(null)" : view.get(hornIdKey, PersistentDataType.STRING);
		boolean hasEnchant = hand.hasItemMeta() && hand.getItemMeta().hasEnchants();
		int loreSize = hand.hasItemMeta() && hand.getItemMeta().lore() != null
			? hand.getItemMeta().lore().size() : 0;
		plugin.getLogger().info("[dump] type=" + hand.getType() + " animals=" + animals
			+ " hornId=" + hornId + " enchant=" + hasEnchant + " loreLines=" + loreSize);
		player.sendMessage(HornText.component("&7[dump] animals=&f" + animals
			+ " &7hornId=&f" + hornId + " &7附魔=&f" + hasEnchant + " &7lore 行数=&f" + loreSize));
	}
}
