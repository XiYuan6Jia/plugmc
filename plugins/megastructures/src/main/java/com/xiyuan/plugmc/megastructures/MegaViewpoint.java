package com.xiyuan.plugmc.megastructures;

import org.bukkit.Material;

/**
 * 可直接传送过去的观景点：观景站位、传送菜单图标、景观介绍，
 * 以及观景台旁边交互实体（右键打开传送列表）的位置。
 */
public record MegaViewpoint(
	String id,
	String label,
	String description,
	Material icon,
	double x,
	double y,
	double z,
	float yaw,
	float pitch,
	double interactX,
	double interactY,
	double interactZ
) {
}
