package com.xiyuan.plugmc.economy.mail;

import com.xiyuan.plugmc.economy.internal.EconomyStore;
import com.xiyuan.plugmc.economy.internal.ItemCodec;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.util.UUID;

public record MailEntry(
	long id,
	UUID owner,
	ItemStack item,
	BigDecimal money,
	String source,
	long createdAt,
	long expiresAt,
	boolean claimed
) {
	public MailEntry {
		item = item == null ? null : item.clone();
	}

	public static MailEntry fromDto(EconomyStore.MailEntryDto dto) {
		if (dto == null) {
			throw new IllegalArgumentException("邮件数据为空");
		}
		return new MailEntry(
			Long.parseLong(dto.id),
			UUID.fromString(dto.owner),
			dto.item == null || dto.item.isBlank() ? null : ItemCodec.decode(dto.item),
			new BigDecimal(dto.money == null || dto.money.isBlank() ? "0.00" : dto.money),
			dto.source == null ? "" : dto.source,
			dto.createdAt,
			dto.expiresAt,
			dto.claimed
		);
	}

	public EconomyStore.MailEntryDto toDto() {
		EconomyStore.MailEntryDto dto = new EconomyStore.MailEntryDto();
		dto.id = Long.toString(id);
		dto.owner = owner.toString();
		dto.item = item == null ? null : ItemCodec.encode(item);
		dto.money = money == null ? "0.00" : money.toPlainString();
		dto.source = source == null ? "" : source;
		dto.createdAt = createdAt;
		dto.expiresAt = expiresAt;
		dto.claimed = claimed;
		return dto;
	}

	public boolean hasItem() {
		return item != null && !item.getType().isAir() && item.getAmount() > 0;
	}

	public boolean expired(long now) {
		return expiresAt > 0 && expiresAt <= now;
	}
}
