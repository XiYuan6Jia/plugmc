package com.xiyuan.plugmc.train;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.PoweredMinecart;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 自动驾驶：让一列列车沿路线依次停靠各站。
 *
 * <p>动力来源跟普通矿车一样 —— 动力矿车烧煤。这里不凭空造速度，只是按路线把
 * 「该往哪走、该多快」算出来再赋给车头；没燃料时它就走不动，和手动推一个道理。</p>
 *
 * <p>减速策略：离站点还远时按巡航速度跑，进入刹停距离后按剩余距离线性降到慢速，
 * 到达停车半径内直接把速度清零并开始计停靠时间。这样车头会正好停在站点方块上。</p>
 */
public final class TrainDriver {
	private final TrainPlugin plugin;
	private final Map<UUID, Drive> drives = new HashMap<>();

	public TrainDriver(TrainPlugin plugin) {
		this.plugin = plugin;
	}

	/** 一条列车当前的行驶状态。 */
	public static final class Drive {
		public UUID head;
		public String routeName;
		/** 这列车在 {@link TrainLines} 里登记的名字；空串表示没命名（直接 /train start 起的）。 */
		public String lineName = "";
		public int index;
		public int direction = 1;
		public List<Vector> path = new ArrayList<>();
		public int cursor;
		public int dwellTicks;
		public boolean dwelling;
		/**
		 * 整列列车能占用的那条铁轨折线：前面是车头身后的一串铁轨中心，后面是本站的路径。
		 *
		 * <p>记方块中心而不是车头的原始坐标：落在方块中心的矿车才是「正常停在轨道上」
		 * 的状态，随便一个浮点位置会让它脱轨。</p>
		 */
		public List<Vector> rail = new ArrayList<>();
		/**
		 * 车头沿 {@link #path} 走过的弧长（格）。位置驱动，不靠物理速度。
		 *
		 * <p>为什么不给矿车设速度让它自己跑：26.3 的矿车用的是新的轨道运动模型，
		 * 外部 {@code setVelocity} 设进去的速度会被原版的轨道逻辑和摩擦很快吃掉 ——
		 * 实测给 0.34 格/tick，下一 tick 读回来的 Motion 只有 1e-8，车头原地不动。
		 * 改成每 tick 按弧长算出该在哪一格，直接摆过去，位置完全可控。</p>
		 */
		public double progress;
		public String lastMessage;

		public Drive(UUID head, String routeName) {
			this.head = head;
			this.routeName = routeName;
		}
	}

	public boolean isDriving(UUID any) {
		for (Drive drive : drives.values()) {
			if (plugin.trains().consistOf(any).contains(drive.head) || drive.head.equals(any)) {
				return true;
			}
		}
		return false;
	}

	public Drive driveOf(UUID head) {
		return drives.get(head);
	}

	public int count() {
		return drives.size();
	}

	public List<UUID> heads() {
		return List.copyOf(drives.keySet());
	}

	/**
	 * 让某节车厢所在的列车开始跑某条路线。
	 *
	 * @return 失败原因；成功返回 null
	 */
	public String start(UUID any, String routeName, Player starter) {
		return start(any, routeName, starter, null);
	}

	/**
	 * 让某节车厢所在的列车按「命名列车」的设定开始跑。
	 *
	 * <p>与上面那个重载的差别只有两点：把这列记在 {@code Drive.lineName} 上（停站消息、
	 * 菜单里都要显示它），以及沿用这条列车自己配的路线 —— 菜单里点「出发」时不必再传路线。</p>
	 *
	 * @param line 命名列车；传 null 就是一次性的临时列车
	 * @return 失败原因；成功返回 null
	 */
	public String start(UUID any, String routeName, Player starter, TrainLines.TrainLine line) {
		String wanted = line != null && !line.routeName().isBlank() ? line.routeName() : routeName;
		TrainRoutes.TrainRoute route = plugin.routes().get(wanted);
		if (route == null) {
			return "没有叫「" + wanted + "」的路线。";
		}
		if (route.stations().size() < 2) {
			return "这条路线至少要两个站点才能跑。";
		}
		UUID head = plugin.trains().headOf(any);
		Entity entity = Bukkit.getEntity(head);
		if (!(entity instanceof PoweredMinecart)) {
			return "车头必须是动力矿车 —— 请把动力矿车连在列车的一端。";
		}
		Drive drive = new Drive(head, route.name());
		drive.lineName = line == null ? "" : line.name();
		drive.index = 0;
		drive.direction = 1;
		if (!rebuildPath(drive)) {
			return "找不到通往「" + route.stations().get(1 % route.stations().size()) + "」的铁轨，检查一下轨道是否连通。";
		}
		drives.put(head, drive);
		if (starter != null) {
			starter.sendMessage(TrainText.component("&a列车已出发，路线：&f" + route.name()
				+ (drive.lineName.isBlank() ? "" : " &7（" + drive.lineName + "）")));
		}
		return null;
	}

