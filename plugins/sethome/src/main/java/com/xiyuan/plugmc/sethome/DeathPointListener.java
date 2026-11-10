package com.xiyuan.plugmc.sethome;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

import java.util.Locale;

/**
 * 死亡后在聊天栏给出可点击的「返回死亡地点」按钮。
 */
public final class DeathPointListener implements Listener {
	private final SetHomePlugin plugin;

	public DeathPointListener(SetHomePlugin plugin) {
		this.plugin = plugin;
	}

	@EventHandler
	public void onDeath(PlayerDeathEvent event) {
		if (!plugin.deathpointEnabled()) {
			return;
		}
		Player player = event.getEntity();
		Bukkit.getScheduler().runTaskLater(plugin, () -> {
			if (!player.isOnline()) {
				return;
			}
			Location location = player.getLastDeathLocation();
			if (location == null || location.getWorld() == null) {
				return;
			}
			Component message = HomeText.of("&7你死亡于 &f"
				+ String.format(Locale.ROOT, "%s %.1f, %.1f, %.1f",
					location.getWorld().getName(), location.getX(), location.getY(), location.getZ())
				+ "&7，")
				.append(HomeText.of("&e&l[点击传送回死亡地点]")
					.clickEvent(ClickEvent.runCommand("deathpoint"))
					.hoverEvent(HoverEvent.showText(HomeText.of("&7执行 &f/deathpoint&7（有读秒与冷却）"))));
			player.sendMessage(message);
		}, 1L);
	}
}
