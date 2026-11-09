package com.xiyuan.plugmc.economy.api;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * 经济事务提交成功后触发（同步，主线程执行）。
 * 监听者可据此做成就、统计、日志等联动；不要在此事件中再次发起经济操作。
 */
public final class EconomyTransactionEvent extends Event {
	private static final HandlerList HANDLERS = new HandlerList();

	private final String reason;
	private final String reference;
	private final long sequence;
	private final List<Entry> entries;

	public EconomyTransactionEvent(String reason, String reference, long sequence, List<Entry> entries) {
		this.reason = reason;
		this.reference = reference;
		this.sequence = sequence;
		this.entries = List.copyOf(entries);
	}

	/** 事务原因，如 PAY、MARKET_BUY、AUCTION_SETTLE。 */
	public String reason() {
		return reason;
	}

	/** 事务关联的业务编号，可能为 null。 */
	public String reference() {
		return reference;
	}

	public long sequence() {
		return sequence;
	}

	public List<Entry> entries() {
		return entries;
	}

	/** 指定玩家在本事务中的净变动，未参与时返回 0。 */
	public BigDecimal playerDelta(UUID playerId) {
		String key = "player:" + playerId;
		BigDecimal sum = BigDecimal.ZERO;
		for (Entry entry : entries) {
			if (key.equals(entry.account())) {
				sum = sum.add(entry.delta());
			}
		}
		return sum;
	}

	public boolean involvesPlayer(UUID playerId) {
		String key = "player:" + playerId;
		for (Entry entry : entries) {
			if (key.equals(entry.account())) {
				return true;
			}
		}
		return false;
	}

	@Override
	public HandlerList getHandlers() {
		return HANDLERS;
	}

	public static HandlerList getHandlerList() {
		return HANDLERS;
	}

	public record Entry(String account, BigDecimal delta) {
	}
}
