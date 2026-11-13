package com.xiyuan.plugmc.tpa;

import com.xiyuan.plugmc.sethome.api.HomeService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 申请传送：申请 → 对方一键接受 → 读秒热身 → 传送。
 * 请求只保存在内存中，重启即失效；热身期间移动或受伤可取消。
 *
 * <p>三种方向共用同一套申请流程：传到对方身边、把对方拉到自己身边、传到对方的家。
 * 「家」的坐标在 <b>接受那一刻</b> 才去查 —— 申请期间对方可能已经把那个家删了。</p>
 */
public final class TpaManager implements Listener {
	private final JavaPlugin plugin;
	private final Map<UUID, TpaRequest> pending = new HashMap<>();
	private final Map<UUID, Long> cooldownUntil = new HashMap<>();
	private final Map<UUID, Warmup> warmups = new HashMap<>();
	private volatile TpaConfig config;

	public TpaManager(JavaPlugin plugin, TpaConfig config) {
		this.plugin = plugin;
		this.config = config;
	}

	public TpaConfig config() {
		return config;
	}

	public void reload() {
		plugin.reloadConfig();
		this.config = TpaConfig.load(plugin.getConfig());
		plugin.getLogger().info("申请传送配置已重载：热身 " + config.warmupSeconds()
			+ " 秒，冷却 " + config.cooldownSeconds() + " 秒");
	}

	/** 家园数据来自 sethome 插件；它没装或没起来时返回 null。 */
	private HomeService homes() {
		return Bukkit.getServicesManager().load(HomeService.class);
	}

	public boolean request(Player from, Player target, TpaRequest.Direction direction) {
		return request(from, target, direction, null);
	}

	public boolean request(Player from, Player target, TpaRequest.Direction direction, String homeName) {
		if (!config.enabled()) {
			from.sendMessage(TpaText.of("&c申请传送暂未开放。"));
			return false;
		}
		if (!from.hasPermission("tpa.use")) {
			from.sendMessage(TpaText.of("&c你没有权限使用申请传送。"));
			return false;
		}
		if (from.getUniqueId().equals(target.getUniqueId())) {
			from.sendMessage(TpaText.of("&c不能对自己发起传送申请。"));
			return false;
		}
		if (direction == TpaRequest.Direction.TO_HOME) {
			if (!config.allowHomeRequest()) {
				from.sendMessage(TpaText.of("&c传送到别人家暂未开放。"));
				return false;
			}
			if (!from.hasPermission("tpa.home")) {
				from.sendMessage(TpaText.of("&c你没有权限申请传送到别人家。"));
				return false;
			}
			HomeService service = homes();
			if (service == null) {
				from.sendMessage(TpaText.of("&c家园功能当前不可用。"));
				return false;
			}
			if (!service.has(target.getUniqueId(), homeName)) {
				// 对方一个家都没有时，连申请都没必要发
				from.sendMessage(TpaText.of(service.count(target.getUniqueId()) == 0
					? "&c" + target.getName() + " 还没有设置家园。"
					: "&c" + target.getName() + " 没有名为 &f" + homeName + " &c的家园。"));
				return false;
			}
		}
		long now = System.currentTimeMillis();
		Long cooldown = cooldownUntil.get(from.getUniqueId());
		if (cooldown != null && cooldown > now) {
			from.sendMessage(TpaText.of("&c请等待 " + ((cooldown - now) / 1000 + 1) + " 秒后再发起申请。"));
			return false;
		}
		if (warmups.containsKey(from.getUniqueId()) || warmups.containsKey(target.getUniqueId())) {
			from.sendMessage(TpaText.of("&c双方中有人正在传送读秒中。"));
			return false;
		}

		String home = direction == TpaRequest.Direction.TO_HOME
			? (homeName == null || homeName.isBlank() ? null : homeName.trim())
			: null;
		TpaRequest request = new TpaRequest(
			from.getUniqueId(),
			from.getName(),
			target.getUniqueId(),
			target.getName(),
			direction,
			home,
			now + config.requestTimeoutSeconds() * 1000L
		);
		pending.put(target.getUniqueId(), request);
		cooldownUntil.put(from.getUniqueId(), now + config.cooldownSeconds() * 1000L);

		from.sendMessage(TpaText.of("&a已向 &e" + target.getName() + " &a发送"
				+ (direction == TpaRequest.Direction.TO_HOME ? "传送到他家的" : "") + "申请。")
			.append(Component.text(" "))
			.append(button("&c[撤回]", "tpcancel " + target.getName(), "撤回这条申请")));

		target.sendMessage(TpaText.of(request.describe() + "：")
			.append(Component.text(" "))
			.append(button("&a&l[点击接受]", "tpaccept " + from.getName(), "接受后开始传送读秒"))
			.append(Component.text(" "))
			.append(button("&c&l[拒绝]", "tpadeny " + from.getName(), "拒绝这条传送申请")));
		target.sendMessage(TpaText.of("&7也可以按 &fShift+F &7打开菜单，在「申请传送」里处理"
			+ "（" + config.requestTimeoutSeconds() + " 秒内有效）。"));
		return true;
	}

