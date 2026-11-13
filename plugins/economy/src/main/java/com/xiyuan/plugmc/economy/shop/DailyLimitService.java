package com.xiyuan.plugmc.economy.shop;

import com.xiyuan.plugmc.economy.internal.EconomyStore;
import org.bukkit.entity.Player;

import java.time.LocalDate;
import java.util.UUID;

/**
 * 商店每日限额。按“玩家 + 日期 + 动作 + 商品”计数，随 state.json 快照持久化。
 *
 * <p>既有 Player 版本，也有 UUID 版本：回收箱结算时绑定玩家可能已离线，
 * 那时没有 Player 对象可用，但限额仍然要按那个人的账号计。</p>
 */
public final class DailyLimitService {
	private final EconomyStore store;

	public DailyLimitService(EconomyStore store) {
		this.store = store;
	}

	public int used(Player player, String action, String itemId) {
		return used(player.getUniqueId(), action, itemId);
	}

	public int used(UUID playerId, String action, String itemId) {
		return store.dailyUsed(key(playerId, action, itemId));
	}

	public int remaining(Player player, String action, String itemId, int limit) {
		return remaining(player.getUniqueId(), action, itemId, limit);
	}

	public int remaining(UUID playerId, String action, String itemId, int limit) {
		if (limit <= 0) {
			return Integer.MAX_VALUE;
		}
		return Math.max(0, limit - used(playerId, action, itemId));
	}

	public boolean canUse(Player player, String action, String itemId, int amount, int limit) {
		return canUse(player.getUniqueId(), action, itemId, amount, limit);
	}

	public boolean canUse(UUID playerId, String action, String itemId, int amount, int limit) {
		if (limit <= 0) {
			return true;
		}
		return used(playerId, action, itemId) + amount <= limit;
	}

	public void consume(Player player, String action, String itemId, int amount) {
		consume(player.getUniqueId(), action, itemId, amount);
	}

	public void consume(UUID playerId, String action, String itemId, int amount) {
		if (amount > 0) {
			store.addDailyUse(key(playerId, action, itemId), amount);
		}
	}

	private String key(UUID playerId, String action, String itemId) {
		return playerId + "|" + LocalDate.now() + "|" + action + "|" + itemId;
	}
}
