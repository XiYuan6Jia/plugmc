package com.xiyuan.plugmc.minigames;

import com.xiyuan.plugmc.economy.api.EconomyService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.math.BigDecimal;

/**
 * 经济系统桥接：投注走 withdraw（GAME_*_BET），派彩走 deposit（GAME_*_WIN），
 * 两条都能在 economy 账本里按原因区分。
 */
public final class EconomyBridge {
	private final MinigamesPlugin plugin;
	private volatile EconomyService economy;

	public EconomyBridge(MinigamesPlugin plugin) {
		this.plugin = plugin;
	}

	public void refresh() {
		economy = Bukkit.getServicesManager().load(EconomyService.class);
		if (economy == null) {
			plugin.getLogger().warning("EconomyService 未注册，小游戏暂时不能下注");
		}
	}

	public boolean ready() {
		return economy != null;
	}

	public EconomyService service() {
		return economy;
	}

	public BigDecimal balance(Player player) {
		return economy == null ? BigDecimal.ZERO : economy.getBalance(player.getUniqueId());
	}

	public String format(BigDecimal amount) {
		return economy == null ? amount.toPlainString() : economy.format(amount);
	}

	public boolean canAfford(Player player, BigDecimal amount) {
		return economy != null && economy.has(player.getUniqueId(), amount);
	}

	public boolean withdraw(Player player, BigDecimal amount, String reason) {
		return economy != null && economy.withdraw(player.getUniqueId(), amount, reason);
	}

	public boolean deposit(Player player, BigDecimal amount, String reason) {
		return economy != null && (amount.signum() <= 0 || economy.deposit(player.getUniqueId(), amount, reason));
	}
}
