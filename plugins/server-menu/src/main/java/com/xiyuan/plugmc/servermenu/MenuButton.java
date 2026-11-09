package com.xiyuan.plugmc.servermenu;

import org.bukkit.Material;

import java.util.List;

public record MenuButton(
	Material material,
	String name,
	List<String> lore,
	String action,
	String value,
	String permission
) {
}
