package com.xiyuan.plugmc.minigames;

import org.bukkit.Material;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 老虎机：三个滚轮各自按权重抽符号（合计 100），三连按倍数、两同返还 1.1 倍，
 * 理论返还率约 92%。
 */
public final class SlotsGame {
	public record Symbol(String label, Material material, int weight, BigDecimal triple) {
	}

	private static final List<Symbol> SYMBOLS = List.of(
		new Symbol("&c&l樱桃", Material.RED_DYE, 30, new BigDecimal("4")),
		new Symbol("&e&l柠檬", Material.YELLOW_DYE, 25, new BigDecimal("6")),
		new Symbol("&b&l铃铛", Material.BELL, 20, new BigDecimal("10")),
		new Symbol("&a&l钻石", Material.DIAMOND, 15, new BigDecimal("15")),
		new Symbol("&6&l幸运 7", Material.GOLD_INGOT, 10, new BigDecimal("30"))
	);

	private static final BigDecimal PAIR_MULTIPLIER = new BigDecimal("1.1");

	private SlotsGame() {
	}

	public static List<Symbol> symbols() {
		return SYMBOLS;
	}

	public static int randomIndex() {
		int total = SYMBOLS.stream().mapToInt(Symbol::weight).sum();
		int roll = ThreadLocalRandom.current().nextInt(total);
		for (int index = 0; index < SYMBOLS.size(); index++) {
			roll -= SYMBOLS.get(index).weight();
			if (roll < 0) {
				return index;
			}
		}
		return 0;
	}

	public static Symbol symbol(int index) {
		return SYMBOLS.get(Math.max(0, Math.min(index, SYMBOLS.size() - 1)));
	}

	/** 返回派彩金额（0 表示没中）。 */
	public static BigDecimal payout(int bet, int[] reels) {
		BigDecimal amount = BigDecimal.valueOf(bet);
		if (reels[0] == reels[1] && reels[1] == reels[2]) {
			return symbol(reels[0]).triple().multiply(amount).setScale(2, RoundingMode.DOWN);
		}
		boolean pair = reels[0] == reels[1] || reels[1] == reels[2] || reels[0] == reels[2];
		if (pair) {
			return PAIR_MULTIPLIER.multiply(amount).setScale(2, RoundingMode.DOWN);
		}
		return BigDecimal.ZERO;
	}

	public static String describe(int[] reels) {
		if (reels[0] == reels[1] && reels[1] == reels[2]) {
			return symbol(reels[0]).label() + " 三连！";
		}
		if (reels[0] == reels[1] || reels[1] == reels[2] || reels[0] == reels[2]) {
			return "两个相同，返还 1.1 倍";
		}
		return "";
	}
}
