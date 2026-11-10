package com.xiyuan.plugmc.minigames;

import org.bukkit.Material;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 幸运转盘：6 个扇区按权重抽取（权重合计 100，理论返还率约 92%）。
 */
public final class WheelGame {
	public record Sector(String label, BigDecimal multiplier, int weight, Material material) {
	}

	private static final List<Sector> SECTORS = List.of(
		new Sector("&7空手而归", BigDecimal.ZERO, 32, Material.GRAY_STAINED_GLASS_PANE),
		new Sector("&f返还 0.5 倍", new BigDecimal("0.5"), 30, Material.WHITE_STAINED_GLASS_PANE),
		new Sector("&a返还 1 倍", BigDecimal.ONE, 22, Material.LIME_STAINED_GLASS_PANE),
		new Sector("&b返还 2 倍", new BigDecimal("2"), 10, Material.LIGHT_BLUE_STAINED_GLASS_PANE),
		new Sector("&e返还 5 倍", new BigDecimal("5"), 5, Material.YELLOW_STAINED_GLASS_PANE),
		new Sector("&6&l返还 10 倍", new BigDecimal("10"), 1, Material.ORANGE_STAINED_GLASS_PANE)
	);

	private WheelGame() {
	}

	public static List<Sector> sectors() {
		return SECTORS;
	}

	/** 按权重抽一个扇区。 */
	public static Sector spin() {
		int total = SECTORS.stream().mapToInt(Sector::weight).sum();
		int roll = ThreadLocalRandom.current().nextInt(total);
		for (Sector sector : SECTORS) {
			roll -= sector.weight();
			if (roll < 0) {
				return sector;
			}
		}
		return SECTORS.get(0);
	}

	public static BigDecimal payout(int bet, Sector sector) {
		return sector.multiplier()
			.multiply(BigDecimal.valueOf(bet))
			.setScale(2, RoundingMode.DOWN);
	}
}
