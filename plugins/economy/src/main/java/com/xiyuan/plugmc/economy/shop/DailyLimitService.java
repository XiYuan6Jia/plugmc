package com.xiyuan.plugmc.economy.shop;

import com.xiyuan.plugmc.economy.internal.EconomyStore;
import org.bukkit.entity.Player;

import java.time.LocalDate;

/**
 * 商店每日限额。按“玩家 + 日期 + 动作 + 商品”计数，随 state.json 快照持久化。
 */
public final class DailyLimitService {
	private final EconomyStore store;

	public DailyLimitService(EconomyStore store) {
		this.store = store;
	}

	public int used(Player player, String action, String itemId) {
		return store.dailyUsed(key(player, action, itemId));
	}

	public int remaining(Player player, String action, String itemId, int limit) {
		if (limit <= 0) {
			return Integer.MAX_VALUE;
		}
		return Math.max(0, limit - used(player, action, itemId));
	}

	public boolean canUse(Player player, String action, String itemId, int amount, int limit) {
		if (limit <= 0) {
			return true;
		}
		return used(player, action, itemId) + amount <= limit;
	}

	public void consume(Player player, String action, String itemId, int amount) {
		if (amount > 0) {
			store.addDailyUse(key(player, action, itemId), amount);
		}
	}

	private String key(Player player, String action, String itemId) {
		return player.getUniqueId() + "|" + LocalDate.now() + "|" + action + "|" + itemId;
	}
}
