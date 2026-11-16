package com.xiyuan.plugmc.megastructures;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

import java.util.UUID;

/** 进入巨构 / 返回原位置。返回点写入 returns.yml，重启后依然可用。 */
public final class MegaManager {
	/**
	 * 与 hub 插件约定：返回传送期间设置此临时 PDC 标记，目标端不再覆盖自己的返回点。
	 * 标记只在 teleport() 调用期间存在。
	 */
	static final NamespacedKey RETURN_KEY = NamespacedKey.fromString("plugmc:return_teleport");

	private final MegaPlugin plugin;
	private final MegaReturns returns;

	public MegaManager(MegaPlugin plugin, MegaReturns returns) {
		this.plugin = plugin;
		this.returns = returns;
	}

	public boolean enter(Player player, String viewpointId) {
		MegaViewpoint viewpoint = MegaStructures.viewpoint(viewpointId);
		if (viewpoint == null) {
			player.sendMessage(MegaText.component("&c未知观景点：" + viewpointId + "，用 /mega list 查看。"));
			return false;
		}
		return enter(player, viewpoint);
	}

	public boolean enter(Player player, MegaViewpoint viewpoint) {
		if (!plugin.megaWorld().ready()) {
			player.sendMessage(MegaText.component("&c巨构维度尚未加载，请联系管理员。"));
			return false;
		}
		Location from = player.getLocation();
		if (!plugin.megaWorld().isMega(from)) {
			returns.remember(player.getUniqueId(), from);
		}
		Location target = plugin.megaWorld().viewpoint(viewpoint);
		if (!player.teleport(target)) {
			player.sendMessage(MegaText.component("&c传送失败，请稍后再试。"));
			return false;
		}
		plugin.getServer().getScheduler().runTask(plugin, () -> {
			if (player.isOnline()) {
				plugin.comfort().sync(player);
				plugin.interactions().ensureViewpoint(viewpoint);
			}
		});
		player.showTitle(Title.title(
			MegaText.component("&6" + viewpoint.label()),
			MegaText.component("&7" + viewpoint.description())
		));
		player.sendMessage(MegaText.component("&a已抵达 &6" + viewpoint.label() + "&a。")
			.append(Component.text(" "))
			.append(MegaText.component("&e[返回原位置]")
				.clickEvent(ClickEvent.runCommand("mega back"))
				.hoverEvent(HoverEvent.showText(MegaText.component("&7回到传送前的位置")))));
		return true;
	}

	public void rememberOnEnter(Player player, Location from) {
		if (from == null || plugin.megaWorld().isMega(from)) {
			return;
		}
		returns.remember(player.getUniqueId(), from);
	}

	public boolean back(Player player) {
		Location target = returns.take(player.getUniqueId());
		if (target == null || target.getWorld() == null) {
			target = fallbackPoint(player);
			player.sendMessage(MegaText.component("&7没有找到传送前的位置，已回到主世界出生点。"));
		}
		if (target.getWorld() == null) {
			player.sendMessage(MegaText.component("&c找不到可返回的世界。"));
			return false;
		}
		player.getPersistentDataContainer().set(RETURN_KEY, PersistentDataType.BYTE, (byte) 1);
		try {
			return player.teleport(target);
		} finally {
			player.getPersistentDataContainer().remove(RETURN_KEY);
		}
	}

	/** 这次传送是否由本插件或其它插件的“返回原位置”发起。 */
	public static boolean isReturnTeleport(Player player) {
		return player.getPersistentDataContainer().has(RETURN_KEY, PersistentDataType.BYTE);
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
