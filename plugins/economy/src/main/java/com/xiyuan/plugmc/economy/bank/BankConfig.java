package com.xiyuan.plugmc.economy.bank;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 建材银行配置：可存入的分类、折算率、单次上限，以及是否允许抵扣商店消费。
 */
public record BankConfig(
	boolean enabled,
	Set<String> categories,
	double depositRate,
	int maxPerOperation,
	boolean payShop
) {
	public BankConfig {
		Set<String> normalized = new LinkedHashSet<>();
		for (String category : categories) {
			if (category != null && !category.isBlank()) {
				normalized.add(category.trim().toLowerCase(Locale.ROOT));
			}
		}
		categories = Set.copyOf(normalized);
		depositRate = Math.max(0.0, Math.min(1.0, depositRate));
		maxPerOperation = Math.max(1, maxPerOperation);
	}

	public static BankConfig load(FileConfiguration config) {
		List<String> defaults = List.of(
			"stone", "wood", "glass", "wool", "concrete", "terracotta", "light", "decor"
		);
		List<String> categories = config.getStringList("bank.categories");
		return new BankConfig(
			config.getBoolean("bank.enabled", true),
			categories.isEmpty() ? Set.copyOf(defaults) : Set.copyOf(categories),
			config.getDouble("bank.deposit-rate", 1.0),
			config.getInt("bank.max-per-operation", 2304),
			config.getBoolean("bank.pay-shop", true)
		);
	}

	public boolean includes(String categoryId) {
		return categoryId != null && categories.contains(categoryId.toLowerCase(Locale.ROOT));
	}
}
