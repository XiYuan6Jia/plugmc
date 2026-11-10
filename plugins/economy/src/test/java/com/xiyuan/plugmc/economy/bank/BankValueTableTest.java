package com.xiyuan.plugmc.economy.bank;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BankValueTableTest {
	private static Map<String, Long> base() {
		return Map.of(
			"STONE", 200L,
			"STONE_BRICKS", 400L,
			"STONE_BRICK_STAIRS", 400L,
			"STONE_BRICK_SLAB", 400L,
			"CRACKED_STONE_BRICKS", 500L
		);
	}

	private static List<BankValueTable.RecipeSpec> recipes() {
		return List.of(
			// 4 石头 -> 4 石砖
			new BankValueTable.RecipeSpec("STONE_BRICKS", 4, Map.of("STONE", 4)),
			// 6 石砖 -> 4 石砖楼梯
			new BankValueTable.RecipeSpec("STONE_BRICK_STAIRS", 4, Map.of("STONE_BRICKS", 6)),
			// 3 石砖 -> 6 石砖台阶
			new BankValueTable.RecipeSpec("STONE_BRICK_SLAB", 6, Map.of("STONE_BRICKS", 3)),
			// 烧炼：石砖 -> 裂纹石砖
			new BankValueTable.RecipeSpec("CRACKED_STONE_BRICKS", 1, Map.of("STONE_BRICKS", 1))
		);
	}

	@Test
	void recipeCostLowersValue() {
		Map<String, Long> values = BankValueTable.solve(base(), recipes());
		assertEquals(200L, values.get("STONE_BRICKS"));
		assertEquals(300L, values.get("STONE_BRICK_STAIRS"));
		assertEquals(100L, values.get("STONE_BRICK_SLAB"));
		assertEquals(200L, values.get("CRACKED_STONE_BRICKS"));
	}

	@Test
	void slabIsCheaperThanBrick() {
		Map<String, Long> values = BankValueTable.solve(base(), recipes());
		assertTrue(values.get("STONE_BRICK_SLAB") < values.get("STONE_BRICKS"));
		assertTrue(values.get("STONE_BRICK_STAIRS") > values.get("STONE_BRICK_SLAB"));
	}

	@Test
	void noRecipeCanCreateValue() {
		Map<String, Long> values = BankValueTable.solve(base(), recipes());
		for (BankValueTable.RecipeSpec recipe : recipes()) {
			long cost = 0;
			for (Map.Entry<String, Integer> ingredient : recipe.ingredients().entrySet()) {
				cost += values.get(ingredient.getKey()) * ingredient.getValue();
			}
			assertTrue(
				values.get(recipe.result()) * recipe.output() <= cost,
				"配方 " + recipe.result() + " 会凭空产生价值"
			);
		}
	}

	@Test
	void unknownIngredientsAreIgnored() {
		Map<String, Long> values = BankValueTable.solve(
			Map.of("STONE", 200L),
			List.of(new BankValueTable.RecipeSpec("STONE", 1, Map.of("MYSTERY", 1)))
		);
		assertEquals(200L, values.get("STONE"));
	}

	@Test
	void danglingRecipesDoNotAddItems() {
		Map<String, Long> values = BankValueTable.solve(
			Map.of("STONE", 200L),
			List.of(new BankValueTable.RecipeSpec("DIAMOND_BLOCK", 1, Map.of("STONE", 1)))
		);
		assertEquals(Map.of("STONE", 200L), values);
	}
}
