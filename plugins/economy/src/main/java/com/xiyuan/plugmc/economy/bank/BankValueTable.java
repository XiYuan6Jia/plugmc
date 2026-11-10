package com.xiyuan.plugmc.economy.bank;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 按合成表推导建材估值（单位是最小货币单位，默认 0.01 金币）。
 *
 * <p>规则参考开源等价交换（ProjectE）的思路：估值不超过商店买价，也不超过任何一条
 * 合成路径的材料成本，迭代到收敛。整条链路使用整数运算并向下取整，保证任何合成、
 * 存取、兑换都不会凭空产生价值。</p>
 */
public final class BankValueTable {
	private static final int MAX_ROUNDS = 512;

	private BankValueTable() {
	}

	/**
	 * 一条合成/烧炼配方：output 个 result 需要 ingredients（材质名 -> 数量）。
	 */
	public record RecipeSpec(String result, int output, Map<String, Integer> ingredients) {
		public RecipeSpec {
			ingredients = Map.copyOf(ingredients);
			if (output <= 0) {
				throw new IllegalArgumentException("产出数量必须为正");
			}
		}
	}

	public static Map<String, Long> solve(Map<String, Long> base, List<RecipeSpec> recipes) {
		Map<String, Long> values = new HashMap<>();
		base.forEach((key, value) -> {
			if (value != null && value > 0) {
				values.put(key, value);
			}
		});
		for (int round = 0; round < MAX_ROUNDS; round++) {
			boolean changed = false;
			for (RecipeSpec recipe : recipes) {
				Long current = values.get(recipe.result());
				if (current == null) {
					continue;
				}
				long cost = 0;
				boolean known = true;
				for (Map.Entry<String, Integer> ingredient : recipe.ingredients().entrySet()) {
					Long value = values.get(ingredient.getKey());
					if (value == null || ingredient.getValue() <= 0) {
						known = false;
						break;
					}
					cost += value * ingredient.getValue();
				}
				if (!known || cost <= 0) {
					continue;
				}
				long candidate = Math.max(1, cost / recipe.output());
				if (candidate < current) {
					values.put(recipe.result(), candidate);
					changed = true;
				}
			}
			if (!changed) {
				break;
			}
		}
		return Map.copyOf(values);
	}
}
