package com.xiyuan.plugmc.cosmetic;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemAttributeModifiers;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ItemType;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 属性透传：把装饰槽里物品的 {@code attribute_modifiers} 施加到玩家身上。
 *
 * <p>关键取舍：</p>
 * <ul>
 *   <li>一律用 {@code addTransientModifier}，不写进玩家存档 —— 插件卸载或意外崩溃不会留下"幽灵属性"。</li>
 *   <li>本类自己记录"加过哪些（属性 × key）"，撤销时只按这张表摘，不去遍历整个注册表。</li>
 *   <li>物品自身的 {@code EquipmentSlotGroup} 被忽略：装饰槽没有装备栏语义，一律按 ANY 施加，
 *       否则大量限定 MAINHAND/ARMOR 的物品会完全不生效。</li>
 * </ul>
 */
final class CosmeticAttributes {
	private final JavaPlugin plugin;
	private final CosmeticConfig config;
	private final CosmeticWardrobe wardrobe;
	/** playerId -> 本插件加上的（属性, key） */
	private final Map<UUID, List<Applied>> applied = new HashMap<>();
	private static final Multimap<Attribute, AttributeModifier> EMPTY = ArrayListMultimap.create();

	private record Applied(Attribute attribute, NamespacedKey key) {
	}

	CosmeticAttributes(JavaPlugin plugin, CosmeticConfig config, CosmeticWardrobe wardrobe) {
		this.plugin = plugin;
		this.config = config;
		this.wardrobe = wardrobe;
	}

	/**
	 * 重新计算某玩家的透传属性。调用点：槽位变化、玩家加入、重生、换世界、插件启用。
	 */
	void refresh(Player player) {
		if (!config.attributesEnabled) {
			return;
		}
		UUID id = player.getUniqueId();
		strip(player);

		// 先按属性收集候选，再统一施加，避免叠加模式下顺序影响结果
		Map<Attribute, List<AttributeModifier>> grouped = new LinkedHashMap<>();
		for (String slotId : wardrobe.slotIds(id)) {
			ItemStack stack = wardrobe.get(id, slotId);
			if (stack == null) {
				continue;
			}
			for (Map.Entry<Attribute, AttributeModifier> entry : modifiersOf(stack).entries()) {
				Attribute attribute = entry.getKey();
				AttributeModifier modifier = entry.getValue();
				if (attribute == null || modifier == null || !allowed(attribute)) {
					continue;
				}
				NamespacedKey key = new NamespacedKey(plugin, "cos_" + slotId + "_" + sanitize(modifier.getName()));
				AttributeModifier mapped = new AttributeModifier(key, modifier.getAmount(), modifier.getOperation());
				grouped.computeIfAbsent(attribute, k -> new ArrayList<>()).add(mapped);
			}
		}

		List<Applied> added = new ArrayList<>();
		for (Map.Entry<Attribute, List<AttributeModifier>> entry : grouped.entrySet()) {
			Attribute attribute = entry.getKey();
			AttributeInstance instance = player.getAttribute(attribute);
			if (instance == null) {
				continue;
			}
			List<AttributeModifier> candidates = entry.getValue();
			if (!config.attributesStack && candidates.size() > 1) {
				AttributeModifier best = candidates.get(0);
				for (AttributeModifier candidate : candidates) {
					if (Math.abs(candidate.getAmount()) > Math.abs(best.getAmount())) {
						best = candidate;
					}
				}
				candidates = List.of(best);
			}
			for (AttributeModifier modifier : candidates) {
				try {
					instance.addTransientModifier(modifier);
					added.add(new Applied(attribute, modifier.getKey()));
				} catch (RuntimeException exception) {
					plugin.getLogger().warning("施加属性失败（" + attribute.getKey().getKey() + "）：" + exception.getMessage());
				}
			}
		}
		if (!added.isEmpty()) {
			applied.put(id, added);
		}
	}

