package com.xiyuan.plugmc.megastructures;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 巨构维度里的观景辅助：允许飞行、常驻夜视。
 * 只撤销自己授予的状态，玩家原本就能飞或本来带夜视时不记录、不回收。
 */
public final class MegaComfort {
	private final MegaPlugin plugin;
	private final Set<UUID> flightGranted = new HashSet<>();
	private final Set<UUID> nightVisionGranted = new HashSet<>();

	public MegaComfort(MegaPlugin plugin) {
		this.plugin = plugin;
	}

	public void sync(Player player) {
		if (!plugin.megaWorld().isMega(player.getLocation())) {
			release(player);
			return;
		}
		MegaConfig config = plugin.settings();
		GameMode mode = player.getGameMode();
		if (config.flight()
			&& player.hasPermission("mega.fly")
			&& mode != GameMode.CREATIVE
			&& mode != GameMode.SPECTATOR
			&& !player.getAllowFlight()) {
			player.setAllowFlight(true);
			flightGranted.add(player.getUniqueId());
		}
		if (config.nightVision() && !player.hasPotionEffect(PotionEffectType.NIGHT_VISION)) {
			player.addPotionEffect(new PotionEffect(
				PotionEffectType.NIGHT_VISION,
				Integer.MAX_VALUE,
				0,
				true,
				false,
				false
			));
			nightVisionGranted.add(player.getUniqueId());
		}
	}

	public void release(Player player) {
		UUID playerId = player.getUniqueId();
		if (flightGranted.remove(playerId)) {
			GameMode mode = player.getGameMode();
			if (mode != GameMode.CREATIVE && mode != GameMode.SPECTATOR) {
				player.setFlying(false);
				player.setAllowFlight(false);
			}
		}
		if (nightVisionGranted.remove(playerId)) {
			player.removePotionEffect(PotionEffectType.NIGHT_VISION);
		}
	}

	public void releaseAll() {
		for (Player player : Bukkit.getOnlinePlayers()) {
			release(player);
		}
	}
}