	public boolean accept(Player target, String requesterName) {
		TpaRequest request = pending.get(target.getUniqueId());
		if (request == null || request.expired(System.currentTimeMillis())) {
			pending.remove(target.getUniqueId());
			target.sendMessage(TpaText.of("&c没有有效的传送申请。"));
			return false;
		}
		if (requesterName != null && !requesterName.isBlank()
			&& !request.fromName().equalsIgnoreCase(requesterName)) {
			target.sendMessage(TpaText.of("&c没有来自 " + requesterName + " 的传送申请。"));
			return false;
		}
		Player requester = Bukkit.getPlayer(request.from());
		if (requester == null) {
			pending.remove(target.getUniqueId());
			target.sendMessage(TpaText.of("&c申请人已离线。"));
			return false;
		}
		if (!config.allowCrossWorld() && !requester.getWorld().equals(target.getWorld())) {
			target.sendMessage(TpaText.of("&c对方在其他世界，当前配置不允许跨世界传送。"));
			return false;
		}

		// 家的坐标到这一刻才解析：申请发出后对方可能改了家、或者把家删了
		Location homeTarget = null;
		if (request.isHome()) {
			HomeService service = homes();
			if (service == null) {
				pending.remove(target.getUniqueId());
				target.sendMessage(TpaText.of("&c家园功能当前不可用，已取消这条申请。"));
				requester.sendMessage(TpaText.of("&c家园功能当前不可用，传送申请已取消。"));
				return false;
			}
			String name = request.homeName() == null ? service.defaultName() : request.homeName();
			homeTarget = service.home(target.getUniqueId(), name);
			if (homeTarget == null) {
				pending.remove(target.getUniqueId());
				target.sendMessage(TpaText.of("&c你现在的家园列表里没有 &f" + name
					+ " &c了，已取消这条申请。"));
				requester.sendMessage(TpaText.of("&e" + target.getName() + " 的家园已不存在，"
					+ "传送申请已取消。"));
				return false;
			}
		}

		pending.remove(target.getUniqueId());

		if (request.isHome()) {
			requester.sendMessage(TpaText.of("&a" + target.getName() + " 接受了你的传送申请，"
				+ "正在前往他的家。"));
			target.sendMessage(TpaText.of("&a已接受 " + requester.getName() + " 的传送申请。"));
			String label = "&e" + target.getName() + " &a的家"
				+ (request.homeName() == null ? "" : "（" + request.homeName() + "）");
			startWarmup(requester, homeTarget, label, null);
			return true;
		}

		Player traveller = request.direction() == TpaRequest.Direction.TO_TARGET ? requester : target;
		Player anchor = request.direction() == TpaRequest.Direction.TO_TARGET ? target : requester;

		requester.sendMessage(TpaText.of("&a" + target.getName() + " 接受了你的传送申请。"));
		target.sendMessage(TpaText.of("&a已接受 " + requester.getName() + " 的传送申请。"));
		startWarmup(traveller, anchor);
		return true;
	}