	public void stop(UUID any, Player stopper) {
		// 不能只用 headOf(any) 去删：双向列车跑到一半时车头已经换到另一端，
		// 这时点的是原来那一节，headOf 给出的仍是当前车头（可能正好对，也可能不对）。
		// 直接沿着整列车找「哪一节正被某个 Drive 当车头」，找到就停。
		List<UUID> line = plugin.trains().consistOf(any);
		UUID key = null;
		for (UUID id : line) {
			if (drives.containsKey(id)) {
				key = id;
				break;
			}
		}
		if (key == null && drives.containsKey(any)) {
			key = any;
		}
		Drive removed = key == null ? null : drives.remove(key);
		if (removed != null) {
			Entity entity = Bukkit.getEntity(key);
			if (entity instanceof Minecart cart) {
				cart.setVelocity(new Vector());
			}
			if (stopper != null) {
				stopper.sendMessage(TrainText.component("&7列车已停止自动驾驶。"));
			}
		} else if (stopper != null) {
			stopper.sendMessage(TrainText.component("&7这列车本来就没在自动行驶。"));
		}
	}

	/**
	 * 停掉所有正在自动行驶的列车。
	 *
	 * <p>为什么要这个：{@code /train stop} 原来只找玩家身边 6 格内的矿车，
	 * 但列车一跑起来就不在玩家旁边了，于是永远回「附近没有矿车」——
	 * 玩家看着车在跑却停不下来。</p>
	 *
	 * @return 停掉的列车数量
	 */
	public int stopAll(Player stopper) {
		int count = 0;
		for (Map.Entry<UUID, Drive> entry : new HashMap<>(drives).entrySet()) {
			Drive removed = drives.remove(entry.getKey());
			if (removed == null) {
				continue;
			}
			Entity entity = Bukkit.getEntity(entry.getKey());
			if (entity instanceof Minecart cart) {
				cart.setVelocity(new Vector());
			}
			count++;
		}
		if (stopper != null) {
			stopper.sendMessage(count > 0
				? TrainText.component("&7已停止 " + count + " 列自动行驶的列车。")
				: TrainText.component("&7现在没有列车在自动行驶。"));
		}
		return count;
	}

	/** 正在自动行驶的列车数量。 */
	public int runningCount() {
		return drives.size();
	}

