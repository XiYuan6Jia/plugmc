package com.xiyuan.plugmc.economy.auction;

import com.xiyuan.plugmc.economy.internal.Money;
import org.bukkit.configuration.file.FileConfiguration;

import java.math.BigDecimal;

public record AuctionConfig(
	boolean enabled,
	int slots,
	int durationHours,
	BigDecimal minIncrement,
	double taxRate,
	double minIncrementRate,
	int antiSnipeSeconds,
	int maxExtensions,
	double listingFeeRate,
	BigDecimal listingFeeMin
) {
	public AuctionConfig {
		slots = Math.max(0, Math.min(100, slots));
		durationHours = Math.max(1, Math.min(720, durationHours));
		taxRate = clampRate(taxRate);
		minIncrementRate = clampRate(minIncrementRate);
		listingFeeRate = clampRate(listingFeeRate);
		antiSnipeSeconds = Math.max(0, Math.min(600, antiSnipeSeconds));
		maxExtensions = Math.max(0, Math.min(50, maxExtensions));
		if (minIncrement == null || minIncrement.signum() <= 0) {
			throw new IllegalArgumentException("auction.min-increment 必须是正金额");
		}
		if (listingFeeMin == null || listingFeeMin.signum() < 0) {
			throw new IllegalArgumentException("auction.listing-fee-min 必须是非负金额");
		}
	}

	/**
	 * 兼容旧调用：只关心必填项时其余字段使用默认值。
	 */
	public AuctionConfig(boolean enabled, int slots, int durationHours, BigDecimal minIncrement, double taxRate) {
		this(
			enabled,
			slots,
			durationHours,
			minIncrement,
			taxRate,
			0.05,
			60,
			5,
			0.02,
			new BigDecimal("10.00")
		);
	}

	public static AuctionConfig load(FileConfiguration config, int decimalPlaces) {
		return new AuctionConfig(
			config.getBoolean("auction.enabled", true),
			config.getInt("auction.slots", 5),
			config.getInt("auction.duration-hours", 48),
			Money.parse(config.getString("auction.min-increment", "1.00"), decimalPlaces, false),
			config.getDouble("auction.tax-rate", 0.03),
			config.getDouble("auction.min-increment-rate", 0.05),
			config.getInt("auction.anti-snipe-seconds", 60),
			config.getInt("auction.max-extensions", 5),
			config.getDouble("auction.listing-fee-rate", 0.02),
			Money.parse(config.getString("auction.listing-fee-min", "10.00"), decimalPlaces, true)
		);
	}

	public BigDecimal tax(BigDecimal total, int decimalPlaces) {
		return Money.ceil(total.multiply(BigDecimal.valueOf(taxRate)), decimalPlaces);
	}

	/**
	 * 当前价越高，最小加价按比例提高；没有出价时使用固定最小加价。
	 */
	public BigDecimal minIncrementFor(BigDecimal currentBid, int decimalPlaces) {
		if (currentBid == null || currentBid.signum() <= 0 || minIncrementRate <= 0) {
			return minIncrement;
		}
		BigDecimal byRate = Money.ceil(
			currentBid.multiply(BigDecimal.valueOf(minIncrementRate)),
			decimalPlaces
		);
		return byRate.compareTo(minIncrement) > 0 ? byRate : minIncrement;
	}

	public BigDecimal listingFee(BigDecimal startingPrice, int decimalPlaces) {
		if (startingPrice == null || startingPrice.signum() <= 0 || listingFeeRate <= 0) {
			return Money.zero(decimalPlaces);
		}
		BigDecimal byRate = Money.ceil(
			startingPrice.multiply(BigDecimal.valueOf(listingFeeRate)),
			decimalPlaces
		);
		BigDecimal fee = byRate.compareTo(listingFeeMin) > 0 ? byRate : listingFeeMin;
		return Money.ceil(fee, decimalPlaces);
	}

	/**
	 * 最后 {@code antiSnipeSeconds} 秒内出价则延长，最多 {@code maxExtensions} 次。
	 */
	public boolean shouldExtend(long now, long expiresAt, int extensions) {
		if (antiSnipeSeconds <= 0 || maxExtensions <= 0 || extensions >= maxExtensions) {
			return false;
		}
		if (expiresAt <= now) {
			return false;
		}
		return expiresAt - now <= antiSnipeSeconds * 1000L;
	}

	public long extendedExpiry(long now) {
		return now + antiSnipeSeconds * 1000L;
	}

	private static double clampRate(double value) {
		return Math.max(0.0, Math.min(1.0, value));
	}
}