	public boolean deny(Player target, String requesterName) {
		TpaRequest request = pending.get(target.getUniqueId());
		if (request == null) {
			target.sendMessage(TpaText.of("&7当前没有待处理的传送申请。"));
			return false;
		}
		if (requesterName != null && !requesterName.isBlank()
			&& !request.fromName().equalsIgnoreCase(requesterName)) {
			target.sendMessage(TpaText.of("&c没有来自 " + requesterName + " 的传送申请。"));
			return false;
		}
		pending.remove(target.getUniqueId());
		target.sendMessage(TpaText.of("&7已拒绝 " + request.fromName() + " 的传送申请。"));
		Player requester = Bukkit.getPlayer(request.from());
		if (requester != null) {
			requester.sendMessage(TpaText.of("&c" + target.getName() + " 拒绝了你的传送申请。"));
		}
		return true;
	}

	public boolean cancel(Player requester, String targetName) {
		for (Map.Entry<UUID, TpaRequest> entry : new HashMap<>(pending).entrySet()) {
			TpaRequest request = entry.getValue();
			if (!request.from().equals(requester.getUniqueId())) {
				continue;
			}
			if (targetName != null && !targetName.isBlank()
				&& !request.toName().equalsIgnoreCase(targetName)) {
				continue;
			}
			pending.remove(entry.getKey());
			requester.sendMessage(TpaText.of("&7已撤回发给 " + request.toName() + " 的传送申请。"));
			Player target = Bukkit.getPlayer(request.to());
			if (target != null) {
				target.sendMessage(TpaText.of("&7" + requester.getName() + " 撤回了传送申请。"));
			}
			return true;
		}
		requester.sendMessage(TpaText.of("&7没有可撤回的传送申请。"));
		return false;
	}

	public String pendingRequesterName(Player target) {
		TpaRequest request = pending.get(target.getUniqueId());
		if (request == null) {
			return null;
		}
		if (request.expired(System.currentTimeMillis())) {
			pending.remove(target.getUniqueId());
			return null;
		}
		return request.fromName();
	}

	/** 待处理申请的说明文字，给菜单用；没有则返回 null。 */
	public String pendingDescription(Player target) {
		TpaRequest request = pending.get(target.getUniqueId());
		if (request == null) {
			return null;
		}
		if (request.expired(System.currentTimeMillis())) {
			pending.remove(target.getUniqueId());
			return null;
		}
		return request.describe();
	}

	public boolean inWarmup(Player player) {
		return warmups.containsKey(player.getUniqueId());
	}

	/**
	 * 某个目标玩家的候选家园名（给「传送到他家」补全与菜单用）。
	 */
	public List<String> homeNamesOf(Player target) {
		HomeService service = homes();
		return service == null ? List.of() : service.names(target.getUniqueId());
	}

	private void startWarmup(Player traveller, Player anchor) {
		startWarmup(traveller, anchor.getLocation().clone(), "&e" + anchor.getName() + " &a身边",
			anchor.getUniqueId());
	}

