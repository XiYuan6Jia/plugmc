package com.xiyuan.plugmc.tpa;

import java.util.UUID;

/**
 * 一条待处理的传送申请。
 *
 * <p>{@link Direction#TO_TARGET} 表示申请人前往目标，{@link Direction#TO_SENDER} 表示目标被拉
 * 到申请人身边，{@link Direction#TO_HOME} 表示申请人前往目标的某个家园。</p>
 *
 * <p>{@code homeName} 只在 {@code TO_HOME} 时有意义；为空表示「用那个人的默认家园」，
 * 具体解析留到接受那一刻做 —— 申请期间对方可能把家删了。</p>
 */
record TpaRequest(
	UUID from,
	String fromName,
	UUID to,
	String toName,
	Direction direction,
	String homeName,
	long expiresAt
) {
	enum Direction {
		TO_TARGET,
		TO_SENDER,
		TO_HOME
	}

	boolean expired(long now) {
		return expiresAt <= now;
	}

	boolean isHome() {
		return direction == Direction.TO_HOME;
	}

	String describe() {
		return switch (direction) {
			case TO_TARGET -> fromName + " 想传送到你身边";
			case TO_SENDER -> fromName + " 想把你传送到他身边";
			case TO_HOME -> fromName + " 想传送到你的家"
				+ (homeName == null || homeName.isBlank() ? "" : "（" + homeName + "）");
		};
	}
}
