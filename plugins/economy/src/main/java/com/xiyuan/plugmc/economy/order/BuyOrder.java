package com.xiyuan.plugmc.economy.order;

import com.xiyuan.plugmc.economy.internal.EconomyStore;
import com.xiyuan.plugmc.economy.internal.ItemCodec;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.util.UUID;

public record BuyOrder(
	long id,
	UUID buyer,
	String buyerName,
	ItemStack template,
	BigDecimal unitPrice,
	int remaining,
	long createdAt,
	long expiresAt,
	Status status
) {
	public enum Status {
		ACTIVE,
		FILLED,
		EXPIRED,
		CANCELLED
	}

	public BuyOrder {
		template = template.clone();
	}

	public static BuyOrder fromDto(EconomyStore.OrderDto dto) {
		if (dto == null) {
			throw new IllegalArgumentException("收购订单数据为空");
		}
		return new BuyOrder(
			Long.parseLong(dto.id),
			UUID.fromString(dto.buyer),
			dto.buyerName == null ? "" : dto.buyerName,
			ItemCodec.decode(dto.item),
			new BigDecimal(dto.unitPrice),
			dto.remaining,
			dto.createdAt,
			dto.expiresAt,
			Status.valueOf(dto.status)
		);
	}

	public EconomyStore.OrderDto toDto() {
		EconomyStore.OrderDto dto = new EconomyStore.OrderDto();
		dto.id = Long.toString(id);
		dto.buyer = buyer.toString();
		dto.buyerName = buyerName;
		dto.item = ItemCodec.encode(template);
		dto.unitPrice = unitPrice.toPlainString();
		dto.remaining = remaining;
		dto.createdAt = createdAt;
		dto.expiresAt = expiresAt;
		dto.status = status.name();
		return dto;
	}

	public boolean active() {
		return status == Status.ACTIVE && remaining > 0;
	}

	public boolean expired(long now) {
		return expiresAt > 0 && expiresAt <= now;
	}

	public BuyOrder withRemaining(int newRemaining, Status newStatus) {
		return new BuyOrder(
			id,
			buyer,
			buyerName,
			template,
			unitPrice,
			Math.max(0, newRemaining),
			createdAt,
			expiresAt,
			newStatus
		);
	}
}
