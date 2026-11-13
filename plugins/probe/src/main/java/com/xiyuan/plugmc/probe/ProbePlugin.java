package com.xiyuan.plugmc.probe;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 测试服专用的命令派发器。
 *
 * <p><b>为什么需要它</b>：本机没有稳定的键鼠注入通道（注入会抢前台焦点，而且聊天框注入在
 * 26.3 上验证不生效）。经济系统的命令全部要求 {@code sender instanceof Player}，
 * 从控制台/RCON 直接发只会得到一句"用法"。{@link Bukkit#dispatchCommand(CommandSender, String)}
 * 以真实 {@link Player} 作为 sender，命令看到的和执行玩家手打完全一样。</p>
 *
 * <p>只在测试服装，线上不要放。</p>
 */
public final class ProbePlugin extends JavaPlugin implements CommandExecutor {
	@Override
	public void onEnable() {
		if (getCommand("asplayer") != null) {
			getCommand("asplayer").setExecutor(this);
		}
		if (getCommand("probeblock") != null) {
			getCommand("probeblock").setExecutor(this);
		}
		getLogger().info("探针已启用：/asplayer <玩家> <命令>、/probeblock <世界> <x> <y> <z>");
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (command.getName().equalsIgnoreCase("probeblock")) {
			return probeBlock(sender, args);
		}
		if (args.length < 2) {
			sender.sendMessage("用法：/asplayer <玩家> <命令...>（命令不带开头的斜杠）");
			return true;
		}
		Player target = Bukkit.getPlayerExact(args[0]);
		if (target == null) {
			sender.sendMessage("玩家不在线：" + args[0]);
			return true;
		}
		String line = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
		if (line.startsWith("/")) {
			line = line.substring(1);
		}
		boolean ok = Bukkit.dispatchCommand(target, line);
		sender.sendMessage("[" + target.getName() + "] /" + line + " → " + (ok ? "已执行" : "命令不存在或被拒绝"));
		return true;
	}

	/**
	 * 读一格方块的真实 id。
	 *
	 * <p>{@code data get block} 只对带方块实体的方块有效（箱子、告示牌…），普通方块一律回
	 * "The target block is not a block entity"。要确认某个摊位位置上到底摆着什么，
	 * 只能从服务端直接读 {@code BlockData}。</p>
	 */
	private boolean probeBlock(CommandSender sender, String[] args) {
		if (args.length < 4) {
			sender.sendMessage("用法：/probeblock <世界> <x> <y> <z>");
			return true;
		}
		org.bukkit.World world = Bukkit.getWorld(args[0]);
		if (world == null) {
			sender.sendMessage("世界没加载：" + args[0]);
			return true;
		}
		int x = Integer.parseInt(args[1]);
		int y = Integer.parseInt(args[2]);
		int z = Integer.parseInt(args[3]);
		org.bukkit.block.Block block = world.getBlockAt(x, y, z);
		sender.sendMessage(x + " " + y + " " + z + " = " + block.getBlockData().getAsString());
		return true;
	}
}
