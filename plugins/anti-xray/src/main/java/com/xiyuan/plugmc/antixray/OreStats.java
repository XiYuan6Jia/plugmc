package com.xiyuan.plugmc.antixray;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 单个玩家的矿石挖掘统计（时间窗口内）。
 */
public final class OreStats {
	private final String playerName;
	private final Deque<Event> events = new ArrayDeque<>();
	private long lastAlertAt;

	public OreStats(String playerName) {
		this.playerName = playerName == null ? "" : playerName;
	}

	public synchronized void record(boolean hidden) {
		events.addLast(new Event(System.currentTimeMillis(), hidden));
	}

	public synchronized int hiddenWithin(long windowMillis) {
		prune(windowMillis);
		int count = 0;
		for (Event event : events) {
			if (event.hidden()) {
				count++;
			}
		}
		return count;
	}

	public synchronized int totalWithin(long windowMillis) {
		prune(windowMillis);
		return events.size();
	}

	public synchronized long lastAlertAt() {
		return lastAlertAt;
	}

	public synchronized void lastAlertAt(long value) {
		this.lastAlertAt = value;
	}

	public String playerName() {
		return playerName;
	}

	private void prune(long windowMillis) {
		long cutoff = System.currentTimeMillis() - Math.max(0L, windowMillis);
		while (!events.isEmpty() && events.peekFirst().timestamp() < cutoff) {
			events.removeFirst();
		}
	}

	private record Event(long timestamp, boolean hidden) {
	}
}
