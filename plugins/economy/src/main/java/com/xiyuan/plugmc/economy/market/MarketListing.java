package com.xiyuan.plugmc.economy.market;

import com.xiyuan.plugmc.economy.internal.EconomyStore;
import com.xiyuan.plugmc.economy.internal.ItemCodec;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.util.UUID;

public record MarketListing(
	long id,
	UUID seller,
	String sellerName,
	ItemStack item,
	BigDecimal unitPrice,
	long createdAt,
	long expiresAt,
	Status status,
	boolean escrowPending
) {
	public enum Status {
		PENDING,
		ACTIVE,
		SOLD,
		EXPIRED,
		CANCELLED
	}

	public MarketListing {
		item = item.clone();
	}

	public static MarketListing fromDto(EconomyStore.MarketListingDto dto) {
		if (dto == null) {
			throw new IllegalArgumentException("市场挂单数据为空");
		}
		return new MarketListing(
			Long.parseLong(dto.id),
			UUID.fromString(dto.seller),
			dto.sellerName == null ? "" : dto.sellerName,
			ItemCodec.decode(dto.item),
			new BigDecimal(dto.unitPrice),
			dto.createdAt,
			dto.expiresAt,
			Status.valueOf(dto.status),
			dto.escrowPending
		);
	}

	public EconomyStore.MarketListingDto toDto() {
		EconomyStore.MarketListingDto dto = new EconomyStore.MarketListingDto();
		dto.id = Long.toString(id);
		dto.seller = seller.toString();
		dto.sellerName = sellerName;
		dto.item = ItemCodec.encode(item);
		dto.unitPrice = unitPrice.toPlainString();
		dto.createdAt = createdAt;
		dto.expiresAt = expiresAt;
		dto.status = status.name();
		dto.escrowPending = escrowPending;
		return dto;
	}

	public int amount() {
		return item.getAmount();
	}

	public boolean active() {
		return status == Status.ACTIVE && !escrowPending;
	}

	public boolean expired(long now) {
		return expiresAt > 0 && expiresAt <= now;
	}

	public MarketListing withRemaining(int remaining) {
		ItemStack copy = item.clone();
		copy.setAmount(Math.max(0, remaining));
		return new MarketListing(
			id,
			seller,
			sellerName,
			copy,
			unitPrice,
			createdAt,
			expiresAt,
			remaining <= 0 ? Status.SOLD : status,
			false
		);
	}

	public MarketListing withStatus(Status newStatus, boolean pending) {
		return new MarketListing(id, seller, sellerName, item, unitPrice, createdAt, expiresAt, newStatus, pending);
	}
}
