package com.xiyuan.plugmc.economy.shop;

import org.bukkit.Material;

import java.util.List;

public record ShopCategory(String id, String displayName, Material icon, List<String> itemIds) {
	public ShopCategory {
		itemIds = List.copyOf(itemIds);
	}
}
