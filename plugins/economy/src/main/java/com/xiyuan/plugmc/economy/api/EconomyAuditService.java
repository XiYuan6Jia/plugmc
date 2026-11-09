package com.xiyuan.plugmc.economy.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * 经济审计接口：供管理面板等只读展示使用，不修改任何数据。
 */
public interface EconomyAuditService {
	/** 统计摘要，每行一条。 */
	List<String> statsLines();

	/** 账本与托管对账结果。 */
	List<Check> doctorChecks();

	/** 需要管理员留意的提示（如到期待结算数量）。 */
	List<String> reminders();

	/** 内存演练结果，不接触真实数据。 */
	List<Check> selfTestChecks();

	/**
	 * 玩家签到状态，用于菜单展示，例如「今日已签到（连续 3 天）」。
	 */
	String dailyStatus(UUID playerId);

	/**
	 * 玩家支出是否被冻结。
	 */
	boolean isFrozen(UUID playerId);

	/**
	 * 财政账户余额，供管理面板展示。
	 */
	BigDecimal treasuryBalance();

	record Check(String name, boolean passed, String detail) {
	}
}