	/**
	 * 这条命名列车现在是不是在跑。
	 *
	 * <p>按名字找而不是按 UUID：双向列车掉头会把 Drive 记在另一端的动力矿车上，
	 * 用「哪节车厢」去找会漏。</p>
	 */
	public boolean isRunning(String lineName) {
		if (lineName == null || lineName.isBlank()) {
			return false;
		}
		for (Drive drive : drives.values()) {
			if (lineName.equalsIgnoreCase(drive.lineName)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 让一条命名列车停下来。
	 *
	 * @return 是否真的停了一列
	 */
	public boolean stopLine(String lineName) {
		if (lineName == null || lineName.isBlank()) {
			return false;
		}
		for (Map.Entry<UUID, Drive> entry : new HashMap<>(drives).entrySet()) {
			if (!lineName.equalsIgnoreCase(entry.getValue().lineName)) {
				continue;
			}
			if (drives.remove(entry.getKey()) == null) {
				continue;
			}
			Entity entity = Bukkit.getEntity(entry.getKey());
			if (entity instanceof Minecart cart) {
				cart.setVelocity(new Vector());
			}
			return true;
		}
		return false;
	}

	/** 主循环：由插件按配置间隔调用。 */
	public void tick() {
		if (drives.isEmpty()) {
			return;
		}
		for (Drive drive : new ArrayList<>(drives.values())) {
			step(drive);
		}
	}

	private void step(Drive drive) {
		Entity headEntity = Bukkit.getEntity(drive.head);
		if (!(headEntity instanceof Minecart headCart)) {
			plugin.getLogger().info("自动驾驶结束：车头矿车已经不在了（" + drive.head + "）");
			drives.remove(drive.head);
			return;
		}
		World world = headCart.getWorld();
		TrainRoutes.TrainRoute route = plugin.routes().get(drive.routeName);
		if (route == null) {
			plugin.getLogger().info("自动驾驶结束：路线「" + drive.routeName + "」已被删除");
			drives.remove(drive.head);
			return;
		}
		List<String> stations = route.stations();

		if (drive.dwelling) {
			headCart.setVelocity(new Vector());
			drive.dwellTicks--;
			if (drive.dwellTicks > 0) {
				return;
			}
			drive.dwelling = false;
			if (!advanceStation(drive, route)) {
				plugin.getLogger().info("自动驾驶结束：路线「" + drive.routeName + "」已跑完（停顿在 " + drive.index
					+ " 号站，方向 " + drive.direction + "）");
				drives.remove(drive.head);
				notifyNearest(headCart, "&7路线跑完了，列车停在终点站。");
				return;
			}
			if (!rebuildPath(drive)) {
				plugin.getLogger().info("自动驾驶结束：从车头位置找不到通往 " + stations.get(drive.index) + " 的铁轨（车头在 "
					+ headCart.getLocation().getBlockX() + "," + headCart.getLocation().getBlockY()
					+ "," + headCart.getLocation().getBlockZ() + "）");
				drives.remove(drive.head);
				notifyNearest(headCart, "&c找不到下一段铁轨，自动驾驶已停止。");
				return;
			}
		}

		if (drive.path.isEmpty()) {
			plugin.getLogger().info("自动驾驶结束：车头到目标站之间没有可用铁轨（" + drive.head + "）");
			drives.remove(drive.head);
			return;
		}

		// 燃料：跟普通动力矿车完全一样，由原版自己在跑动时烧煤，这里只负责「没燃料就不开」。
		// 早先这里还会每秒再扣一点，结果和原版叠加成双倍消耗，一块煤跑不到应有的一半距离。
		//
		// 例外：车头正压在通电的动力铁轨上时，原版会持续给它加速，这时燃料耗尽也能走 ——
		// 和手动开矿车完全一致，所以这种情况不拦。
		if (headCart instanceof PoweredMinecart powered && powered.getFuel() <= 0 && !onPoweredRail(headCart)) {
			headCart.setVelocity(new Vector());
			if (!"fuel".equals(drive.lastMessage)) {
				drive.lastMessage = "fuel";
				notifyNearest(headCart, "&c动力矿车没燃料了，加点煤炭或铺一段通电的动力铁轨吧。");
			}
			return;
		}
		drive.lastMessage = null;

		TrainConfig config = plugin.settings();
		double total = pathLength(drive.path);
		double remaining = Math.max(0.0, total - drive.progress);

		if (remaining <= config.stopRadius()) {
			headCart.setVelocity(new Vector());
			drive.dwelling = true;
			TrainStations.TrainStation station = plugin.stations().get(stations.get(drive.index));
			int dwell = route.dwellAt(drive.index);
			if (dwell <= 0) {
				dwell = station == null ? config.defaultDwellSeconds() : station.dwellSeconds();
			}
			drive.dwellTicks = Math.max(1, dwell) * 20;
			notifyNearest(headCart, "&a到站：&f" + (station == null ? stations.get(drive.index) : station.name())
				+ " &7停靠 " + Math.max(1, dwell) + " 秒");
			return;
		}

		// 速度按「离站还剩多少」定：远处巡航，进刹停距离后线性降到进站速度
		double speed = config.travelSpeed();
		if (remaining < config.brakeDistance()) {
			double ratio = Math.max(0.0, remaining / config.brakeDistance());
			speed = config.approachSpeed() + (config.travelSpeed() - config.approachSpeed()) * ratio;
			speed = Math.max(config.approachSpeed() * 0.6, speed);
		}

		drive.progress = Math.min(total, drive.progress + speed * Math.max(1, config.tickInterval()));
		Vector target = pointAt(drive.path, drive.progress);
		if (target == null) {
			return;
		}
		// 车头朝向也跟轨道走：过弯时车体跟着转，看起来才是「沿轨道行进」而不是平移。
		moveCart(headCart, target, yawAt(drive.path, drive.progress));
		placeCarsBehind(drive, headCart, target, spacingOf(drive));
	}

	/**
	 * 这列车该用多大间距。
	 *
	 * <p>优先用这列车自己在 {@code trains.yml} 里配的值（菜单里可以单独调），
	 * 没配（0）或没命名就退回配置文件的默认值。上限一律 1.0 格 ——
	 * 再宽就不像一列编组而像一队各自跑的车了。</p>
	 */
	private double spacingOf(Drive drive) {
		double fallback = plugin.settings().carSpacing();
		if (drive.lineName == null || drive.lineName.isBlank() || plugin.lines() == null) {
			return fallback;
		}
		TrainLines.TrainLine line = plugin.lines().get(drive.lineName);
		if (line == null || line.spacing() <= 0) {
			return fallback;
		}
		return Math.min(Math.max(line.spacing(), 0.5), 1.0);
	}

	/**
	 * 把一节矿车摆到折线上的某个点。
	 *
	 * <p><b>为什么不用 {@code setVelocity}</b>：26.3 的矿车走的是新的轨道运动模型，
	 * 插件设进去的速度会被原版的轨道逻辑与摩擦立刻吃掉 —— 实测给 0.34 格/tick，
	 * 下一 tick 读回来的 Motion 只有 1e-8，车头原地不动（这正是一开始
	 * 「自动驾驶跑了半截就不动了」的真因，不是路径算错、也不是燃料问题）。
	 * 直接改位置才可控，刹停距离与停车点也才算得准。</p>
	 *
	 * <p>每 tick 摆一次意味着转向时不会切角，坡道上也不会被插值卡在半空。</p>
	 */
	private static void moveCart(Minecart cart, Vector point, float yaw) {
		Location at = cart.getLocation();
		Location to = new Location(at.getWorld(), point.getX(), point.getY(), point.getZ(), yaw, at.getPitch());
		if (at.distanceSquared(to) < 1.0e-8 && Math.abs(at.getYaw() - yaw) < 0.5f) {
			return;
		}
		cart.teleport(to);
		cart.setVelocity(new Vector());
	}

	/** 折线总长（格）。 */
	private static double pathLength(List<Vector> points) {
		double total = 0;
		for (int i = 1; i < points.size(); i++) {
			total += points.get(i - 1).distance(points.get(i));
		}
		return total;
	}

	/**
	 * 把各节车厢沿着铁轨排在车头后面。
	 *
	 * <p>位置全部取自 {@link RailPath#behindChain}：从车头所在的铁轨方块往「来的方向」
	 * 逐格往回走，得到一串铁轨方块中心。这样每节车厢都正好压在轨道正中。</p>
	 *
	 * <p>早先拿车头的浮点坐标往回推，车头只要偏离中线半个像素（原版矿车会左右微调），
	 * 算出来的点就落到轨道外面，被判成脱轨直接掉到地下 —— 表现就是「跑到一半车厢集体
	 * 消失、列车散架」。</p>
	 */
	private void placeCarsBehind(Drive drive, Minecart headCart, Vector aheadTarget, double spacing) {
		List<UUID> line = plugin.trains().consistOf(headCart.getUniqueId());
		if (line.size() < 2) {
			return;
		}
		World world = headCart.getWorld();
		Location now = headCart.getLocation();
		RailPath.BlockKey here = RailPath.BlockKey.of(now);
		RailPath.BlockKey onward = aheadTarget == null ? null : RailPath.BlockKey.of(
			new Location(world, aheadTarget.getX(), aheadTarget.getY(), aheadTarget.getZ()));
		int needed = (int) Math.ceil(spacing * (line.size() - 1)) + 3;
		List<Vector> chain = RailPath.behindChain(world, here, onward, Math.max(3, needed));
		if (chain.size() < 2) {
			return;
		}

		// 车头在自己那一格里并不是永远停在正中心：它是按弧长插值移动的，可能已经走到格子前沿。
		// 早先直接从「车头方块中心」往后量间距，结果车头一走到格子边上，第一节车厢就贴上来，
		// 走回格子中间又拉开 —— 表现就是间距忽大忽小、过弯时像被甩了一下。
		// 这里先把「车头实际位置相对本格中心、沿向后方向的偏移」算出来，再统一加进每节的弧长里，
		// 于是不管车头在格子里哪儿，它与第一节的直线距离都恒等于 spacing。
		Vector center = chain.get(0);
		Vector back = chain.get(1).clone().subtract(center);
		if (back.lengthSquared() < 1.0e-9) {
			return;
		}
		back.normalize();
		double headOffset = now.toVector().subtract(center).dot(back);

		boolean headIsFirst = line.get(0).equals(headCart.getUniqueId());
		for (int i = 1; i < line.size(); i++) {
			UUID id = headIsFirst ? line.get(i) : line.get(line.size() - 1 - i);
			Entity entity = Bukkit.getEntity(id);
			if (!(entity instanceof Minecart cart)) {
				continue;
			}
			double distance = Math.max(0.0, headOffset + spacing * i);
			Vector target = pointAt(chain, distance);
			if (target == null) {
				continue;
			}
			// 朝向跟着轨道走：过弯时车厢本体也一起转过去，不然会看到一排方块车横着滑过拐角。
			Location location = new Location(world, target.getX(), target.getY(), target.getZ(),
				yawAt(chain, distance), 0f);
			cart.teleport(location);
			cart.setVelocity(new Vector());
		}
	}

	/**
	 * 折线上某处的朝向（Minecraft 的 yaw：0 为 +Z，顺时针）。
	 *
	 * <p>取该点所在那一段的方向：段内方向就是这两格铁轨之间的走向，过弯时自然跟着拐。
	 * 用 {@code atan2(-dx, dz)} 是因为 MC 的 yaw 以 -Z 为 90°、+X 为 -90°。</p>
	 */
	private static float yawAt(List<Vector> points, double distance) {
		double walked = 0;
		for (int i = 1; i < points.size(); i++) {
			Vector a = points.get(i - 1);
			Vector b = points.get(i);
			double length = a.distance(b);
			if (walked + length >= distance || i == points.size() - 1) {
				double dx = b.getX() - a.getX();
				double dz = b.getZ() - a.getZ();
				if (Math.abs(dx) < 1.0e-9 && Math.abs(dz) < 1.0e-9) {
					break;
				}
				return (float) Math.toDegrees(Math.atan2(-dx, dz));
			}
			walked += length;
		}
		return 0f;
	}

	/** 折线上从起点算起 distance 格处的点；distance 超出两端时贴到端点。 */
	private static Vector pointAt(List<Vector> points, double distance) {
		if (points.isEmpty()) {
			return null;
		}
		if (distance <= 0) {
			return points.get(0).clone();
		}
		double walked = 0;
		for (int i = 1; i < points.size(); i++) {
			Vector a = points.get(i - 1);
			Vector b = points.get(i);
			double length = a.distance(b);
			if (walked + length >= distance) {
				double need = distance - walked;
				double ratio = length <= 0 ? 0 : need / length;
				Vector point = a.clone().add(b.clone().subtract(a).multiply(ratio));
				// 落在两格之间时只取水平插值，y 取较近那一格的轨面，
				// 免得坡道上两格高差被插出半个方块、矿车卡在半空。
				point.setY(Math.abs(a.getY() - point.getY()) < Math.abs(b.getY() - point.getY())
					? a.getY() : b.getY());
				return point;
			}
			walked += length;
		}
		return points.get(points.size() - 1).clone();
	}

	/**
	 * 换下一站。循环路线就绕回第一站；非循环路线在终点掉头（双向列车）或者结束。
	 *
	 * @return false 表示这条路线上已经没有下一站了
	 */
	private boolean advanceStation(Drive drive, TrainRoutes.TrainRoute route) {
		List<String> stations = route.stations();
		int next = drive.index + drive.direction;
		if (next >= 0 && next < stations.size()) {
			drive.index = next;
			return true;
		}
		if (route.loop()) {
			drive.index = drive.direction > 0 ? 0 : stations.size() - 1;
			return true;
		}
		// 非循环：看看另一端有没有动力矿车可以当反向车头
		boolean flipped = flipHead(drive);
		if (!flipped) {
			return false;
		}
		drive.direction = -drive.direction;
		drive.index = drive.index + drive.direction;
		return drive.index >= 0 && drive.index < stations.size();
	}

	/** 双向列车掉头：把车头换成另一端的动力矿车。 */
	private boolean flipHead(Drive drive) {
		List<UUID> line = plugin.trains().consistOf(drive.head);
		if (line.size() < 2) {
			return false;
		}
		UUID other = line.get(0).equals(drive.head) ? line.get(line.size() - 1) : line.get(0);
		Entity entity = Bukkit.getEntity(other);
		if (!(entity instanceof PoweredMinecart)) {
			return false;
		}
		UUID old = drive.head;
		drive.head = other;
		drives.remove(old);
		drives.put(other, drive);
		return true;
	}

	/** 从车头当前位置重新算到本站的铁轨路径。 */
	private boolean rebuildPath(Drive drive) {
		Entity entity = Bukkit.getEntity(drive.head);
		if (!(entity instanceof Minecart cart)) {
			return false;
		}
		TrainRoutes.TrainRoute route = plugin.routes().get(drive.routeName);
		if (route == null) {
			return false;
		}
		TrainStations.TrainStation station = plugin.stations().get(route.stations().get(drive.index));
		if (station == null || !station.usable()) {
			return false;
		}
		World world = cart.getWorld();
		if (!world.equals(station.world())) {
			return false;
		}
		RailPath path = RailPath.find(world, RailPath.BlockKey.of(cart.getLocation()),
			new RailPath.BlockKey(station.x(), station.y(), station.z()), plugin.settings().maxSearchBlocks());
		if (path == null || path.isEmpty()) {
			return false;
		}
		drive.path = new ArrayList<>(path.points());
		drive.cursor = 0;
		drive.progress = 0.0;
		drive.dwellTicks = 0;
		drive.dwelling = false;
		return true;
	}

	/**
	 * 车头脚下那一格是不是通电的动力铁轨。
	 *
	 * <p>原版给动力铁轨通电后会把上面的矿车一直往前推，不需要燃料。所以燃料耗尽时
	 * 只要车头正好压在这样一段轨道上，就不该判成「开不动」。</p>
	 *
	 * <p>取脚下那一格用的是 {@code add(0, -0.06, 0)}：矿车中心比轨面高一点点，
	 * 直接取中心会落到铁轨方块的上面一格。</p>
	 */
	private static boolean onPoweredRail(Minecart cart) {
		Location under = cart.getLocation().add(0, -0.06, 0);
		if (!(under.getBlock().getBlockData() instanceof org.bukkit.block.data.type.RedstoneRail rail)) {
			return false;
		}
		return rail.isPowered();
	}

	private void notifyNearest(Minecart cart, String message) {
		// 先记一条日志：玩家不在附近时消息发不出去，事后排查只能靠这行。
		// 注意别自己再写 "[train] " 前缀 —— getLogger() 已经带了插件名。
		plugin.getLogger().info(TrainText.plain(TrainText.component(message))
			+ "（车头在 " + cart.getLocation().getBlockX() + "," + cart.getLocation().getBlockY()
			+ "," + cart.getLocation().getBlockZ() + "）");
		for (Player player : cart.getWorld().getPlayers()) {
			if (player.getLocation().distanceSquared(cart.getLocation()) < 48 * 48) {
				player.sendMessage(TrainText.component(message));
			}
		}
	}

	/** 车厢被拆或列车解散时清掉状态。 */
	public void forget(UUID any) {
		drives.remove(any);
		drives.values().removeIf(drive -> plugin.trains().consistOf(drive.head).isEmpty());
	}

	public void clear() {
		drives.clear();
	}
}
