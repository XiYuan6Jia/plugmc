package com.xiyuan.plugmc.probe;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryView;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Arrays;
import java.util.Locale;

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
	private static final String[] COMMANDS =
		{"asplayer", "probeblock", "asclick", "openinv", "astp", "asheal", "invclick"};

	@Override
	public void onEnable() {
		for (String name : COMMANDS) {
			if (getCommand(name) != null) {
				getCommand(name).setExecutor(this);
			}
		}
		getLogger().info("探针已启用：" + String.join("、", Arrays.stream(COMMANDS)
			.map(n -> "/" + n).toList()));
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		return switch (command.getName().toLowerCase(Locale.ROOT)) {
			case "probeblock" -> probeBlock(sender, args);
			case "asclick" -> asClick(sender, args);
			case "openinv" -> openInv(sender, args);
			case "astp" -> asTeleport(sender, args);
			case "asheal" -> asHeal(sender, args);
			case "invclick" -> invClick(sender, args);
			default -> asPlayer(sender, args);
		};
	}

	private boolean asPlayer(CommandSender sender, String[] args) {
		if (args.length < 2) {
			sender.sendMessage("用法：/asplayer <玩家> <命令...>（命令不带开头的斜杠）");
			return true;
		}
		Player target = Bukkit.getPlayerExact(args[0]);
		if (target == null) {
			sender.sendMessage("玩家不在线：" + args[0]);
			return true;
		}
		String line = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
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
		sender.sendMessage(args[0] + " " + x + " " + y + " " + z + " = "
			+ world.getBlockAt(x, y, z).getBlockData().getAsString());
		return true;
	}

	/**
	 * 合成一次"右键实体"，用来验证实体点击链路。
	 *
	 * <p>合成事件走的是完整的监听器注册链，效果和真点击一样；只有读鼠标位置、读手上物品
	 * 这类东西会和真点击有出入。</p>
	 */
	private boolean asClick(CommandSender sender, String[] args) {
		if (args.length < 1) {
			sender.sendMessage("用法：/asclick <玩家> [实体类型]");
			return true;
		}
		Player target = Bukkit.getPlayerExact(args[0]);
		if (target == null) {
			sender.sendMessage("玩家不在线：" + args[0]);
			return true;
		}
		Entity nearest = null;
		double best = Double.MAX_VALUE;
		for (Entity entity : target.getWorld().getNearbyEntities(target.getLocation(), 16, 16, 16)) {
			if (entity instanceof Player) {
				continue;
			}
			if (args.length >= 2 && !entity.getType().getKey().getKey().equalsIgnoreCase(args[1])) {
				continue;
			}
			double distance = entity.getLocation().distanceSquared(target.getLocation());
			if (distance < best) {
				best = distance;
				nearest = entity;
			}
		}
		if (nearest == null) {
			sender.sendMessage("16 格内没有实体：" + (args.length >= 2 ? args[1] : "任意"));
			return true;
		}
		PlayerInteractEntityEvent event =
			new PlayerInteractEntityEvent(target, nearest, EquipmentSlot.HAND);
		Bukkit.getPluginManager().callEvent(event);
		sender.sendMessage("已合成右键 " + nearest.getType().getKey().getKey()
			+ " @ " + String.format("%.1f %.1f %.1f",
				nearest.getLocation().getX(), nearest.getLocation().getY(), nearest.getLocation().getZ())
			+ (event.isCancelled() ? "（已被取消）" : "（未被取消）"));
		return true;
	}

	/**
	 * 把玩家直接传送到某个世界的坐标。
	 *
	 * <p>两件事不能省：① 不能借用 {@code /hub}（会被 HuskHomes 的 {@code /spawn} 别名抢走），
	 * 也不能用 {@code /execute in <dim> run tp}（26.3 上不换维度）；② RCON 线程不是主线程，
	 * 跨维度传送必须回主线程做，否则 {@link Player#teleport(org.bukkit.Location)} 直接返回 false。</p>
	 */
	private boolean asTeleport(CommandSender sender, String[] args) {
		if (args.length < 5) {
			sender.sendMessage("用法：/astp <玩家> <世界> <x> <y> <z>");
			return true;
		}
		Player target = Bukkit.getPlayerExact(args[0]);
		if (target == null) {
			sender.sendMessage("玩家不在线：" + args[0]);
			return true;
		}
		org.bukkit.World world = Bukkit.getWorld(args[1]);
		if (world == null) {
			sender.sendMessage("世界没加载：" + args[1]);
			return true;
		}
		double x = Double.parseDouble(args[2]);
		double y = Double.parseDouble(args[3]);
		double z = Double.parseDouble(args[4]);
		org.bukkit.Location destination = new org.bukkit.Location(world, x, y, z);
		Bukkit.getScheduler().runTask(this, () -> {
			boolean ok = target.teleport(destination);
			getLogger().info("[astp] " + target.getName() + " → " + world.getName()
				+ " " + x + " " + y + " " + z + (ok ? " 成功" : " 被拒绝")
				+ "（死亡=" + target.isDead() + " 血=" + target.getHealth() + "）");
		});
		sender.sendMessage("[" + target.getName() + "] 已提交传送 → " + world.getName()
			+ " " + x + " " + y + " " + z + "（结果看服务器日志）");
		return true;
	}

	/**
	 * 满血复活。
	 *
	 * <p>死亡状态下的玩家 {@link Player#teleport(org.bukkit.Location)} 一律返回 false ——
	 * 端到端验证之前必须先确认人没躺在死亡界面上，否则后面所有传送都会被静默拒绝，
	 * 表现出来只是"传送没生效"，很难联想到死亡。</p>
	 */
	private boolean asHeal(CommandSender sender, String[] args) {
		if (args.length < 1) {
			sender.sendMessage("用法：/asheal <玩家>");
			return true;
		}
		Player target = Bukkit.getPlayerExact(args[0]);
		if (target == null) {
			sender.sendMessage("玩家不在线：" + args[0]);
			return true;
		}
		Bukkit.getScheduler().runTask(this, () -> {
			if (target.isDead()) {
				target.spigot().respawn();
			}
			target.setHealth(target.getMaxHealth());
			target.setFoodLevel(20);
			target.setFireTicks(0);
			target.setFallDistance(0);
			getLogger().info("[asheal] " + target.getName() + " 死亡=" + target.isDead()
				+ " 血=" + target.getHealth());
		});
		sender.sendMessage("[" + target.getName() + "] 已提交满血复活");
		return true;
	}

	/** 打印玩家当前打开的界面标题，用来确认"右键之后真的开了界面"。 */
	private boolean openInv(CommandSender sender, String[] args) {
		if (args.length < 1) {
			sender.sendMessage("用法：/openinv <玩家>");
			return true;
		}
		Player target = Bukkit.getPlayerExact(args[0]);
		if (target == null) {
			sender.sendMessage("玩家不在线：" + args[0]);
			return true;
		}
		InventoryView view = target.getOpenInventory();
		String title = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
			.serialize(view.title());
		sender.sendMessage("[" + target.getName() + "] 当前界面 = " + title
			+ "｜类型 = " + view.getType()
			+ "｜格数 = " + view.getTopInventory().getSize());
		return true;
	}

	/**
	 * 在当前打开的界面里点一个格子。
	 *
	 * <p>合成 {@link org.bukkit.event.inventory.InventoryClickEvent} 走完整监听链，
	 * 用来验证"菜单按钮点下去真的会跳到下一个界面"。</p>
	 */
	private boolean invClick(CommandSender sender, String[] args) {
		if (args.length < 2) {
			sender.sendMessage("用法：/invclick <玩家> <槽位>");
			return true;
		}
		Player target = Bukkit.getPlayerExact(args[0]);
		if (target == null) {
			sender.sendMessage("玩家不在线：" + args[0]);
			return true;
		}
		int slot = Integer.parseInt(args[1]);
		InventoryView view = target.getOpenInventory();
		org.bukkit.inventory.Inventory top = view.getTopInventory();
		if (slot < 0 || slot >= top.getSize()) {
			sender.sendMessage("槽位超出范围（0.." + (top.getSize() - 1) + "）");
			return true;
		}
		org.bukkit.event.inventory.InventoryClickEvent event =
			new org.bukkit.event.inventory.InventoryClickEvent(
				view,
				org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER,
				slot,
				org.bukkit.event.inventory.ClickType.LEFT,
				org.bukkit.event.inventory.InventoryAction.PICKUP_ALL);
		Bukkit.getPluginManager().callEvent(event);
		sender.sendMessage("已合成点击槽位 " + slot + (event.isCancelled() ? "（已被取消）" : "（未被取消）"));
		return true;
	}
}
