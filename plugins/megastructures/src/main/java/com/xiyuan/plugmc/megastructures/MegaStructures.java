package com.xiyuan.plugmc.megastructures;

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
		new MegaViewpoint("ring", "轨道环（出生点）", 0.5, 172.0, 240.5, 180.0f, -8.0f),
		new MegaViewpoint("spire", "核心尖塔观景台", 0.5, 287.0, 25.5, 0.0f, 22.0f),
		new MegaViewpoint("dyson", "戴森球观景台", -675.5, 180.0, 0.5, 90.0f, -5.0f),
		new MegaViewpoint("elevator", "太空电梯基座", 818.5, 61.0, 0.5, 90.0f, -5.0f),
		new MegaViewpoint("top", "太空电梯顶端", 800.5, 301.0, 12.5, 180.0f, 12.0f),
		new MegaViewpoint("gate", "星际之门", 0.5, 61.0, -770.5, 180.0f, -20.0f)
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
