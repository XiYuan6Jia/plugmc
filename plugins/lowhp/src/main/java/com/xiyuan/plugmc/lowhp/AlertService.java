package com.xiyuan.plugmc.lowhp;

import org.bukkit.Bukkit;
import org.bukkit.SoundCategory;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * 低血量提示音：血量低于阈值时随机给该玩家播放一段提示音。
 *
 * <p>声音只发给该玩家自己（{@code Player#playSound}），不会影响其他人；
 * 播放间隔由冷却控制，回血超过上限阈值时会立刻停止并重置冷却。</p>
 */
public final class AlertService {
	private final JavaPlugin plugin;
	private final Random random = new Random();
	private final Map<UUID, Playing> playing = new HashMap<>();
	private final Map<UUID, Long> nextAllowed = new HashMap<>();
	private final Set<UUID> muted = new HashSet<>();
	private final File mutedFile;
	private LowHpConfig config;
	private Map<String, Float> durations = Map.of();
	private BukkitTask task;

	private record Playing(Alert alert, long endAt, BukkitTask endTask) {
	}

	public AlertService(JavaPlugin plugin, LowHpConfig config) {
		this.plugin = plugin;
		this.config = config;
		this.mutedFile = new File(plugin.getDataFolder(), "muted.yml");
	}

	public void apply(LowHpConfig config, Map<String, Float> durations) {
		this.config = config;
		this.durations = Map.copyOf(durations);
	}

	public LowHpConfig config() {
		return config;
	}

	public void loadMuted() {
		muted.clear();
		if (!mutedFile.isFile()) {
			return;
		}
		org.bukkit.configuration.file.YamlConfiguration yaml =
			org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(mutedFile);
		for (String raw : yaml.getStringList("muted")) {
			try {
				muted.add(UUID.fromString(raw));
			} catch (IllegalArgumentException ignored) {
				// 脏数据直接跳过
			}
		}
	}

	public void saveMuted() {
		var yaml = new org.bukkit.configuration.file.YamlConfiguration();
		yaml.set("muted", muted.stream().map(UUID::toString).toList());
		try {
			File parent = mutedFile.getParentFile();
			if (parent != null) {
				parent.mkdirs();
			}
			yaml.save(mutedFile);
		} catch (IOException exception) {
			plugin.getLogger().warning("保存静音列表失败：" + exception.getMessage());
		}
	}

	public boolean muted(Player player) {
		return muted.contains(player.getUniqueId());
	}

	/**
	 * 切换静音，返回切换后是否静音。
	 */
	public boolean toggleMuted(Player player) {
		boolean nowMuted;
		if (muted.remove(player.getUniqueId())) {
			nowMuted = false;
		} else {
			muted.add(player.getUniqueId());
			nowMuted = true;
			stop(player);
		}
		saveMuted();
		return nowMuted;
	}

	public void start() {
		stop();
		task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 10L);
	}

	public void stop() {
		if (task != null) {
			task.cancel();
			task = null;
		}
		for (UUID id : Map.copyOf(playing).keySet()) {
			Player player = Bukkit.getPlayer(id);
			if (player != null) {
				stop(player);
			}
		}
		playing.clear();
		nextAllowed.clear();
	}

	/**
	 * 手动播放（命令/测试用），不受血量与冷却限制。
	 */
	public boolean playNow(Player player, Alert alert) {
		stop(player);
		if (!config.alerts().contains(alert)) {
			player.sendMessage(LowHpText.component("&c当前配置没有启用提示音 " + alert.id()));
			return false;
		}
		String sound = alert.soundKey("lowhp");
		player.playSound(player.getLocation(), sound, SoundCategory.PLAYERS,
			(float) config.volume(), config.pitch());
		float seconds = durations.getOrDefault(alert.id(), 0f);
		long endAt = System.currentTimeMillis() + (long) (seconds * 1000L);
		BukkitTask endTask = null;
		if (seconds > 0) {
			long ticks = Math.max(20L, (long) (seconds * 20L) + 20L);
			endTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
				Playing current = playing.remove(player.getUniqueId());
				if (current != null) {
					player.stopSound(sound, SoundCategory.PLAYERS);
				}
			}, ticks);
		}
		playing.put(player.getUniqueId(), new Playing(alert, endAt, endTask));
		return true;
	}

	/**
	 * 停止该玩家正在播放的提示音。
	 */
	public boolean stop(Player player) {
		Playing current = playing.remove(player.getUniqueId());
		if (current == null) {
			return false;
		}
		if (current.endTask() != null) {
			current.endTask().cancel();
		}
		player.stopSound(current.alert().soundKey("lowhp"), SoundCategory.PLAYERS);
		return true;
	}

	public boolean isPlaying(Player player) {
		return playing.containsKey(player.getUniqueId());
	}

	public Alert currentAlert(Player player) {
		Playing current = playing.get(player.getUniqueId());
		return current == null ? null : current.alert();
	}

	/**
	 * 血量阈值（绝对值）。
	 */
	public double threshold(Player player) {
		return threshold(maxHealth(player), config.thresholdPercent());
	}

	/**
	 * 纯计算版本，便于单测：血量是否已经低到该触发提示音。
	 */
	public static double threshold(double maxHealth, double thresholdPercent) {
		return Math.max(0.0, maxHealth) * thresholdPercent;
	}

	public static boolean lowHealth(double health, double maxHealth, double thresholdPercent) {
		return health > 0 && health <= threshold(maxHealth, thresholdPercent);
	}

	public double healLine(Player player) {
		return maxHealth(player) * config.healPercent();
	}

	private double maxHealth(Player player) {
		AttributeInstance attribute = player.getAttribute(Attribute.MAX_HEALTH);
		return attribute == null ? 20.0 : attribute.getValue();
	}

	private void tick() {
		LowHpConfig settings = config;
		if (!settings.enabled()) {
			return;
		}
		long now = System.currentTimeMillis();
		for (Player player : Bukkit.getOnlinePlayers()) {
			if (player.isDead() || player.getHealth() <= 0) {
				continue;
			}
			UUID id = player.getUniqueId();
			Playing current = playing.get(id);
			if (current != null) {
				if (settings.stopWhenHealed() && player.getHealth() > healLine(player)) {
					stop(player);
					nextAllowed.remove(id);
				}
				continue;
			}
			if (player.getHealth() > threshold(player)) {
				// 血量恢复后重置冷却，下一次掉血能立刻听到提示
				nextAllowed.remove(id);
				continue;
			}			if (muted.contains(id) || !player.hasPermission("lowhp.alerts")) {
				continue;
			}
			long allowedAt = nextAllowed.getOrDefault(id, 0L);
			if (now < allowedAt) {
				continue;
			}
			List<Alert> alerts = settings.alertList();
			if (alerts.isEmpty()) {
				continue;
			}
			Alert alert = alerts.get(random.nextInt(alerts.size()));
			if (!playNow(player, alert)) {
				continue;
			}
			nextAllowed.put(id, now + settings.cooldownSeconds() * 1000L);
			if (settings.notifyPlayer()) {
				player.sendMessage(LowHpText.component("&c血量过低！&7正在播放：&f"
					+ alert.display() + " &8(/lowhp toggle 可关闭)"));
			}
		}
	}
}
