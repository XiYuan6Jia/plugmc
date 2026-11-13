package com.xiyuan.plugmc.bazaar;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * 摊位分配器：决定「谁站哪个摊、用哪套装饰」。
 *
 * <p>三条硬性规则：</p>
 * <ol>
 *   <li><b>一玩家一摊</b> —— 分配单位是玩家，不是交易项。一个人挂十件货也只占一个摊位。</li>
 *   <li><b>位置稳定</b> —— 一个玩家的摊位位置由名字哈希决定（{@link BazaarTenants#stableIndex}），
 *       冲突时向后顺延找空位。同一个人下次重新摆摊还回到老地方，玩家能记住「我的摊在东边第三家」。</li>
 *   <li><b>装饰随机</b> —— 每次<b>新占用</b>时随机挑一套装饰；已经在摆的摊位不重抽，
 *       否则每 40 tick 刷一次就换一身衣服，看着像在闪。</li>
 * </ol>
 *
 * <p>当某个玩家的交易全部结束后，{@link #sync} 会发现他不在当前租户列表里，
 * 调用 {@link BazaarStalls#release} 撤掉实体并把装饰方块还原。</p>
 */
final class BazaarDistributor {
	private final BazaarStalls stalls;
	private final Random random = new Random();

	/** 玩家名（小写）→ 已分配到的摊位序号。摊位号一旦给出就不变，直到该摊位被释放。 */
	private final Map<String, Integer> assigned = new HashMap<>();

	/** 玩家名（小写）→ 已分到的装饰方案（新占用时抽一次，之后保持不变）。 */
	private final Map<String, BazaarConfig.Decor> decorOf = new HashMap<>();

	/** 摊位全部放满时，这些人的摊位就被挤掉了 —— 记录一次以便给出提示。 */
	private int lastOverflow = 0;

	BazaarDistributor(BazaarStalls stalls) {
		this.stalls = stalls;
	}

	/**
	 * 按当前租户列表同步整条商业街。
	 *
	 * @param tenants 当前所有有在售交易的玩家
	 * @param config  配置
	 * @return 本次新占用的摊位数（用于日志与调试）
	 */
	int sync(BazaarTenants tenants, BazaarConfig config) {
		List<int[]> slots = config.playerStalls().slots();
		if (slots.isEmpty()) {
			lastOverflow = tenants.tenants().size();
			return 0;
		}

		// 先清掉已经不在租户列表里的人（交易全部结束 → 解放摊位、还原装饰）
		List<String> gone = new ArrayList<>();
		for (String key : assigned.keySet()) {
			if (tenants.byName(key) == null) {
				gone.add(key);
			}
		}
		for (String key : gone) {
			assigned.remove(key);
			decorOf.remove(key);
			stalls.release(key);
		}

		int occupiedNow = 0;
		int overflow = 0;
		for (BazaarTenants.Tenant tenant : tenants.tenants()) {
			String key = tenant.name().toLowerCase(Locale.ROOT);
			Integer slot = assigned.get(key);
			if (slot == null) {
				int free = findFreeSlot(key, slots);
				if (free < 0) {
					overflow++;
					continue;
				}
				slot = free;
				assigned.put(key, slot);
				decorOf.put(key, pickDecor(config));
				stalls.occupy(slot, tenant, decorOf.get(key), slots.get(slot));
				occupiedNow++;
			} else {
				// 已在摊上，只刷新展示内容（装饰不动）
				stalls.refresh(slot, tenant, slots.get(slot));
			}
		}
		lastOverflow = overflow;
		return occupiedNow;
	}

	/**
	 * 给玩家找一个空摊位。
	 *
	 * <p>从名字哈希算出的首选位置开始，线性向后找第一个没人占的 ——
	 * 这样即便有冲突，整体分布仍然是散开的，不会全挤到尾部。</p>
	 */
	private int findFreeSlot(String key, List<int[]> slots) {
		int size = slots.size();
		int start = BazaarTenants.stableIndex(key, size);
		for (int step = 0; step < size; step++) {
			int candidate = (start + step) % size;
			if (!assigned.containsValue(candidate)) {
				return candidate;
			}
		}
		return -1;
	}

	/** 随机挑一套装饰；配置里只有一套时就固定用它。 */
	private BazaarConfig.Decor pickDecor(BazaarConfig config) {
		List<BazaarConfig.Decor> decors = config.decors();
		if (decors.isEmpty()) {
			return BazaarConfig.Decor.defaults().get(0);
		}
		return decors.get(random.nextInt(decors.size()));
	}

	/** 摊位没放满时被挤掉的人数（配置的摊位区太小会 > 0）。 */
	int lastOverflow() {
		return lastOverflow;
	}

	/** 摊位总数，用于 /bazaar status。 */
	static int capacity(BazaarConfig config) {
		return config.playerStalls().slots().size();
	}
}
