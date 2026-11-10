package com.xiyuan.plugmc.economy.shop;

import com.xiyuan.plugmc.economy.gui.MenuText;
import com.xiyuan.plugmc.economy.internal.Money;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class GoodsConfig {
	private final Map<String, ShopItem> items;
	private final Map<String, ShopCategory> categories;
	private final Map<Material, List<ShopItem>> byMaterial;

	private GoodsConfig(
		Map<String, ShopItem> items,
		Map<String, ShopCategory> categories,
		Map<Material, List<ShopItem>> byMaterial
	) {
		this.items = Map.copyOf(items);
		this.categories = Map.copyOf(categories);
		Map<Material, List<ShopItem>> copied = new LinkedHashMap<>();
		byMaterial.forEach((material, list) -> copied.put(material, List.copyOf(list)));
		this.byMaterial = Map.copyOf(copied);
	}

	public static GoodsConfig load(JavaPlugin plugin, ShopConfig shopConfig, int decimalPlaces) {
		File file = new File(plugin.getDataFolder(), "goods.yml");
		if (!file.isFile()) {
			plugin.saveResource("goods.yml", false);
		}
		YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);

		Map<String, ShopCategory> categories = new LinkedHashMap<>();
		ConfigurationSection categorySection = yaml.getConfigurationSection("categories");
		if (categorySection != null) {
			for (String key : categorySection.getKeys(false)) {
				ConfigurationSection section = categorySection.getConfigurationSection(key);
				if (section == null) {
					continue;
				}
				Material icon = Material.matchMaterial(section.getString("icon", "CHEST"));
				if (icon == null || icon.isAir()) {
					icon = Material.CHEST;
				}
				categories.put(key.toLowerCase(Locale.ROOT), new ShopCategory(
					key,
					section.getString("display", key),
					icon,
					section.getStringList("items")
				));
			}
		}

		ConfigurationSection itemSection = yaml.getConfigurationSection("items");
		if (itemSection == null) {
			throw new IllegalStateException("goods.yml 缺少 items");
		}

		Map<String, ShopItem> items = new LinkedHashMap<>();
		Map<Material, List<ShopItem>> byMaterial = new LinkedHashMap<>();
		for (String key : itemSection.getKeys(false)) {
			ConfigurationSection section = itemSection.getConfigurationSection(key);
			if (section == null) {
				continue;
			}
			String materialName = section.getString("material", key.toUpperCase(Locale.ROOT));
			Material material = Material.matchMaterial(materialName);
			if (material == null || material.isAir()) {
				throw new IllegalStateException("goods.yml 商品 " + key + " 的材质无效：" + materialName);
			}
			BigDecimal buy = Money.parse(section.getString("buy", "0.00"), decimalPlaces, true);
			BigDecimal sell = Money.parse(section.getString("sell", "0.00"), decimalPlaces, true);
			if (buy.signum() <= 0) {
				throw new IllegalStateException("goods.yml 商品 " + key + " 的 buy 必须大于 0");
			}
			if (buy.compareTo(sell) <= 0) {
				throw new IllegalStateException("goods.yml 商品 " + key + " 的 buy 必须大于 sell");
			}

			int dailyBuy = section.contains("daily-buy")
				? section.getInt("daily-buy")
				: shopConfig.dailyBuyLimit();
			int dailySell = section.contains("daily-sell")
				? section.getInt("daily-sell")
				: shopConfig.dailySellLimit();
			if (dailyBuy < 0 || dailySell < 0) {
				throw new IllegalStateException("goods.yml 商品 " + key + " 的每日限额不能为负");
			}

			String category = findCategory(categories, key);
			boolean exact = "exact".equalsIgnoreCase(section.getString("match", "material"));
			boolean sellable = section.getBoolean("sellable", false);
			ItemStack template = buildTemplate(material, section);
			ShopItem item = new ShopItem(
				key,
				category,
				material,
				buy,
				sell,
				dailyBuy,
				dailySell,
				section.getStringList("aliases"),
				exact,
				sellable,
				template
			);
			items.put(key, item);
			byMaterial.computeIfAbsent(material, ignored -> new ArrayList<>()).add(item);
		}

		byMaterial.values().forEach(list -> list.sort(
			Comparator.comparing(ShopItem::exactMatch).reversed()
		));

		if (categories.isEmpty()) {
			categories.put("misc", new ShopCategory(
				"misc",
				"杂项",
				Material.CHEST,
				new ArrayList<>(items.keySet())
			));
		} else if (!categories.containsKey("misc")
			&& items.values().stream().anyMatch(item -> item.category().equals("misc"))) {
			categories.put("misc", new ShopCategory("misc", "杂项", Material.CHEST, new ArrayList<>()));
		}

		return new GoodsConfig(items, categories, byMaterial);
	}

	private static String findCategory(Map<String, ShopCategory> categories, String itemId) {
		for (ShopCategory category : categories.values()) {
			if (category.itemIds().contains(itemId)) {
				return category.id();
			}
		}
		return "misc";
	}

	private static ItemStack buildTemplate(Material material, ConfigurationSection section) {
		ItemStack stack = new ItemStack(material);
		String name = section.getString("name");
		List<String> lore = section.getStringList("lore");
		if ((name == null || name.isBlank()) && lore.isEmpty()) {
			return stack;
		}
		ItemMeta meta = stack.getItemMeta();
		if (meta == null) {
			return stack;
		}
		if (name != null && !name.isBlank()) {
			meta.displayName(MenuText.component(name));
		}
		if (!lore.isEmpty()) {
			meta.lore(lore.stream().map(MenuText::component).toList());
		}
		stack.setItemMeta(meta);
		return stack;
	}

	public Collection<ShopItem> items() {
		return items.values();
	}

	public List<ShopCategory> categories() {
		return List.copyOf(categories.values());
	}

	public Optional<ShopItem> find(String idOrAlias) {
		if (idOrAlias == null) {
			return Optional.empty();
		}
		String query = idOrAlias.toLowerCase(Locale.ROOT);
		ShopItem direct = items.get(query);
		if (direct != null) {
			return Optional.of(direct);
		}
		for (ShopItem item : items.values()) {
			if (item.material().name().equalsIgnoreCase(query)) {
				return Optional.of(item);
			}
			for (String alias : item.aliases()) {
				if (alias.equalsIgnoreCase(query)) {
					return Optional.of(item);
				}
			}
		}
		return Optional.empty();
	}

	public ShopItem match(ItemStack stack, boolean allowCustomItems) {
		if (stack == null || stack.getType().isAir()) {
			return null;
		}
		List<ShopItem> candidates = byMaterial.get(stack.getType());
		if (candidates == null) {
			return null;
		}
		for (ShopItem item : candidates) {
			if (item.matches(stack, allowCustomItems)) {
				return item;
			}
		}
		return null;
	}

	/**
	 * 回收匹配：只有 goods.yml 中标注 sellable: true 的商品才允许卖给商店，
	 * 默认只给矿石原矿开启，避免把锭、方块和成品卖回系统。
	 */
	public ShopItem matchSellable(ItemStack stack, boolean allowCustomItems) {
		ShopItem item = match(stack, allowCustomItems);
		return item != null && item.sellable() ? item : null;
	}

	public List<ShopItem> itemsForCategory(String categoryId) {
		ShopCategory category = categories.get(categoryId.toLowerCase(Locale.ROOT));
		if (category == null) {
			return List.of();
		}
		LinkedHashSet<ShopItem> result = new LinkedHashSet<>();
		for (String itemId : category.itemIds()) {
			ShopItem item = items.get(itemId);
			if (item != null) {
				result.add(item);
			}
		}
		if (result.isEmpty()) {
			items.values().stream()
				.filter(item -> item.category().equalsIgnoreCase(category.id()))
				.forEach(result::add);
		}
		return List.copyOf(result);
	}

	public List<ShopItem> search(String query) {
		String needle = query == null ? "" : query.toLowerCase(Locale.ROOT).trim();
		if (needle.isEmpty()) {
			return List.copyOf(items.values());
		}
		List<ShopItem> result = new ArrayList<>();
		for (ShopItem item : items.values()) {
			if (item.id().toLowerCase(Locale.ROOT).contains(needle)
				|| item.material().name().toLowerCase(Locale.ROOT).contains(needle)
				|| item.aliases().stream().anyMatch(alias -> alias.toLowerCase(Locale.ROOT).contains(needle))) {
				result.add(item);
			}
		}
		return List.copyOf(result);
	}
}