	/** 摘掉本插件加过的全部修饰符。 */
	void strip(Player player) {
		List<Applied> previous = applied.remove(player.getUniqueId());
		if (previous == null || previous.isEmpty()) {
			return;
		}
		for (Applied one : previous) {
			AttributeInstance instance = player.getAttribute(one.attribute());
			if (instance == null) {
				continue;
			}
			try {
				if (instance.getModifier(one.key()) != null) {
					instance.removeModifier(one.key());
				}
			} catch (RuntimeException exception) {
				plugin.getLogger().warning("撤销属性失败（" + one.attribute().getKey().getKey() + "）：" + exception.getMessage());
			}
		}
	}

	/** 玩家下线时的清理。 */
	void forget(UUID playerId) {
		applied.remove(playerId);
	}

	int activeModifierCount() {
		int total = 0;
		for (List<Applied> list : applied.values()) {
			total += list.size();
		}
		return total;
	}

	/**
	 * 读一件物品的（属性 → 修饰符）表。
	 *
	 * <p>26.3 里属性修饰符是 {@code minecraft:attribute_modifiers} 数据组件。三条路径依次尝试：</p>
	 * <ol>
	 *   <li>{@code stack.getData(ATTRIBUTE_MODIFIERS)} —— 物品上显式覆盖过的值；</li>
	 *   <li>{@code Material.asItemType().getDefaultAttributeModifiers()} —— 物品定义自带的
	 *       （钻石剑的 +7 攻击力就在这一层，{@link ItemMeta#getAttributeModifiers()} 完全看不到）；</li>
	 *   <li>{@link ItemMeta} —— 兼容旧式直接写在 meta 上的物品 / 第三方自定义物品。</li>
	 * </ol>
	 */
	private static Multimap<Attribute, AttributeModifier> modifiersOf(ItemStack stack) {
		try {
			ItemAttributeModifiers component = stack.getData(DataComponentTypes.ATTRIBUTE_MODIFIERS);
			if (component != null && !component.modifiers().isEmpty()) {
				return toMultimap(component);
			}
		} catch (RuntimeException ignored) {
			// 落到下一条路径
		}
		try {
			ItemType itemType = stack.getType().asItemType();
			if (itemType != null) {
				Multimap<Attribute, AttributeModifier> defaults = itemType.getDefaultAttributeModifiers();
				if (defaults != null && !defaults.isEmpty()) {
					return defaults;
				}
			}
		} catch (RuntimeException ignored) {
			// 不是物品（空气、纯方块等），落到下一条路径
		}
		ItemMeta meta = stack.getItemMeta();
		if (meta != null && meta.hasAttributeModifiers()) {
			Multimap<Attribute, AttributeModifier> modifiers = meta.getAttributeModifiers();
			if (modifiers != null) {
				return modifiers;
			}
		}
		return EMPTY;
	}

	private static Multimap<Attribute, AttributeModifier> toMultimap(ItemAttributeModifiers component) {
		Multimap<Attribute, AttributeModifier> out = ArrayListMultimap.create();
		for (ItemAttributeModifiers.Entry entry : component.modifiers()) {
			if (entry.attribute() == null || entry.modifier() == null) {
				continue;
			}
			AttributeModifier modifier = entry.modifier();
			// 重新包一个不限装备槽的修饰符：装饰槽没有装备栏语义，原物品的 HEAD/CHEST 之类会完全不生效
			out.put(entry.attribute(), new AttributeModifier(
					modifier.getKey(), modifier.getAmount(), modifier.getOperation()));
		}
		return out;
	}

	private boolean allowed(Attribute attribute) {
		String name = attribute.getKey().getKey().toUpperCase(Locale.ROOT);
		if (config.attributeBlacklist.contains(name)) {
			return false;
		}
		return config.attributeWhitelist.isEmpty() || config.attributeWhitelist.contains(name);
	}

	private static String sanitize(String raw) {
		if (raw == null || raw.isBlank()) {
			return "mod";
		}
		StringBuilder out = new StringBuilder(raw.length());
		for (char c : raw.toCharArray()) {
			out.append(Character.isLetterOrDigit(c) ? Character.toLowerCase(c) : '_');
		}
		return out.toString();
	}
}
