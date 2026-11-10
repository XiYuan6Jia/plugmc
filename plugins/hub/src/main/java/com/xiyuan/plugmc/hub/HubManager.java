package com.xiyuan.plugmc.hub;

import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * 进主城 / 返回原位置。返回点写入 returns.yml，重启后依然可用。
 */
public final class HubManager {
	private final HubPlugin plugin;
	private final HubReturns returns;

	public HubManager(HubPlugin plugin, HubReturns returns) {
		this.plugin = plugin;
		this.returns = returns;
	}

	public boolean enter(Player player) {
		HubConfig config = plugin.settings();
		if (!plugin.hubWorld().ready()) {
			player.sendMessage(HubText.component("&c主城维度尚未加载，请联系管理员。"));
			return false;
		}
		Location target = plugin.hubWorld().spawn(config);
		Location from = player.getLocation();
		if (!plugin.hubWorld().isHub(from)) {
			returns.remember(player.getUniqueId(), from);
		}
		if (!player.teleport(target)) {
			player.sendMessage(HubText.component("&c传送失败，请稍后再试。"));
			return false;
		}
		player.sendMessage(HubText.component("&a已进入 &6主城大厅 &a。")
			.append(net.kyori.adventure.text.Component.text(" "))
			.append(HubText.component("&e[返回原位置]")
				.clickEvent(ClickEvent.runCommand("hub back"))
				.hoverEvent(HoverEvent.showText(HubText.component("&7回到传送前的位置")))));
		return true;
	}

	/** 其他方式（菜单、传送命令、其它插件）把玩家送进主城时也记下返回点。 */
	public void rememberOnEnter(Player player, Location from) {
		if (from == null || plugin.hubWorld().isHub(from)) {
			return;
		}
		returns.remember(player.getUniqueId(), from);
	}

	public boolean back(Player player) {
		Location target = returns.take(player.getUniqueId());
		if (target == null || target.getWorld() == null) {
			target = fallbackPoint(player);
			player.sendMessage(HubText.component("&7没有找到传送前的位置，已回到主世界出生点。"));
		}
		if (target.getWorld() == null) {
			player.sendMessage(HubText.component("&c找不到可返回的世界。"));
			return false;
		}
		return player.teleport(target);
	}

	public void forget(UUID playerId) {
		returns.forget(playerId);
	}

	private Location fallbackPoint(Player player) {
		World main = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
		if (main == null) {
			return player.getWorld().getSpawnLocation();
		}
		return main.getSpawnLocation();
	}
}
