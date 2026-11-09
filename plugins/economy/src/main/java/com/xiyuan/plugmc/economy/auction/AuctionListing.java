package com.xiyuan.plugmc.economy.auction;

import com.xiyuan.plugmc.economy.internal.EconomyStore;
import com.xiyuan.plugmc.economy.internal.ItemCodec;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.util.UUID;

public record AuctionListing(
	long id,
	UUID seller,
	String sellerName,
	ItemStack item,
	BigDecimal startingPrice,
	BigDecimal buyoutPrice,
	UUID highestBidder,
	String highestBidderName,
	BigDecimal highestBid,
	long createdAt,
	long expiresAt,
	Status status
) {
	public enum Status {
		ACTIVE,
		SOLD,
		EXPIRED,
		CANCELLED
	}

	public AuctionListing {
		item = item.clone();
	}

	public static AuctionListing fromDto(EconomyStore.AuctionDto dto) {
		if (dto == null) {
			throw new IllegalArgumentException("拍卖数据为空");
		}
		return new AuctionListing(
			Long.parseLong(dto.id),
			UUID.fromString(dto.seller),
			dto.sellerName == null ? "" : dto.sellerName,
			ItemCodec.decode(dto.item),
			new BigDecimal(dto.startingPrice),
			new BigDecimal(dto.buyoutPrice),
			dto.highestBidder == null || dto.highestBidder.isBlank() ? null : UUID.fromString(dto.highestBidder),
			dto.highestBidderName == null ? "" : dto.highestBidderName,
			new BigDecimal(dto.highestBid),
			dto.createdAt,
			dto.expiresAt,
			Status.valueOf(dto.status)
		);
	}

	public EconomyStore.AuctionDto toDto() {
		EconomyStore.AuctionDto dto = new EconomyStore.AuctionDto();
		dto.id = Long.toString(id);
		dto.seller = seller.toString();
		dto.sellerName = sellerName;
		dto.item = ItemCodec.encode(item);
		dto.startingPrice = startingPrice.toPlainString();
		dto.buyoutPrice = buyoutPrice.toPlainString();
		dto.highestBidder = highestBidder == null ? "" : highestBidder.toString();
		dto.highestBidderName = highestBidderName;
		dto.highestBid = highestBid.toPlainString();
		dto.createdAt = createdAt;
		dto.expiresAt = expiresAt;
		dto.status = status.name();
		return dto;
	}

	public boolean active() {
		return status == Status.ACTIVE;
	}

	public boolean hasBid() {
		return highestBidder != null && highestBid.signum() > 0;
	}

	public boolean expired(long now) {
		return expiresAt > 0 && expiresAt <= now;
	}

	public boolean hasBuyout() {
		return buyoutPrice.signum() > 0;
	}

	public AuctionListing withBid(UUID bidder, String bidderName, BigDecimal amount) {
		return new AuctionListing(
			id, seller, sellerName, item, startingPrice, buyoutPrice,
			bidder, bidderName, amount, createdAt, expiresAt, status
		);
	}
}
