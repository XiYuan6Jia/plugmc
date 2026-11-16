package com.xiyuan.plugmc.megastructures;

/** 可直接 /mega tp 过去的观景点。 */
public record MegaViewpoint(
	String id,
	String label,
	double x,
	double y,
	double z,
	float yaw,
	float pitch
) {
}
