package com.xiyuan.plugmc.tpa;

import java.util.UUID;

/**
 * 一条待处理的传送申请。{@link Direction#TO_TARGET} 表示申请人前往目标，
 * {@link Direction#TO_SENDER} 表示目标被拉到申请人身边。
 */
record TpaRequest(
	UUID from,
	String fromName,
	UUID to,
	String toName,
	Direction direction,
	long expiresAt
) {
	enum Direction {
		TO_TARGET,
		TO_SENDER
	}

	boolean expired(long now) {
		return expiresAt <= now;
	}

	String describe() {
		return direction == Direction.TO_TARGET
			? fromName + " 想传送到你身边"
			: fromName + " 想把你传送到他身边";
	}
}
