package com.xiyuan.plugmc.sleepvote;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerBedEnterEvent;

public final class SleepListener implements Listener {
	private final SleepVotePlugin plugin;

	public SleepListener(SleepVotePlugin plugin) {
		this.plugin = plugin;
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onBedEnter(PlayerBedEnterEvent event) {
		if (event.getBedEnterResult() != PlayerBedEnterEvent.BedEnterResult.OK) {
			return;
		}
		Player player = event.getPlayer();
		// 上床当刻 isSleeping() 还是 false，下一次 tick 再统计
		plugin.getServer().getScheduler().runTask(plugin, () -> plugin.checkSleep(player));
	}
}
