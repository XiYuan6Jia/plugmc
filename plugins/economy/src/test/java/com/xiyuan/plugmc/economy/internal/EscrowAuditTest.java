package com.xiyuan.plugmc.economy.internal;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EscrowAuditTest {
	private static EconomyStore.AuctionDto auction(String status, String highestBid, long expiresAt) {
		EconomyStore.AuctionDto dto = new EconomyStore.AuctionDto();
		dto.id = "1";
		dto.status = status;
		dto.highestBid = highestBid;
		dto.expiresAt = expiresAt;
		return dto;
	}

	private static EconomyStore.OrderDto order(String status, String unitPrice, int remaining, long expiresAt) {
		EconomyStore.OrderDto dto = new EconomyStore.OrderDto();
		dto.id = "1";
		dto.status = status;
		dto.unitPrice = unitPrice;
		dto.remaining = remaining;
		dto.expiresAt = expiresAt;
		return dto;
	}

	@Test
	void auctionEscrowSumsOnlyActiveHighestBids() {
		assertEquals(
			"30.00",
			EscrowAudit.expectedAuctionEscrow(List.of(
				auction("ACTIVE", "10.00", 0),
				auction("ACTIVE", "20.00", 0),
				auction("ACTIVE", "", 0),
				auction("SOLD", "99.00", 0),
				auction("CANCELLED", "50.00", 0)
			)).toPlainString()
		);
	}

	@Test
	void orderEscrowMultipliesUnitPriceByRemaining() {
		assertEquals(
			"35.00",
			EscrowAudit.expectedOrderEscrow(List.of(
				order("ACTIVE", "5.00", 3, 0),
				order("ACTIVE", "10.00", 2, 0),
				order("ACTIVE", "7.00", 0, 0),
				order("FILLED", "100.00", 5, 0)
			)).toPlainString()
		);
	}

	@Test
	void expiredCountersIgnoreFinishedEntries() {
		long now = 1_000L;
		assertEquals(1, EscrowAudit.expiredAuctions(List.of(
			auction("ACTIVE", "1.00", 999L),
			auction("ACTIVE", "1.00", 1_001L),
			auction("SOLD", "1.00", 1L)
		), now));
		assertEquals(1, EscrowAudit.expiredOrders(List.of(
			order("ACTIVE", "1.00", 1, 500L),
			order("ACTIVE", "1.00", 1, 2_000L),
			order("CANCELLED", "1.00", 1, 1L)
		), now));
	}
}
