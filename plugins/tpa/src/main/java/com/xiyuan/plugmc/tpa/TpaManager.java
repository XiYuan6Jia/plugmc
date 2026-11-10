package com.xiyuan.plugmc.tpa;

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
import java.util.Map;
import java.util.UUID;

/**
 * 申请传送：申请 → 对方一键接受 → 读秒热身 → 传送。
 * 请求只保存在内存中，重启即失效；热身期间移动或受伤可取消。
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

	public boolean request(Player from, Player target, TpaRequest.Direction direction) {
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

		TpaRequest request = new TpaRequest(
			from.getUniqueId(),
			from.getName(),
			target.getUniqueId(),
			target.getName(),
			direction,
			now + config.requestTimeoutSeconds() * 1000L
		);
		pending.put(target.getUniqueId(), request);
		cooldownUntil.put(from.getUniqueId(), now + config.cooldownSeconds() * 1000L);

		from.sendMessage(TpaText.of("&a已向 &e" + target.getName() + " &a发送传送申请。")
			.append(Component.text(" "))
			.append(button("&c[撤回]", "tpcancel " + target.getName(), "撤回这条申请")));

		target.sendMessage(TpaText.of("&e" + from.getName() + " &f"
				+ (direction == TpaRequest.Direction.TO_TARGET
					? "请求传送到你身边："
					: "请求把你传送到他身边："))
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

		pending.remove(target.getUniqueId());
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

	public boolean inWarmup(Player player) {
		return warmups.containsKey(player.getUniqueId());
	}

	private void startWarmup(Player traveller, Player anchor) {
		int seconds = config.warmupSeconds();
		if (seconds <= 0) {
			teleport(traveller, anchor);
			return;
		}

		long expiresAt = System.currentTimeMillis() + seconds * 1000L;
		Warmup warmup = new Warmup(traveller.getLocation().clone(), anchor.getUniqueId(), expiresAt);
		warmups.put(traveller.getUniqueId(), warmup);
		traveller.sendMessage(TpaText.of("&e传送准备中：&f" + seconds + " &e秒后传送，移动或受伤会取消。"));

		warmup.task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
			Player current = plugin.getServer().getPlayer(traveller.getUniqueId());
			if (current == null) {
				abort(traveller.getUniqueId(), null);
				return;
			}
			Player destination = plugin.getServer().getPlayer(warmup.anchorId);
			if (destination == null) {
				abort(traveller.getUniqueId(), "&c传送已取消：对方已离线。");
				return;
			}
			if (config.cancelOnMove() && moved(warmup.start, current.getLocation())) {
				abort(traveller.getUniqueId(), "&c传送已取消：你移动了位置。");
				return;
			}
			long left = warmup.expiresAt - System.currentTimeMillis();
			if (left <= 0) {
				finish(current, destination);
				return;
			}
			current.sendActionBar(TpaText.of("&e传送中… &f" + ((left + 999) / 1000) + "s"));
		}, 20L, 10L);
	}

	private void finish(Player traveller, Player anchor) {
		Warmup warmup = warmups.remove(traveller.getUniqueId());
		if (warmup != null) {
			warmup.task.cancel();
		}
		teleport(traveller, anchor);
	}

	private void teleport(Player traveller, Player anchor) {
		boolean success = traveller.teleport(anchor.getLocation());
		if (success) {
			traveller.sendMessage(TpaText.of("&a已传送到 &e" + anchor.getName() + " &a身边。"));
			anchor.sendMessage(TpaText.of("&e" + traveller.getName() + " &a已传送过来。"));
		} else {
			traveller.sendMessage(TpaText.of("&c传送失败，请稍后重试。"));
			anchor.sendMessage(TpaText.of("&c" + traveller.getName() + " 的传送失败。"));
		}
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
		private final UUID anchorId;
		private final long expiresAt;
		private BukkitTask task;

		private Warmup(Location start, UUID anchorId, long expiresAt) {
			this.start = start;
			this.anchorId = anchorId;
			this.expiresAt = expiresAt;
		}
	}
}
