package com.xiyuan.plugmc.economy.internal;

import java.math.BigDecimal;
import java.util.Collection;

/**
 * 托管对账：根据未结束的挂单推算托管账户应有余额，用于 /eco doctor 校验。
 */
public final class EscrowAudit {
	private EscrowAudit() {
	}

	public static BigDecimal expectedAuctionEscrow(Collection<EconomyStore.AuctionDto> auctions) {
		BigDecimal expected = BigDecimal.ZERO;
		for (EconomyStore.AuctionDto dto : auctions) {
			if (dto == null || !"ACTIVE".equals(dto.status)) {
				continue;
			}
			if (dto.highestBid == null || dto.highestBid.isBlank()) {
				continue;
			}
			expected = expected.add(new BigDecimal(dto.highestBid));
		}
		return expected;
	}

	public static BigDecimal expectedOrderEscrow(Collection<EconomyStore.OrderDto> orders) {
		BigDecimal expected = BigDecimal.ZERO;
		for (EconomyStore.OrderDto dto : orders) {
			if (dto == null || !"ACTIVE".equals(dto.status) || dto.remaining <= 0) {
				continue;
			}
			if (dto.unitPrice == null || dto.unitPrice.isBlank()) {
				continue;
			}
			expected = expected.add(new BigDecimal(dto.unitPrice).multiply(BigDecimal.valueOf(dto.remaining)));
		}
		return expected;
	}

	public static int expiredAuctions(Collection<EconomyStore.AuctionDto> auctions, long now) {
		int count = 0;
		for (EconomyStore.AuctionDto dto : auctions) {
			if (dto != null && "ACTIVE".equals(dto.status) && dto.expiresAt > 0 && dto.expiresAt <= now) {
				count++;
			}
		}
		return count;
	}

	public static int expiredOrders(Collection<EconomyStore.OrderDto> orders, long now) {
		int count = 0;
		for (EconomyStore.OrderDto dto : orders) {
			if (dto != null && "ACTIVE".equals(dto.status) && dto.expiresAt > 0 && dto.expiresAt <= now) {
				count++;
			}
		}
		return count;
	}
}
