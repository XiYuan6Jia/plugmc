package com.xiyuan.plugmc.train;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * {@code /train}：站点与路线的设置、以及让列车开起来。
 *
 * <p>站点是「站在铁轨上敲一下就记下来」——不占用方块，也不用摆特殊装置。
 * 路线用名字引用站点，列车按顺序跑。</p>
 */
public final class TrainCommand implements CommandExecutor, TabCompleter {
	private static final List<String> SUBCOMMANDS = List.of(
		"station", "route", "line", "start", "stop", "link", "unlink", "info", "reload");
	private static final List<String> STATION_SUBCOMMANDS = List.of("create", "remove", "list", "dwell", "here");
	private static final List<String> ROUTE_SUBCOMMANDS = List.of("create", "remove", "list", "add", "info", "loop");
	private static final List<String> LINE_SUBCOMMANDS = List.of(
		"create", "rename", "remove", "list", "route", "spacing", "start", "stop", "info");
	private static final int REACH = 8;

	private final TrainPlugin plugin;

	public TrainCommand(TrainPlugin plugin) {
		this.plugin = plugin;
	}

	@Override
	public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
							 @NotNull String label, @NotNull String[] args) {
		if (args.length == 0) {
			sender.sendMessage(TrainText.component("&6铁道列车 &7—— /train station|route|start|stop|link|unlink|info"));
			return true;
		}
		switch (args[0].toLowerCase(Locale.ROOT)) {
			case "station" -> station(sender, args);
			case "route" -> route(sender, args);
			case "line" -> line(sender, args);
			case "start" -> start(sender, args);
			case "stop" -> stop(sender, args);
			case "link" -> link(sender, args);
			case "unlink" -> unlink(sender, args);
			case "info" -> info(sender);
			case "reload" -> reload(sender);
			default -> sender.sendMessage(TrainText.component("&c没有这个子命令。"));
		}
		return true;
	}

	/**
	 * {@code /train line ...}：给自己的列车起名、绑定路线、调间距、开关。
	 *
	 * <p>只有登记的人能改自己的车；管理员权限可以动别人的（运维需要），
	 * 但菜单里只列本人的，避免看到一屏别人的车。</p>
	 */
	private void line(CommandSender sender, String[] args) {
		if (args.length < 2) {
			sender.sendMessage(TrainText.component("&7用法：/train line create|rename|remove|list|route|spacing|start|stop|info"));
			return;
		}
		switch (args[1].toLowerCase(Locale.ROOT)) {
			case "create" -> createLine(sender, args);
			case "rename" -> renameLine(sender, args);
			case "remove" -> removeLine(sender, args);
			case "list" -> listLines(sender);
			case "route" -> setLineRoute(sender, args);
			case "spacing" -> setLineSpacing(sender, args);
			case "start" -> startLine(sender, args);
			case "stop" -> stopLine(sender, args);
			case "info" -> lineInfo(sender, args);
			default -> sender.sendMessage(TrainText.component("&7用法：/train line create|rename|remove|list|route|spacing|start|stop|info"));
		}
	}

	private void createLine(CommandSender sender, String[] args) {
		if (!(sender instanceof Player player)) {
			sender.sendMessage(TrainText.component("&c请玩家站在列车旁边执行。"));
			return;
		}
		if (args.length < 3) {
			player.sendMessage(TrainText.component("&7用法：/train line create <列车名>（先站到列车的某节车厢旁边）"));
			return;
		}
		String name = args[2];
		if (name.length() > 16) {
			player.sendMessage(TrainText.component("&c列车名最多 16 个字。"));
			return;
		}
		if (plugin.lines().exists(name)) {
			player.sendMessage(TrainText.component("&c已经有一条叫「" + name + "」的列车了，换个名字。"));
			return;
		}
		Minecart cart = nearestCart(player);
		if (cart == null) {
			player.sendMessage(TrainText.component("&c附近没有矿车。"));
			return;
		}
		List<UUID> consist = plugin.trains().consistOf(cart.getUniqueId());
		if (consist.size() < 2) {
			player.sendMessage(TrainText.component("&c这节车厢还没连成列车 —— 先用拴绳把几台矿车连起来。"));
			return;
		}
		Entity headEntity = Bukkit.getEntity(plugin.trains().headOf(cart.getUniqueId()));
		if (!(headEntity instanceof org.bukkit.entity.minecart.PoweredMinecart)) {
			player.sendMessage(TrainText.component("&c这列车两端的动力矿车不对 —— 车头必须是动力矿车，请把动力矿车连在一端。"));
			return;
		}
		plugin.lines().put(new TrainLines.TrainLine(name, player.getUniqueId(), player.getName(),
			cart.getUniqueId(), "", 0.0));
		plugin.lines().save();
		player.sendMessage(TrainText.component("&a已创建列车 &f" + name + " &7（" + consist.size()
			+ " 节车厢）。用 &f/train line route " + name + " <路线名> &7绑定路线。"));
	}

	private void renameLine(CommandSender sender, String[] args) {
		if (args.length < 4) {
			sender.sendMessage(TrainText.component("&7用法：/train line rename <旧名> <新名>"));
			return;
		}
		TrainLines.TrainLine line = ownedLine(sender, args[2]);
		if (line == null) {
			return;
		}
		String name = args[3];
		if (name.length() > 16) {
			sender.sendMessage(TrainText.component("&c列车名最多 16 个字。"));
			return;
		}
		if (plugin.lines().exists(name)) {
			sender.sendMessage(TrainText.component("&c已经有一条叫「" + name + "」的列车了。"));
			return;
		}
		plugin.lines().rename(line, new TrainLines.TrainLine(name, line.ownerId(), line.ownerName(),
			line.cart(), line.routeName(), line.spacing()));
		plugin.lines().save();
		sender.sendMessage(TrainText.component("&a列车已改名为 &f" + name));
	}

	private void removeLine(CommandSender sender, String[] args) {
		if (args.length < 3) {
			sender.sendMessage(TrainText.component("&7用法：/train line remove <列车名>"));
			return;
		}
		TrainLines.TrainLine line = ownedLine(sender, args[2]);
		if (line == null) {
			return;
		}
		plugin.driver().stopLine(line.name());
		plugin.lines().remove(line.name());
		plugin.lines().save();
		sender.sendMessage(TrainText.component("&7已删除列车 &f" + line.name() + " &7（矿车本身没动）"));
	}

	private void listLines(CommandSender sender) {
		if (!(sender instanceof Player player)) {
			List<TrainLines.TrainLine> all = plugin.lines().all();
			sender.sendMessage(TrainText.component("&6全部列车（" + all.size() + "）"));
			for (TrainLines.TrainLine line : all) {
				sender.sendMessage(TrainText.component(" &f" + line.name() + " &7— " + line.ownerName()
					+ "，" + (line.routeName().isBlank() ? "&8未绑路线" : "&7" + line.routeName())));
			}
			return;
		}
		List<TrainLines.TrainLine> mine = plugin.lines().of(player.getUniqueId());
		if (mine.isEmpty()) {
			player.sendMessage(TrainText.component("&7你还没有命名过列车。站到列车旁边用 &f/train line create <名字>"));
			return;
		}
		player.sendMessage(TrainText.component("&6我的列车（" + mine.size() + "）"));
		for (TrainLines.TrainLine line : mine) {
			player.sendMessage(TrainText.component(" &f" + line.name()
				+ " &7" + (line.routeName().isBlank() ? "&8未绑路线" : "路线 " + line.routeName())
				+ " &7间距 " + (line.spacing() > 0 ? String.format(Locale.ROOT, "%.2f", line.spacing()) : "默认")
				+ (plugin.driver().isRunning(line.name()) ? " &a行驶中" : " &8已停")));
		}
	}

	private void setLineRoute(CommandSender sender, String[] args) {
		if (args.length < 3) {
			sender.sendMessage(TrainText.component("&7用法：/train line route <列车名> [路线名]（不给路线名就是解绑）"));
			return;
		}
		TrainLines.TrainLine line = ownedLine(sender, args[2]);
		if (line == null) {
			return;
		}
		if (args.length < 4) {
			plugin.lines().put(line.withRoute(""));
			plugin.lines().save();
			sender.sendMessage(TrainText.component("&7已解除 &f" + line.name() + " &7的路线绑定。"));
			return;
		}
		if (plugin.routes().get(args[3]) == null) {
			sender.sendMessage(TrainText.component("&c没有叫「" + args[3] + "」的路线。"));
			return;
		}
		plugin.lines().put(line.withRoute(args[3]));
		plugin.lines().save();
		sender.sendMessage(TrainText.component("&a列车 &f" + line.name() + " &a已绑定路线 &f" + args[3]));
	}

	private void setLineSpacing(CommandSender sender, String[] args) {
		if (args.length < 4) {
			sender.sendMessage(TrainText.component("&7用法：/train line spacing <列车名> <0.5~1.0>"));
			return;
		}
		TrainLines.TrainLine line = ownedLine(sender, args[2]);
		if (line == null) {
			return;
		}
		double value;
		try {
			value = Double.parseDouble(args[3]);
		} catch (NumberFormatException bad) {
			sender.sendMessage(TrainText.component("&c间距要写成小数，比如 0.9"));
			return;
		}
		if (value < 0.5 || value > 1.0) {
			sender.sendMessage(TrainText.component("&c间距只能在 0.5 到 1.0 格之间 —— 太挤会叠在一起，太松就不像编组了。"));
			return;
		}
		plugin.lines().put(line.withSpacing(value));
		plugin.lines().save();
		sender.sendMessage(TrainText.component("&a列车 &f" + line.name() + " &a的车厢间距设为 &f"
			+ String.format(Locale.ROOT, "%.2f", value) + " &a格"));
	}

	private void startLine(CommandSender sender, String[] args) {
		if (args.length < 3) {
			sender.sendMessage(TrainText.component("&7用法：/train line start <列车名> [路线名]"));
			return;
		}
		TrainLines.TrainLine line = ownedLine(sender, args[2]);
		if (line == null) {
			return;
		}
		String route = args.length >= 4 ? args[3] : line.routeName();
		if (route == null || route.isBlank()) {
			sender.sendMessage(TrainText.component("&c这条列车还没绑路线。先 &f/train line route " + line.name() + " <路线名>"));
			return;
		}
		if (plugin.routes().get(route) == null) {
			sender.sendMessage(TrainText.component("&c没有叫「" + route + "」的路线。"));
			return;
		}
		String error = plugin.driver().start(line.cart(), route, sender instanceof Player p ? p : null, line);
		if (error != null) {
			sender.sendMessage(TrainText.component("&c" + error));
		}
	}

	private void stopLine(CommandSender sender, String[] args) {
		if (args.length < 3) {
			sender.sendMessage(TrainText.component("&7用法：/train line stop <列车名>"));
			return;
		}
		TrainLines.TrainLine line = ownedLine(sender, args[2]);
		if (line == null) {
			return;
		}
		sender.sendMessage(plugin.driver().stopLine(line.name())
			? TrainText.component("&7列车 &f" + line.name() + " &7已停止。")
			: TrainText.component("&7这条列车本来就没在跑。"));
	}

	private void lineInfo(CommandSender sender, String[] args) {
		if (args.length < 3) {
			sender.sendMessage(TrainText.component("&7用法：/train line info <列车名>"));
			return;
		}
		TrainLines.TrainLine line = ownedLine(sender, args[2]);
		if (line == null) {
			return;
		}
		List<UUID> consist = plugin.trains().consistOf(line.cart());
		sender.sendMessage(TrainText.component("&6列车 &f" + line.name()));
		sender.sendMessage(TrainText.component(" &7车主 &f" + line.ownerName()
			+ " &7车厢 &f" + consist.size()));
		sender.sendMessage(TrainText.component(" &7路线 &f"
			+ (line.routeName().isBlank() ? "&8未绑" : line.routeName())
			+ " &7间距 &f" + (line.spacing() > 0 ? String.format(Locale.ROOT, "%.2f", line.spacing()) : "默认")
			+ " &7状态 &f" + (plugin.driver().isRunning(line.name()) ? "&a行驶中" : "已停")));
	}

	/**
	 * 取出这条列车并确认这个人有权动它。
	 *
	 * <p>「只能改自己的车」是需求里明确写的。管理员（{@code train.admin}）例外 ——
	 * 运维要能清理玩家留下的车，但不能靠这个去看别人的配置，所以菜单里也只列本人的。</p>
	 *
	 * @return 有权操作时返回那条列车，否则返回 null 并已发好提示
	 */
	private TrainLines.TrainLine ownedLine(CommandSender sender, String name) {
		TrainLines.TrainLine line = plugin.lines().get(name);
		if (line == null) {
			sender.sendMessage(TrainText.component("&c没有叫「" + name + "」的列车。"));
			return null;
		}
		if (sender instanceof Player player) {
			if (!line.ownerId().equals(player.getUniqueId()) && !player.hasPermission("train.admin")) {
				sender.sendMessage(TrainText.component("&c这不是你的列车。"));
				return null;
			}
			return line;
		}
		sender.sendMessage(TrainText.component("&7控制台可以操作任意列车。"));
		return line;
	}

	private void station(CommandSender sender, String[] args) {
		if (args.length < 2) {
			sender.sendMessage(TrainText.component("&7用法：/train station create|remove|list|dwell|here"));
			return;
		}
		String action = args[1].toLowerCase(Locale.ROOT);
		switch (action) {
			case "create", "here" -> createStation(sender, args);
			case "remove" -> removeStation(sender, args);
			case "list" -> listStations(sender);
			case "dwell" -> setDwell(sender, args);
			default -> sender.sendMessage(TrainText.component("&7用法：/train station create|remove|list|dwell"));
		}
	}

	private void createStation(CommandSender sender, String[] args) {
		if (!(sender instanceof Player player)) {
			if (args.length < 7) {
				sender.sendMessage(TrainText.component("&c控制台用法：/train station create <名字> <世界> <x> <y> <z>"));
				return;
			}
			World world = Bukkit.getWorld(args[3]);
			if (world == null) {
				sender.sendMessage(TrainText.component("&c找不到世界 " + args[3]));
				return;
			}
			Block block = world.getBlockAt(Integer.parseInt(args[4]), Integer.parseInt(args[5]), Integer.parseInt(args[6]));
			registerStation(sender, args[2], block);
			return;
		}
		if (args.length < 3) {
			player.sendMessage(TrainText.component("&7站在铁轨上输入：/train station create <名字>"));
			return;
		}
		Block target = player.getTargetBlockExact(REACH);
		if (target == null) {
			Location at = player.getLocation().subtract(0, 1, 0);
			target = at.getBlock();
		}
		registerStation(player, args[2], target);
	}

	private void registerStation(CommandSender sender, String name, Block block) {
		if (!RailPath.isRail(block.getType())) {
			sender.sendMessage(TrainText.component("&c这里不是铁轨，站点必须建在铁轨上。"));
			return;
		}
		TrainStations.TrainStation station = new TrainStations.TrainStation(
			name, block.getWorld().getUID(), block.getX(), block.getY(), block.getZ(),
			plugin.settings().defaultDwellSeconds());
		plugin.stations().put(station);
		plugin.stations().save();
		sender.sendMessage(TrainText.component("&a已记录站点 &f" + name + " &7("
			+ block.getX() + ", " + block.getY() + ", " + block.getZ() + ")，默认停靠 "
			+ station.dwellSeconds() + " 秒。"));
	}

	private void removeStation(CommandSender sender, String[] args) {
		if (args.length < 3) {
			sender.sendMessage(TrainText.component("&7用法：/train station remove <名字>"));
			return;
		}
		TrainStations.TrainStation removed = plugin.stations().remove(args[2]);
		if (removed == null) {
			sender.sendMessage(TrainText.component("&c没有叫「" + args[2] + "」的站点。"));
			return;
		}
		plugin.stations().save();
		sender.sendMessage(TrainText.component("&7已删除站点 &f" + removed.name()));
	}

	private void listStations(CommandSender sender) {
		List<TrainStations.TrainStation> all = plugin.stations().all();
		if (all.isEmpty()) {
			sender.sendMessage(TrainText.component("&7还没有任何站点。"));
			return;
		}
		sender.sendMessage(TrainText.component("&6站点（" + all.size() + "）"));
		for (TrainStations.TrainStation station : all) {
			String flag = station.usable() ? "&a✔" : "&c✘";
			sender.sendMessage(TrainText.component(" " + flag + " &f" + station.name() + " &7("
				+ station.x() + ", " + station.y() + ", " + station.z() + ") 停靠 " + station.dwellSeconds() + " 秒"));
		}
	}

	private void setDwell(CommandSender sender, String[] args) {
		if (args.length < 4) {
			sender.sendMessage(TrainText.component("&7用法：/train station dwell <站点> <秒>"));
			return;
		}
		TrainStations.TrainStation station = plugin.stations().get(args[2]);
		if (station == null) {
			sender.sendMessage(TrainText.component("&c没有叫「" + args[2] + "」的站点。"));
			return;
		}
		int seconds;
		try {
			seconds = Integer.parseInt(args[3]);
		} catch (NumberFormatException ex) {
			sender.sendMessage(TrainText.component("&c秒数要写数字。"));
			return;
		}
		TrainStations.TrainStation updated = new TrainStations.TrainStation(
			station.name(), station.worldId(), station.x(), station.y(), station.z(), Math.max(1, seconds));
		plugin.stations().put(updated);
		plugin.stations().save();
		sender.sendMessage(TrainText.component("&a站点 &f" + station.name() + " &7停靠时间改为 "
			+ updated.dwellSeconds() + " 秒。"));
	}

	private void route(CommandSender sender, String[] args) {
		if (args.length < 2) {
			sender.sendMessage(TrainText.component("&7用法：/train route create|remove|list|add|info"));
			return;
		}
		switch (args[1].toLowerCase(Locale.ROOT)) {
			case "create" -> createRoute(sender, args);
			case "remove" -> removeRoute(sender, args);
			case "list" -> listRoutes(sender);
			case "add" -> addStationToRoute(sender, args);
			case "info" -> routeInfo(sender, args);
			case "loop" -> loopRoute(sender, args);
			default -> sender.sendMessage(TrainText.component("&7用法：/train route create|remove|list|add|info|loop"));
		}
	}

	private void createRoute(CommandSender sender, String[] args) {
		if (args.length < 4) {
			sender.sendMessage(TrainText.component("&7用法：/train route create <路线名> <站点1,站点2,...>"));
			return;
		}
		List<String> stations = new ArrayList<>();
		for (String part : args[3].split(",")) {
			String name = part.trim();
			if (name.isEmpty()) {
				continue;
			}
			if (plugin.stations().get(name) == null) {
				sender.sendMessage(TrainText.component("&c站点「" + name + "」不存在，先 /train station create。"));
				return;
			}
			stations.add(name);
		}
		if (stations.size() < 2) {
			sender.sendMessage(TrainText.component("&c路线至少要两个站点。"));
			return;
		}
		plugin.routes().put(new TrainRoutes.TrainRoute(args[2], List.copyOf(stations), parseLoop(args, 4), List.of()));
		plugin.routes().save();
		sender.sendMessage(TrainText.component("&a已创建路线 &f" + args[2] + "&7："
			+ String.join(" → ", stations) + (parseLoop(args, 4) ? "（循环）" : "（往返，终点掉头）")));
	}

	/**
	 * 取第 index 个参数当「要不要循环」，缺省是循环。
	 * 往返路线要求列车两端各有一个动力矿车，掉头时靠 {@code flipHead} 换车头。
	 */
	private static boolean parseLoop(String[] args, int index) {
		if (args.length <= index) {
			return true;
		}
		String flag = args[index].trim();
		return !("false".equalsIgnoreCase(flag) || "no".equalsIgnoreCase(flag)
			|| "往返".equals(flag) || "0".equals(flag));
	}

	private void loopRoute(CommandSender sender, String[] args) {
		if (args.length < 4) {
			sender.sendMessage(TrainText.component("&7用法：/train route loop <路线名> <true|false>"));
			return;
		}
		TrainRoutes.TrainRoute route = plugin.routes().get(args[2]);
		if (route == null) {
			sender.sendMessage(TrainText.component("&c没有叫「" + args[2] + "」的路线。"));
			return;
		}
		boolean loop = parseLoop(args, 3);
		plugin.routes().put(new TrainRoutes.TrainRoute(route.name(), route.stations(), loop, route.dwellSeconds()));
		plugin.routes().save();
		sender.sendMessage(TrainText.component("&a路线 &f" + route.name() + " &a已设为 &f"
			+ (loop ? "循环" : "往返（终点掉头）")));
	}

	private void removeRoute(CommandSender sender, String[] args) {
		if (args.length < 3) {
			sender.sendMessage(TrainText.component("&7用法：/train route remove <路线名>"));
			return;
		}
		TrainRoutes.TrainRoute removed = plugin.routes().remove(args[2]);
		if (removed == null) {
			sender.sendMessage(TrainText.component("&c没有叫「" + args[2] + "」的路线。"));
			return;
		}
		plugin.routes().save();
		sender.sendMessage(TrainText.component("&7已删除路线 &f" + removed.name()));
	}

	private void listRoutes(CommandSender sender) {
		List<TrainRoutes.TrainRoute> all = plugin.routes().all();
		if (all.isEmpty()) {
			sender.sendMessage(TrainText.component("&7还没有任何路线。"));
			return;
		}
		sender.sendMessage(TrainText.component("&6路线（" + all.size() + "）"));
		for (TrainRoutes.TrainRoute route : all) {
			sender.sendMessage(TrainText.component(" &f" + route.name() + " &7(" + route.stations().size()
				+ " 站" + (route.loop() ? "，循环" : "，往返") + ")：" + String.join(" → ", route.stations())));
		}
	}

	private void addStationToRoute(CommandSender sender, String[] args) {
		if (args.length < 4) {
			sender.sendMessage(TrainText.component("&7用法：/train route add <路线名> <站点>"));
			return;
		}
		TrainRoutes.TrainRoute route = plugin.routes().get(args[2]);
		if (route == null) {
			sender.sendMessage(TrainText.component("&c没有叫「" + args[2] + "」的路线。"));
			return;
		}
		if (plugin.stations().get(args[3]) == null) {
			sender.sendMessage(TrainText.component("&c站点「" + args[3] + "」不存在。"));
			return;
		}
		List<String> stations = new ArrayList<>(route.stations());
		stations.add(args[3]);
		plugin.routes().put(new TrainRoutes.TrainRoute(route.name(), List.copyOf(stations),
			route.loop(), route.dwellSeconds()));
		plugin.routes().save();
		sender.sendMessage(TrainText.component("&a已把 &f" + args[3] + " &a加到路线 &f" + route.name()));
	}

	private void routeInfo(CommandSender sender, String[] args) {
		if (args.length < 3) {
			sender.sendMessage(TrainText.component("&7用法：/train route info <路线名>"));
			return;
		}
		TrainRoutes.TrainRoute route = plugin.routes().get(args[2]);
		if (route == null) {
			sender.sendMessage(TrainText.component("&c没有叫「" + args[2] + "」的路线。"));
			return;
		}
		sender.sendMessage(TrainText.component("&6路线 &f" + route.name() + " &7("
			+ (route.loop() ? "循环" : "往返") + ")"));
		int index = 1;
		for (String name : route.stations()) {
			TrainStations.TrainStation station = plugin.stations().get(name);
			String detail = station == null ? "&c站点已丢失"
				: (station.usable() ? "&a✔ " + station.x() + ", " + station.y() + ", " + station.z()
				: "&c✘ 铁轨不在了");
			sender.sendMessage(TrainText.component(" " + index++ + ". &f" + name + " &7" + detail));
		}
	}

	private void start(CommandSender sender, String[] args) {
		if (args.length < 2) {
			sender.sendMessage(TrainText.component("&7用法：/train start <路线名>（先站到列车的某节车厢旁边）"));
			return;
		}
		if (!(sender instanceof Player player)) {
			sender.sendMessage(TrainText.component("&c请玩家在列车旁边执行。"));
			return;
		}
		Minecart cart = nearestCart(player);
		if (cart == null) {
			player.sendMessage(TrainText.component("&c附近没有矿车。"));
			return;
		}
		String error = plugin.driver().start(cart.getUniqueId(), args[1], player);
		if (error != null) {
			player.sendMessage(TrainText.component("&c" + error));
		}
	}

	private void stop(CommandSender sender, String[] args) {
		// /train stop <矿车 UUID>：控制台或运维用，直接按编组停
		if (args.length >= 2) {
			try {
				UUID id = UUID.fromString(args[1]);
				plugin.driver().stop(id, sender instanceof Player p ? p : null);
				sender.sendMessage(TrainText.component("&7已按 " + args[1] + " 所在的列车发出停止指令。"));
			} catch (IllegalArgumentException bad) {
				sender.sendMessage(TrainText.component("&cUUID 写错了：" + args[1]));
			}
			return;
		}
		Player player = sender instanceof Player p ? p : null;
		Minecart cart = player == null ? null : nearestCart(player);
		if (cart != null) {
			// 站在车旁边就直接停这一列
			plugin.driver().stop(cart.getUniqueId(), player);
			return;
		}
		// 身边没有矿车：列车早跑远了。这时按「全部停下」处理，
		// 否则玩家看着车在跑却得到一句「附近没有矿车」，完全无从下手。
		plugin.driver().stopAll(player);
		if (player == null) {
			sender.sendMessage(TrainText.component("&7已停止全部自动行驶的列车。"));
		}
	}

	private void link(CommandSender sender, String[] args) {
		sender.sendMessage(TrainText.component("&7连接方式：手持拴绳 shift 右键第一台矿车，再 shift 右键第二台。"));
	}

	private void unlink(CommandSender sender, String[] args) {
		sender.sendMessage(TrainText.component("&7解开方式：手持拴绳 shift 右键已连接的那节车厢。"));
	}

	private void info(CommandSender sender) {
		sender.sendMessage(TrainText.component("&6铁道列车"));
		sender.sendMessage(TrainText.component(" &7站点 &f" + plugin.stations().size()
			+ " &7路线 &f" + plugin.routes().size() + " &7列车 &f" + plugin.lines().size()
			+ " &7行驶中 &f" + plugin.driver().count()));
		sender.sendMessage(TrainText.component(" &7车厢上限 &f" + plugin.settings().maxCars()
			+ " &7巡航速度 &f" + plugin.settings().travelSpeed()
			+ " &7进站速度 &f" + plugin.settings().approachSpeed()));
	}

	private void reload(CommandSender sender) {
		plugin.reloadSettings();
		plugin.stations().load();
		plugin.routes().load();
		plugin.lines().load();
		sender.sendMessage(TrainText.component("&a配置与站点、路线、列车已重新载入。"));
	}

	private static Minecart nearestCart(Player player) {
		Minecart best = null;
		double bestDistance = 36.0;
		for (Entity entity : player.getNearbyEntities(6, 6, 6)) {
			if (!(entity instanceof Minecart cart)) {
				continue;
			}
			double distance = entity.getLocation().distanceSquared(player.getLocation());
			if (distance < bestDistance) {
				bestDistance = distance;
				best = cart;
			}
		}
		return best;
	}

	@Override
	public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
									  @NotNull String alias, @NotNull String[] args) {
		if (args.length == 1) {
			return filter(SUBCOMMANDS, args[0]);
		}
		if (args.length == 2) {
			return switch (args[0].toLowerCase(Locale.ROOT)) {
				case "station" -> filter(STATION_SUBCOMMANDS, args[1]);
				case "route" -> filter(ROUTE_SUBCOMMANDS, args[1]);
				case "line" -> filter(LINE_SUBCOMMANDS, args[1]);
				case "start" -> filter(plugin.routes().names(), args[1]);
				default -> List.of();
			};
		}
		if (args.length == 3) {
			return switch (args[0].toLowerCase(Locale.ROOT)) {
				case "station" -> filter(plugin.stations().names(), args[2]);
				case "route" -> args[1].equalsIgnoreCase("create") ? List.of() : filter(plugin.routes().names(), args[2]);
				case "line" -> filter(lineNames(sender), args[2]);
				default -> List.of();
			};
		}
		if (args.length == 4 && args[0].equalsIgnoreCase("line")) {
			return switch (args[1].toLowerCase(Locale.ROOT)) {
				case "route", "start" -> filter(plugin.routes().names(), args[3]);
				case "spacing" -> List.of("0.5", "0.7", "0.9", "1.0");
				default -> List.of();
			};
		}
		return List.of();
	}

	/** 补全时只列本人的列车（管理员多列全部，便于运维）。 */
	private List<String> lineNames(CommandSender sender) {
		List<TrainLines.TrainLine> pool = sender instanceof Player player && !player.hasPermission("train.admin")
			? plugin.lines().of(player.getUniqueId())
			: plugin.lines().all();
		List<String> names = new ArrayList<>();
		for (TrainLines.TrainLine line : pool) {
			names.add(line.name());
		}
		return names;
	}

	private static List<String> filter(List<String> options, String prefix) {
		String lower = prefix.toLowerCase(Locale.ROOT);
		List<String> result = new ArrayList<>();
		for (String option : options) {
			if (option.toLowerCase(Locale.ROOT).startsWith(lower)) {
				result.add(option);
			}
		}
		return result;
	}
}
