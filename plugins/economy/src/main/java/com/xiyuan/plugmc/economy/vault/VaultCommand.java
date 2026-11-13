package com.xiyuan.plugmc.economy.vault;

import com.xiyuan.plugmc.economy.api.EconomyService;
import com.xiyuan.plugmc.economy.bank.BankService;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * {@code /vault} —— 回收箱命令。
 *
 * <p>玩家版不需要参数：对着箱子敲 {@code /vault} 就绑定准星指着的那块方块。
 * 控制台看不了准星，所以支持显式坐标 {@code /vault bind <x> <y> <z> <玩家>}，
 * 世界取那个玩家的所在地（主城场景里绑定的就是主城维度里的箱子）。</p>
 */
public final class VaultCommand implements CommandExecutor, TabCompleter {
	private static final List<String> SUBCOMMANDS = List.of("bind", "unbind", "info", "settle");
	private static final int REACH = 6;

	private final VaultService vault;
	private final BankService bank;
	private final EconomyService economy;

	public VaultCommand(VaultService vault, BankService bank, EconomyService economy) {
		this.vault = vault;
		this.bank = bank;
		this.economy = economy;
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		String sub = args.length == 0 ? "bind" : args[0].toLowerCase(Locale.ROOT);
		switch (sub) {
			case "bind" -> bind(sender, args);
			case "unbind" -> unbind(sender, args);
			case "info" -> info(sender, args);
			case "settle" -> settle(sender, args);
			default -> sender.sendMessage("用法：/vault [bind|unbind|info|settle]"
				+ "（玩家对着箱子直接 /vault 即可；控制台：/vault bind <x> <y> <z> <玩家>）");
		}
		return true;
	}

	private void bind(CommandSender sender, String[] args) {
		// /vault bind <x> <y> <z> <玩家>：坐标从 args[1] 起，玩家名在 args[4]
		Player actor = actor(sender, args, 4);
		if (actor == null) {
			return;
		}
		Block block = target(sender, args, 1, actor.getWorld());
		if (block == null) {
			return;
		}
		vault.bind(actor, block);
	}

	private void unbind(CommandSender sender, String[] args) {
		// 与 bind 同样的参数布局：<x> <y> <z> [玩家]
		Player actor = actor(sender, args, 4);
		if (actor == null) {
			return;
		}
		Block block = target(sender, args, 1, actor.getWorld());
		if (block == null) {
			return;
		}
		vault.unbind(actor, block);
	}

	private void info(CommandSender sender, String[] args) {
		World fallback = sender instanceof Player player ? player.getWorld() : null;
		Block block = target(sender, args, 1, fallback);
		if (block == null) {
			return;
		}
		UUID owner = vault.ownerOf(block);
		if (owner == null) {
			sender.sendMessage("这个方块还不是回收箱。");
			return;
		}
		String name = vault.ownerNameOf(block);
		if (name == null || name.isBlank()) {
			OfflinePlayer offline = Bukkit.getOfflinePlayer(owner);
			name = offline.getName() == null ? owner.toString() : offline.getName();
		}
		sender.sendMessage("回收箱主人：" + name);
		sender.sendMessage("建材额度：" + bank.format(bank.creditOf(owner))
			+ "，余额：" + economy.format(economy.getBalance(owner)));
	}

	private void settle(CommandSender sender, String[] args) {
		World fallback = sender instanceof Player player ? player.getWorld() : null;
		Block block = target(sender, args, 1, fallback);
		if (block == null) {
			return;
		}
		if (!vault.isVault(block)) {
			sender.sendMessage("这个方块还不是回收箱。");
			return;
		}
		vault.settle(block);
		sender.sendMessage("已结算一次。");
	}

	/** 有显式玩家名就用那个玩家；否则必须是执行命令的玩家本人。 */
	private Player actor(CommandSender sender, String[] args, int index) {
		if (args.length > index) {
			Player target = Bukkit.getPlayerExact(args[index]);
			if (target == null) {
				sender.sendMessage("玩家不在线：" + args[index]);
			}
			return target;
		}
		if (sender instanceof Player player) {
			return player;
		}
		sender.sendMessage("控制台请指定玩家：/vault bind <x> <y> <z> <玩家>");
		return null;
	}

	/**
	 * 目标方块：够参数就按坐标取（世界用 fallback），否则取玩家准星指着的那块。
	 */
	private Block target(CommandSender sender, String[] args, int start, World fallback) {
		if (args.length >= start + 3) {
			int x;
			int y;
			int z;
			try {
				x = Integer.parseInt(args[start]);
				y = Integer.parseInt(args[start + 1]);
				z = Integer.parseInt(args[start + 2]);
			} catch (NumberFormatException exception) {
				sender.sendMessage("坐标格式不对，需要三个整数。");
				return null;
			}
			if (fallback == null) {
				sender.sendMessage("这个位置用控制台执行时需要指定玩家（世界取自玩家所在地）。");
				return null;
			}
			return fallback.getBlockAt(x, y, z);
		}
		if (!(sender instanceof Player player)) {
			sender.sendMessage("控制台请给出坐标：/vault bind <x> <y> <z> <玩家>");
			return null;
		}
		Block hit = player.getTargetBlockExact(REACH);
		if (hit == null) {
			player.sendMessage("请先看着一个箱子或木桶再执行（或者给出坐标）。");
			return null;
		}
		return hit;
	}

	@Override
	public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
		if (args.length == 1) {
			List<String> out = new ArrayList<>();
			String prefix = args[0].toLowerCase(Locale.ROOT);
			for (String sub : SUBCOMMANDS) {
				if (sub.startsWith(prefix)) {
					out.add(sub);
				}
			}
			return out;
		}
		return List.of();
	}
}
