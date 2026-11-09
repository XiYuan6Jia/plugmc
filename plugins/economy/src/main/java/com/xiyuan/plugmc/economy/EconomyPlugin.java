package com.xiyuan.plugmc.economy;

import com.xiyuan.plugmc.economy.api.EconomyService;
import com.xiyuan.plugmc.economy.internal.EconomyServiceImpl;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

public final class EconomyPlugin extends JavaPlugin implements Listener {
	private EconomyServiceImpl economyService;

	@Override
	public void onEnable() {
		saveDefaultConfig();
		economyService = new EconomyServiceImpl(this);
		Bukkit.getServicesManager().register(EconomyService.class, economyService, this, ServicePriority.Normal);
		getServer().getPluginManager().registerEvents(this, this);
		EconomyCommand command = new EconomyCommand(this, economyService);
		getCommand("money").setExecutor(command);
		getCommand("money").setTabCompleter(command);
		getCommand("pay").setExecutor(command);
		getCommand("pay").setTabCompleter(command);
		getCommand("eco").setExecutor(command);
		getCommand("eco").setTabCompleter(command);
		getLogger().info("经济系统 已启用");
	}

	@Override
	public void onDisable() {
		if (economyService != null) {
			Bukkit.getServicesManager().unregister(EconomyService.class, economyService);
		}
	}

	@EventHandler
	public void onPlayerJoin(PlayerJoinEvent event) {
		economyService.createAccount(event.getPlayer().getUniqueId());
	}
}
