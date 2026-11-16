package com.xiyuan.plugmc.megastructures;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;

/** 全部巨构与观景点的登记表。 */
public final class MegaStructures {
	private static final List<MegaStructure> ALL = List.of(
		new OrbitalRing(),
		new CoreSpire(),
		new DysonSphere(),
		new SpaceElevator(),
		new StarGate()
	);

	private static final List<MegaViewpoint> VIEWPOINTS = List.of(
		new MegaViewpoint(
			"ring", "轨道环（出生点）",
			"半径 380 的环形天桥，出生点就在环上，抬头能看到核心尖塔。",
			Material.END_CRYSTAL,
			0.5, 420.0, 380.5, 180.0f, -8.0f,
			3.5, 420.0, 380.5
		),
		new MegaViewpoint(
			"spire", "核心尖塔观景台",
			"Y=1150 的悬空观景台，玻璃塔身里贯穿整座尖塔的发光能量核心。",
			Material.AMETHYST_BLOCK,
			0.5, 1151.0, 35.5, 0.0f, 22.0f,
			3.5, 1151.0, 35.5
		),
		new MegaViewpoint(
			"dyson", "戴森球观景台",
			"半径 180 的空心戴森球，栈桥穿过入口直达内部悬浮恒星。",
			Material.GLOWSTONE,
			-1375.5, 700.0, 0.5, 90.0f, -5.0f,
			-1375.5, 700.0, 3.5
		),
		new MegaViewpoint(
			"elevator", "太空电梯基座",
			"41×41 的启航平台，玻璃井道一路升到 Y=1400，每 160 格一层平台。",
			Material.IRON_BLOCK,
			1618.5, 61.0, 0.5, 90.0f, -5.0f,
			1618.5, 61.0, 3.5
		),
		new MegaViewpoint(
			"top", "太空电梯顶端",
			"Y=1402 的云端平台，脚下的巨构群一览无余。",
			Material.DIAMOND_BLOCK,
			1600.5, 1402.0, 12.5, 180.0f, 12.0f,
			1603.5, 1402.0, 12.5
		),
		new MegaViewpoint(
			"gate", "星际之门",
			"半径 220 的竖立巨环，环内有两道能量环与 81×81 启航平台。",
			Material.NETHER_STAR,
			0.5, 61.0, -1560.5, 180.0f, -20.0f,
			3.5, 61.0, -1560.5
		)
	);

	private MegaStructures() {
	}

	public static List<MegaStructure> all() {
		return ALL;
	}

	public static List<MegaViewpoint> viewpoints() {
		return VIEWPOINTS;
	}

	public static MegaViewpoint viewpoint(String id) {
		if (id == null) {
			return null;
		}
		for (MegaViewpoint viewpoint : VIEWPOINTS) {
			if (viewpoint.id().equalsIgnoreCase(id.trim())) {
				return viewpoint;
			}
		}
		return null;
	}

	public static List<MegaCheck> checks() {
		List<MegaCheck> checks = new ArrayList<>();
		for (MegaStructure structure : ALL) {
			checks.addAll(structure.checks());
		}
		return List.copyOf(checks);
	}
}