	/**
	 * @param anchorId 要一直追踪的那个玩家；传 null 表示目标是一个固定坐标（比如家），
	 *                 读秒期间不再关心任何人是否在线
	 */
	private void startWarmup(Player traveller, Location destination, String label, UUID anchorId) {
		int seconds = config.warmupSeconds();
		if (seconds <= 0) {
			teleport(traveller, destination, label, anchorId);
			return;
		}

		long expiresAt = System.currentTimeMillis() + seconds * 1000L;
		Warmup warmup = new Warmup(traveller.getLocation().clone(), destination, label, anchorId, expiresAt);
		warmups.put(traveller.getUniqueId(), warmup);
		traveller.sendMessage(TpaText.of("&e传送准备中：&f" + seconds + " &e秒后传送，移动或受伤会取消。"));

		warmup.task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
			Player current = plugin.getServer().getPlayer(traveller.getUniqueId());
			if (current == null) {
				abort(traveller.getUniqueId(), null);
				return;
			}
			if (warmup.anchorId != null) {
				Player tracked = plugin.getServer().getPlayer(warmup.anchorId);
				if (tracked == null) {
					abort(traveller.getUniqueId(), "&c传送已取消：对方已离线。");
					return;
				}
				// 对方在你读秒期间还在动的话，传过去要跟着他的新位置
				warmup.destination = tracked.getLocation().clone();
			}
			if (config.cancelOnMove() && moved(warmup.start, current.getLocation())) {
				abort(traveller.getUniqueId(), "&c传送已取消：你移动了位置。");
				return;
			}
			long left = warmup.expiresAt - System.currentTimeMillis();
			if (left <= 0) {
				finish(current);
				return;
			}
			current.sendActionBar(TpaText.of("&e传送中… &f" + ((left + 999) / 1000) + "s"));
		}, 20L, 10L);
	}

	private void finish(Player traveller) {
		Warmup warmup = warmups.remove(traveller.getUniqueId());
		if (warmup == null) {
			return;
		}
		warmup.task.cancel();
		teleport(traveller, warmup.destination, warmup.label, warmup.anchorId);
	}

	private void teleport(Player traveller, Location destination, String label, UUID anchorId) {
		if (!traveller.isOnline()) {
			return;
		}
		if (traveller.teleport(destination)) {
			traveller.sendMessage(TpaText.of("&a已传送到 " + label + "&a。"));
			if (anchorId != null) {
				Player anchor = plugin.getServer().getPlayer(anchorId);
				if (anchor != null) {
					anchor.sendMessage(TpaText.of("&e" + traveller.getName() + " &a已传送过来。"));
				}
			}
			return;
		}
		traveller.sendMessage(TpaText.of("&c传送失败，请稍后重试。"));
	}

	private void abort(UUID travellerId, String message) {
		Warmup warmup = warmups.remove(travellerId);
		if (warmup != null) {
			warmup.task.cancel();
		}
		if (message != null) {
			Player traveller = plugin.getServer().getPlayer(travellerId);
			if (traveller != null) {
				traveller.sendMessage(TpaText.of(message));
			}
		}
	}

	private boolean moved(Location start, Location current) {
		if (start.getWorld() == null || current.getWorld() == null
			|| !start.getWorld().equals(current.getWorld())) {
			return true;
		}
		return start.distanceSquared(current) > 1.0D;
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onDamage(EntityDamageEvent event) {
		if (!config.cancelOnDamage() || !(event.getEntity() instanceof Player player)) {
			return;
		}
		if (inWarmup(player)) {
			abort(player.getUniqueId(), "&c传送已取消：你受到了伤害。");
		}
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		Player player = event.getPlayer();
		Warmup warmup = warmups.remove(player.getUniqueId());
		if (warmup != null) {
			warmup.task.cancel();
		}
		pending.remove(player.getUniqueId());
		pending.entrySet().removeIf(entry -> entry.getValue().from().equals(player.getUniqueId()));
		cooldownUntil.remove(player.getUniqueId());
	}

	private static Component button(String label, String command, String hover) {
		return TpaText.of(label)
			.clickEvent(ClickEvent.runCommand(command))
			.hoverEvent(HoverEvent.showText(TpaText.of("&7" + hover)));
	}

	private static final class Warmup {
		private final Location start;
		private Location destination;
		private final String label;
		private final UUID anchorId;
		private final long expiresAt;
		private BukkitTask task;

		private Warmup(Location start, Location destination, String label, UUID anchorId, long expiresAt) {
			this.start = start;
			this.destination = destination;
			this.label = label;
			this.anchorId = anchorId;
			this.expiresAt = expiresAt;
		}
	}
}
