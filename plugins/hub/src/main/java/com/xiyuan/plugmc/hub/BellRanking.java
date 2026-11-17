package com.xiyuan.plugmc.hub;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BellRingEvent;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

/**
 * 敲钟计数与三块排行榜（总数 / 每周 / 每月）。
 *
 * <p>每次敲响主城钟亭里那口钟：总计数 +1，周计数 +1，月计数 +1；
 * 成绩落在 {@code bell.yml}（含周期水位，避免重复结算）。</p>
 *
 * <p>榜牌共三块：钟亭背面挂「敲钟总数排名」，钟的左边挂「每周敲钟排名」，
 * 右边挂「每月敲钟排名」。</p>
 *
 * <p>敲钟有极低概率触发事件（概率 {@code bell.event-chance}）：
 * 计数 +1%~15%、计数 -1%~15%（幅度随机，每次三榜按各自的计数分别计算），
 * 或限时倍率加成（等级越高持续时间越短）。
 * 倍率等级与时长从 {@code bell.multipliers} 读（倍率 → 秒）。</p>
 *
 * <p>周期结算：每周日发周榜奖金（前三名），每月 1 号发月榜奖金（前五名）。
 * 奖金表 {@code bell.rewards.weekly} / {@code bell.rewards.monthly}，支持名次键写法。
 * 同名次并列时每人都拿对应名次的奖金（不是平分）。结算只清对应周期的计数，总数不动。</p>
 */
public final class BellRanking implements Listener {
	/** 总数榜牌实体的标签，重建/清理时靠它认人。 */
	static final String BOARD_TAG = "plugmc_bell_board";
	/** 周榜牌实体的标签。 */
	static final String WEEKLY_BOARD_TAG = "plugmc_bell_board_weekly";
	/** 月榜牌实体的标签。 */
	static final String MONTHLY_BOARD_TAG = "plugmc_bell_board_monthly";

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Type STATE_TYPE = new TypeToken<JsonObject>() {
	}.getType();

	/** 同一玩家两次计数的最小间隔（毫秒）。左右手各触发一次、连着狂点都归到一次。 */
	private static final long MIN_GAP_MILLIS = 200L;
	/** 掉盘节流：最多每 30 秒写一次文件。 */
	private static final long FLUSH_INTERVAL_MILLIS = 30_000L;
	/** 周期结算检查间隔（tick）。20 ticks = 1 秒，结算本身每秒查一次水位即可。 */
	private static final long SETTLE_CHECK_TICKS = 20L;

	private final HubPlugin plugin;
	private final File file;
	/** 总计数（永不重置）。 */
	private final Map<UUID, Score> scores = new LinkedHashMap<>();
	/** 本周期周计数（结算后清空）。 */
	private final Map<UUID, Integer> weekly = new LinkedHashMap<>();
	/** 本周期月计数（结算后清空）。 */
	private final Map<UUID, Integer> monthly = new LinkedHashMap<>();
	private final Map<UUID, Long> lastRing = new LinkedHashMap<>();

	/** 倍率加成：到期时间戳 → 倍率。同一玩家同时只有一个生效（后到的覆盖先到的）。 */
	private final Map<UUID, Long> multiplierUntil = new LinkedHashMap<>();
	private final Map<UUID, Integer> multiplierLevel = new LinkedHashMap<>();

	private UUID boardId;
	private UUID weeklyBoardId;
	private UUID monthlyBoardId;
	private volatile boolean boardDirty = true;
	private volatile boolean weeklyBoardDirty = true;
	private volatile boolean monthlyBoardDirty = true;
	private volatile boolean saveDirty;
	private long lastSave = System.currentTimeMillis();

	/** 周期水位：上次结算的「周期标识」。与当前周期不一致时才结算。 */
	private String lastWeeklySettle = "";
	private String lastMonthlySettle = "";

	/** 一次敲击的计数记录；名字跟着最新一次敲击走，改名后榜上显示新名字。 */
	public record Score(String name, int count) {
	}

	/** 一次周期结算的名次行。 */
	public record RewardLine(int rank, String name, UUID id, int count, int reward) {
	}

	public BellRanking(HubPlugin plugin) {
		this.plugin = plugin;
		this.file = new File(plugin.getDataFolder(), "bell.yml");
	}

	public void load() {
		scores.clear();
		weekly.clear();
		monthly.clear();
		multiplierUntil.clear();
		multiplierLevel.clear();
		if (!file.isFile()) {
			return;
		}
		try {
			String json = Files.readString(file.toPath(), StandardCharsets.UTF_8);
			JsonObject root = GSON.fromJson(json, STATE_TYPE);
			if (root == null) {
				return;
			}
			// 老格式只有一层 Map<uuid, Score>；新格式带 totals/weekly/monthly/水位
			if (root.has("totals")) {
				readScores(root.get("totals"), scores);
				readCounts(root.get("weekly"), weekly);
				readCounts(root.get("monthly"), monthly);
				lastWeeklySettle = root.has("lastWeeklySettle") ? root.get("lastWeeklySettle").getAsString() : "";
				lastMonthlySettle = root.has("lastMonthlySettle") ? root.get("lastMonthlySettle").getAsString() : "";
			} else {
				readScores(root, scores);
			}
			plugin.getLogger().info("已恢复 " + scores.size() + " 条敲钟记录（周 "
				+ weekly.size() + " / 月 " + monthly.size() + "）");
		} catch (IOException | RuntimeException exception) {
			plugin.getLogger().log(Level.WARNING, "读取敲钟记录失败", exception);
		}
	}

	private void readScores(com.google.gson.JsonElement element, Map<UUID, Score> into) {
		if (element == null || !element.isJsonObject()) {
			return;
		}
		Type type = new TypeToken<Map<String, Score>>() {
		}.getType();
		Map<String, Score> raw = GSON.fromJson(element, type);
		if (raw == null) {
			return;
		}
		for (Map.Entry<String, Score> entry : raw.entrySet()) {
			try {
				UUID id = UUID.fromString(entry.getKey());
				Score score = entry.getValue();
				if (score != null && score.count() > 0) {
					into.put(id, score);
				}
			} catch (IllegalArgumentException exception) {
				plugin.getLogger().warning("敲钟记录里的 UUID 无效：" + entry.getKey());
			}
		}
	}

	private void readCounts(com.google.gson.JsonElement element, Map<UUID, Integer> into) {
		if (element == null || !element.isJsonObject()) {
			return;
		}
		Type type = new TypeToken<Map<String, Integer>>() {
		}.getType();
		Map<String, Integer> raw = GSON.fromJson(element, type);
		if (raw == null) {
			return;
		}
		for (Map.Entry<String, Integer> entry : raw.entrySet()) {
			try {
				UUID id = UUID.fromString(entry.getKey());
				Integer count = entry.getValue();
				if (count != null && count > 0) {
					into.put(id, count);
				}
			} catch (IllegalArgumentException exception) {
				plugin.getLogger().warning("敲钟记录里的 UUID 无效：" + entry.getKey());
			}
		}
	}

	// ------------------------------------------------------------------ 敲钟

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onRing(BellRingEvent event) {
		if (!(event.getEntity() instanceof Player player)) {
			return;
		}
		if (!isWatchedBell(event.getBlock())) {
			return;
		}
		long now = System.currentTimeMillis();
		Long previous = lastRing.get(player.getUniqueId());
		if (previous != null && now - previous < MIN_GAP_MILLIS) {
			return;
		}
		lastRing.put(player.getUniqueId(), now);
		int total = increment(player);
		int gained = 1;
		// 倍率加成：还在生效期内就按等级加成（额外次数同时计入三榜）
		Long until = multiplierUntil.get(player.getUniqueId());
		int level = multiplierLevel.getOrDefault(player.getUniqueId(), 1);
		if (until != null && until > now && level > 1) {
			gained = level;
			for (int i = 1; i < level; i++) {
				total = incrementBonus(player);
			}
		}
		player.sendActionBar(HubText.component("&6敲钟 &f+" + gained + " &7（累计 &f" + total + "&7）"));
		maybeFireEvent(player, now);
	}

	/** 计入一次并返回该玩家的累计次数（总/周/月一起加）。 */
	public int increment(Player player) {
		UUID id = player.getUniqueId();
		Score previous = scores.get(id);
		int count = (previous == null ? 0 : previous.count()) + 1;
		scores.put(id, new Score(player.getName(), count));
		weekly.merge(id, 1, Integer::sum);
		monthly.merge(id, 1, Integer::sum);
		saveDirty = true;
		boardDirty = true;
		weeklyBoardDirty = true;
		monthlyBoardDirty = true;
		return count;
	}

	/** 只额外加一次计数（倍率加成与事件用）：总/周/月三榜同时 +1。 */
	private int incrementBonus(Player player) {
		UUID id = player.getUniqueId();
		Score previous = scores.get(id);
		int count = (previous == null ? 0 : previous.count()) + 1;
		scores.put(id, new Score(player.getName(), count));
		weekly.merge(id, 1, Integer::sum);
		monthly.merge(id, 1, Integer::sum);
		saveDirty = true;
		boardDirty = true;
		weeklyBoardDirty = true;
		monthlyBoardDirty = true;
		return count;
	}

	/** 只额外减一次计数（事件用）：总/周/月三榜同时 -1。 */
	private void decrementBonus(UUID id, String name) {
		Score previous = scores.get(id);
		int count = previous == null ? 0 : previous.count();
		if (count <= 0) {
			scores.remove(id);
		} else if (count == 1) {
			scores.remove(id);
		} else {
			scores.put(id, new Score(name, count - 1));
		}
		weekly.computeIfPresent(id, (key, value) -> value <= 1 ? null : value - 1);
		monthly.computeIfPresent(id, (key, value) -> value <= 1 ? null : value - 1);
		saveDirty = true;
		boardDirty = true;
		weeklyBoardDirty = true;
		monthlyBoardDirty = true;
	}

	private boolean isWatchedBell(Block block) {
		HubConfig config = plugin.settings();
		if (!config.bellEnabled()) {
			return false;
		}
		if (!plugin.hubWorld().ready() || !plugin.hubWorld().isHub(block.getLocation())) {
			return false;
		}
		return block.getX() == config.bellX()
			&& block.getY() == config.bellY()
			&& block.getZ() == config.bellZ();
	}

	// ------------------------------------------------------------------ 随机事件

	/**
	 * 敲钟后的随机事件。触发概率 {@code bell.event-chance}（0~1）。
	 * 事件：计数 +1%~15%、计数 -1%~15%（幅度随机），或限时倍率加成（等级越高持续时间越短）。
	 * 加减的百分比对三榜各自的计数分别计算 —— 周榜多的玩家在周榜上获得的奖励也多。
	 */
	private void maybeFireEvent(Player player, long now) {
		HubConfig config = plugin.settings();
		double chance = config.bellEventChance();
		if (chance <= 0.0) {
			return;
		}
		if (ThreadLocalRandom.current().nextDouble() >= chance) {
			return;
		}
		// 三类事件等权：0=计数+1%~15%，1=计数-1%~15%，2+=倍率加成
		int roll = ThreadLocalRandom.current().nextInt(3);
		switch (roll) {
			case 0 -> addPercentBonus(player);
			case 1 -> losePercentBonus(player);
			default -> grantMultiplier(player, now);
		}
	}

	/** 事件幅度：1%~15% 之间的随机整数。 */
	private static int randomPercent() {
		return ThreadLocalRandom.current().nextInt(1, 16);
	}

	/** 按百分比计算数量，至少 1。 */
	private static int amountByPercent(int base, int percent) {
		return Math.max(1, (int) Math.round(base * percent / 100.0));
	}

	/** 事件：计数 +1%~15%（三榜按各自计数分别计算，至少各 +1）。 */
	private void addPercentBonus(Player player) {
		UUID id = player.getUniqueId();
		int percent = randomPercent();
		int totalBase = totalOf(id);
		int weeklyBase = weekly.getOrDefault(id, 0);
		int monthlyBase = monthly.getOrDefault(id, 0);
		int totalBonus = amountByPercent(totalBase, percent);
		int weeklyBonus = amountByPercent(weeklyBase, percent);
		int monthlyBonus = amountByPercent(monthlyBase, percent);
		for (int i = 0; i < totalBonus; i++) {
			incrementBonus(player);
		}
		// 周/月各自按自己的基数补足到目标值（incrementBonus 已经给它们各加了 totalBonus）
		adjustPeriod(id, weekly, weeklyBonus - totalBonus);
		adjustPeriod(id, monthly, monthlyBonus - totalBonus);
		broadcastEvent(player, "&a运气不错！" + player.getName() + " 的敲钟计数 +" + percent
			+ "%（总数 +" + totalBonus + "，本周 +" + weeklyBonus + "，本月 +" + monthlyBonus + "）");
	}

	/** 事件：计数 -1%~15%（三榜按各自计数分别计算，减不到负数）。 */
	private void losePercentBonus(Player player) {
		UUID id = player.getUniqueId();
		String name = nameOf(id);
		int percent = randomPercent();
		int totalBase = totalOf(id);
		int weeklyBase = weekly.getOrDefault(id, 0);
		int monthlyBase = monthly.getOrDefault(id, 0);
		if (totalBase <= 0) {
			return;
		}
		int totalLoss = Math.min(totalBase, amountByPercent(totalBase, percent));
		int weeklyLoss = Math.min(weeklyBase, amountByPercent(weeklyBase, percent));
		int monthlyLoss = Math.min(monthlyBase, amountByPercent(monthlyBase, percent));
		for (int i = 0; i < totalLoss; i++) {
			decrementBonus(id, name);
		}
		adjustPeriod(id, weekly, -(weeklyLoss - totalLoss));
		adjustPeriod(id, monthly, -(monthlyLoss - totalLoss));
		broadcastEvent(player, "&c手滑了！" + player.getName() + " 的敲钟计数 -" + percent
			+ "%（总数 -" + totalLoss + "，本周 -" + weeklyLoss + "，本月 -" + monthlyLoss + "）");
	}

	/** 周期计数增减指定数量（可为负），减到 0 就移除条目。 */
	private void adjustPeriod(UUID id, Map<UUID, Integer> period, int delta) {
		if (delta == 0) {
			return;
		}
		int value = period.getOrDefault(id, 0) + delta;
		if (value <= 0) {
			period.remove(id);
		} else {
			period.put(id, value);
		}
		saveDirty = true;
		weeklyBoardDirty = true;
		monthlyBoardDirty = true;
	}

	/** 该玩家在总数榜上的当前计数。 */
	private int totalOf(UUID id) {
		Score score = scores.get(id);
		return score == null ? 0 : score.count();
	}

	/**
	 * 事件：限时倍率加成。等级与持续秒数从 {@code bell.multipliers} 读（倍率 → 秒），
	 * 等级越高持续时间越短。随机挑一个等级，覆盖玩家当前可能还剩的加成。
	 */
	private void grantMultiplier(Player player, long now) {
		// multipliers 存在 config.yml 里（倍率 -> 秒），不进 HubConfig record
		org.bukkit.configuration.ConfigurationSection section =
			plugin.getConfig().getConfigurationSection("bell.multipliers");
		if (section == null) {
			return;
		}
		List<Map.Entry<Integer, Integer>> entries = new ArrayList<>();
		for (String key : section.getKeys(false)) {
			try {
				entries.add(Map.entry(Integer.parseInt(key), section.getInt(key)));
			} catch (NumberFormatException exception) {
				// 忽略写错的键
			}
		}
		if (entries.isEmpty()) {
			return;
		}
		Map.Entry<Integer, Integer> picked = entries.get(ThreadLocalRandom.current().nextInt(entries.size()));
		int level = picked.getKey();
		int seconds = Math.max(1, picked.getValue());
		multiplierUntil.put(player.getUniqueId(), now + seconds * 1000L);
		multiplierLevel.put(player.getUniqueId(), level);
		broadcastEvent(player, "&b鸿运当头！" + player.getName() + " 获得 &f" + level
			+ " &b倍敲钟加成（" + seconds + " 秒）");
	}

	/** 全服广播事件；玩家自己额外拿一条私聊。 */
	private void broadcastEvent(Player player, String message) {
		Bukkit.broadcast(HubText.component("&6[敲钟事件] &r" + message));
		player.sendMessage(HubText.component(message));
		plugin.getLogger().info("[bell-event] " + HubText.plainText(message));
	}

	/** 供命令用的倍率查询；返回 0 表示没有生效的加成。 */
	public int activeMultiplier(UUID playerId) {
		Long until = multiplierUntil.get(playerId);
		if (until == null || until <= System.currentTimeMillis()) {
			return 0;
		}
		return multiplierLevel.getOrDefault(playerId, 0);
	}

	// ------------------------------------------------------------------ 排行

	/** 按次数从多到少；同次数按名字排序，保证两次刷新之间名次不抖。 */
	public List<Map.Entry<UUID, Score>> ranked() {
		List<Map.Entry<UUID, Score>> list = new ArrayList<>(scores.entrySet());
		list.sort(Comparator
			.comparingInt((Map.Entry<UUID, Score> entry) -> entry.getValue().count()).reversed()
			.thenComparing(entry -> entry.getValue().name(), String.CASE_INSENSITIVE_ORDER));
		return list;
	}

	/** 周榜：count 降序 + 名字防抖。 */
	public List<Map.Entry<UUID, Integer>> rankedWeekly() {
		List<Map.Entry<UUID, Integer>> list = new ArrayList<>(weekly.entrySet());
		list.sort(Comparator
			.comparingInt((Map.Entry<UUID, Integer> entry) -> entry.getValue()).reversed()
			.thenComparing(entry -> nameOf(entry.getKey()), String.CASE_INSENSITIVE_ORDER));
		return list;
	}

	/** 月榜：count 降序 + 名字防抖。 */
	public List<Map.Entry<UUID, Integer>> rankedMonthly() {
		List<Map.Entry<UUID, Integer>> list = new ArrayList<>(monthly.entrySet());
		list.sort(Comparator
			.comparingInt((Map.Entry<UUID, Integer> entry) -> entry.getValue()).reversed()
			.thenComparing(entry -> nameOf(entry.getKey()), String.CASE_INSENSITIVE_ORDER));
		return list;
	}

	private String nameOf(UUID id) {
		Score score = scores.get(id);
		if (score != null) {
			return score.name();
		}
		Player online = Bukkit.getPlayer(id);
		return online == null ? id.toString().substring(0, 8) : online.getName();
	}

	public List<String> topLines(int limit) {
		List<Map.Entry<UUID, Score>> list = ranked();
		List<String> lines = new ArrayList<>();
		int index = 1;
		for (Map.Entry<UUID, Score> entry : list) {
			if (index > limit) {
				break;
			}
			lines.add(line(index, entry.getValue()));
			index++;
		}
		return lines;
	}

	/** 周榜文本行（命令与自检共用）。 */
	public List<String> weekLines(int limit) {
		List<String> lines = new ArrayList<>();
		int index = 1;
		for (Map.Entry<UUID, Integer> entry : rankedWeekly()) {
			if (index > limit) {
				break;
			}
			lines.add(rankLine(index, nameOf(entry.getKey()), entry.getValue() + " 次"));
			index++;
		}
		return lines;
	}

	/** 月榜文本行（命令与自检共用）。 */
	public List<String> monthLines(int limit) {
		List<String> lines = new ArrayList<>();
		int index = 1;
		for (Map.Entry<UUID, Integer> entry : rankedMonthly()) {
			if (index > limit) {
				break;
			}
			lines.add(rankLine(index, nameOf(entry.getKey()), entry.getValue() + " 次"));
			index++;
		}
		return lines;
	}

	/** 名次行；前三名给金/银/铜色。 */
	private static String line(int index, Score score) {
		return rankLine(index, score.name(), score.count() + " 次");
	}

	private static String rankLine(int index, String name, String value) {
		String color = switch (index) {
			case 1 -> "&6";
			case 2 -> "&7";
			case 3 -> "&c";
			default -> "&e";
		};
		return color + String.format(Locale.ROOT, "%2d", index) + ". &f" + name
			+ " &7- &f" + value;
	}

	public Score scoreOf(Player player) {
		return scores.get(player.getUniqueId());
	}

	public int countOf(Player player) {
		Score score = scores.get(player.getUniqueId());
		return score == null ? 0 : score.count();
	}

	/** 该玩家当前名次；没敲过返回 0。 */
	public int rankOf(Player player) {
		List<Map.Entry<UUID, Score>> list = ranked();
		for (int i = 0; i < list.size(); i++) {
			if (list.get(i).getKey().equals(player.getUniqueId())) {
				return i + 1;
			}
		}
		return 0;
	}

	// ------------------------------------------------------------------ 榜牌

	/** 若榜牌不存在（首次启用、被重建清掉、区块重新加载）就补一块；已经在了就只刷新内容。 */
	public void ensureBoard() {
		if (!plugin.settings().bellEnabled() || !plugin.hubWorld().ready()) {
			return;
		}
		ensureOneBoard(BOARD_TAG, boardPosition(), id -> boardId = id, this::renderBoard);
		ensureOneBoard(WEEKLY_BOARD_TAG, weeklyPosition(), id -> weeklyBoardId = id,
			this::renderWeeklyBoard);
		ensureOneBoard(MONTHLY_BOARD_TAG, monthlyPosition(), id -> monthlyBoardId = id,
			this::renderMonthlyBoard);
	}

	private interface IdSetter {
		void set(UUID id);
	}

	private interface Renderer {
		void render(World world);
	}

	private void ensureOneBoard(String tag, Location position, IdSetter setter, Renderer renderer) {
		World world = plugin.hubWorld().world();
		// 先把榜牌所在的区块点开：world.getEntities() 只覆盖已加载区块，
		// 刚开服时那一带还没加载，扫不到旧牌就会再建一块 —— 重启几次就叠出好几块。
		world.getChunkAt(position.getBlockX() >> 4, position.getBlockZ() >> 4);
		UUID existing = findBoard(world, tag);
		setter.set(existing);
		if (existing == null) {
			setter.set(spawnBoard(world, position, tag));
		}
		renderer.render(world);
	}

	private Location boardPosition() {
		HubConfig config = plugin.settings();
		return new Location(plugin.hubWorld().world(),
			config.bellBoardX(), config.bellBoardY(), config.bellBoardZ());
	}

	private Location weeklyPosition() {
		HubConfig config = plugin.settings();
		return new Location(plugin.hubWorld().world(),
			config.bellWeeklyBoardX(), config.bellWeeklyBoardY(), config.bellWeeklyBoardZ());
	}

	private Location monthlyPosition() {
		HubConfig config = plugin.settings();
		return new Location(plugin.hubWorld().world(),
			config.bellMonthlyBoardX(), config.bellMonthlyBoardY(), config.bellMonthlyBoardZ());
	}

	/** 找出榜牌；顺手把重复的（历史遗留的多块）清掉，只留一块。 */
	private UUID findBoard(World world, String tag) {
		UUID kept = null;
		for (Entity entity : world.getEntities()) {
			if (!(entity instanceof TextDisplay) || !entity.getScoreboardTags().contains(tag)) {
				continue;
			}
			if (kept == null) {
				kept = entity.getUniqueId();
			} else {
				entity.remove();
			}
		}
		return kept;
	}

	private UUID spawnBoard(World world, Location at, String tag) {
		TextDisplay display = world.spawn(at, TextDisplay.class, entity -> {
			entity.addScoreboardTag(tag);
			entity.setPersistent(true);
			entity.setInvulnerable(true);
			entity.setGravity(false);
			entity.setShadowed(true);
			entity.setSeeThrough(false);
			entity.setDefaultBackground(false);
			entity.setBillboard(Display.Billboard.CENTER);
			entity.setAlignment(TextDisplay.TextAlignment.CENTER);
			entity.setLineWidth(220);
			entity.setViewRange(1.0f);
		});
		return display.getUniqueId();
	}

	private void renderBoard(World world) {
		if (boardId == null) {
			return;
		}
		Entity entity = world.getEntity(boardId);
		if (!(entity instanceof TextDisplay display)) {
			boardId = null;
			return;
		}
		display.text(HubText.component(totalBoardText()));
	}

	private void renderWeeklyBoard(World world) {
		if (weeklyBoardId == null) {
			return;
		}
		Entity entity = world.getEntity(weeklyBoardId);
		if (!(entity instanceof TextDisplay display)) {
			weeklyBoardId = null;
			return;
		}
		display.text(HubText.component(periodBoardText("敲钟周排名", rankedWeekly(),
			plugin.settings().bellBoardRows())));
	}

	private void renderMonthlyBoard(World world) {
		if (monthlyBoardId == null) {
			return;
		}
		Entity entity = world.getEntity(monthlyBoardId);
		if (!(entity instanceof TextDisplay display)) {
			monthlyBoardId = null;
			return;
		}
		display.text(HubText.component(periodBoardText("敲钟月排名", rankedMonthly(),
			plugin.settings().bellBoardRows())));
	}

	/** 总数榜牌正文：标题 + 前 N 名；一个都没敲过时给一句引导。 */
	public String totalBoardText() {
		List<Map.Entry<UUID, Score>> list = ranked();
		StringBuilder builder = new StringBuilder("&6&l敲钟总数排名");
		if (list.isEmpty()) {
			builder.append("\n&7还没有人敲过钟，去钟亭敲一下试试");
			return builder.toString();
		}
		return appendRows(builder, list.size(), plugin.settings().bellBoardRows(),
			index -> line(index, list.get(index - 1).getValue()));
	}

	/** 周/月榜牌正文。 */
	private <T> String periodBoardText(String title, List<Map.Entry<UUID, T>> list, int rows) {
		StringBuilder builder = new StringBuilder("&6&l").append(title);
		if (list.isEmpty()) {
			builder.append("\n&7本期还没有记录");
			return builder.toString();
		}
		return appendRows(builder, list.size(), rows, index -> {
			Map.Entry<UUID, T> entry = list.get(index - 1);
			return rankLine(index, nameOf(entry.getKey()), entry.getValue() + " 次");
		});
	}

	private interface RowFactory {
		String row(int index);
	}

	private String appendRows(StringBuilder builder, int total, int rows, RowFactory factory) {
		int limit = Math.max(1, rows);
		int shown = 0;
		for (int index = 1; index <= total && shown < limit; index++, shown++) {
			builder.append('\n').append(factory.row(index));
		}
		if (total > shown) {
			builder.append("\n&7…还有 &f").append(total - shown).append(" &7位");
		}
		return builder.toString();
	}

	// ------------------------------------------------------------------ 周期结算

	/** 启动周期结算检查（每秒查一次水位）。 */
	public void startSettleTask() {
		Bukkit.getScheduler().runTaskTimer(plugin, this::checkSettlement,
			SETTLE_CHECK_TICKS, SETTLE_CHECK_TICKS);
	}

	/** 每秒检查：当前周期标识与水位不同 → 结算 + 重置对应计数。 */
	void checkSettlement() {
		HubConfig config = plugin.settings();
		if (!config.bellEnabled()) {
			return;
		}
		LocalDate today = LocalDate.now(ZoneId.systemDefault());
		String weeklyKey = "W" + today.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY));
		if (!weeklyKey.equals(lastWeeklySettle)) {
			settlePeriod("每周敲钟排名", weeklyKey, rankedWeekly(), config.bellRewardsWeekly());
			weekly.clear();
			lastWeeklySettle = weeklyKey;
			weeklyBoardDirty = true;
			saveDirty = true;
		}
		String monthlyKey = "M" + today.withDayOfMonth(1);
		if (!monthlyKey.equals(lastMonthlySettle)) {
			settlePeriod("每月敲钟排名", monthlyKey, rankedMonthly(), config.bellRewardsMonthly());
			monthly.clear();
			lastMonthlySettle = monthlyKey;
			monthlyBoardDirty = true;
			saveDirty = true;
		}
	}

	/**
	 * 给一个榜单发奖金。
	 *
	 * <p>奖金表 {@code rewards} 按名次给：第 N 名拿 {@code rewards[N-1]}；
	 * 表不够长的高名次就没奖金。**同名次并列**（count 相同）都拿对应名次的奖金，
	 * 不是平分 —— 这是需求方明确要求的。</p>
	 */
	private void settlePeriod(String title, String periodKey,
			List<Map.Entry<UUID, Integer>> ranking, List<Integer> rewards) {
		if (rewards.isEmpty() || ranking.isEmpty()) {
			plugin.getLogger().info(title + " " + periodKey + "：无记录或无奖金表，跳过结算");
			return;
		}
		var economy = Bukkit.getServicesManager().getRegistration(
			com.xiyuan.plugmc.economy.api.EconomyService.class);
		if (economy == null) {
			plugin.getLogger().warning(title + " 结算失败：economy 插件未提供 EconomyService");
			return;
		}
		// 先算出「名次 → 奖金」，同名次并列共用
		Map<Integer, Integer> rankReward = new LinkedHashMap<>();
		int rank = 0;
		int previousCount = -1;
		int previousRank = 0;
		List<RewardLine> lines = new ArrayList<>();
		for (Map.Entry<UUID, Integer> entry : ranking) {
			int count = entry.getValue();
			int myRank = count == previousCount ? previousRank : rank + 1;
			if (count != previousCount) {
				myRank = ++rank;
			} else {
				myRank = previousRank;
			}
			rank = myRank;
			previousCount = count;
			previousRank = myRank;
			int reward = myRank <= rewards.size() ? rewards.get(myRank - 1) : 0;
			rankReward.put(myRank, reward);
			lines.add(new RewardLine(myRank, nameOf(entry.getKey()), entry.getKey(), count, reward));
		}
		// 发钱（离线也能发：LedgerService.deposit 内部会 ensureAccount）
		int paid = 0;
		StringBuilder summary = new StringBuilder();
		for (RewardLine line : lines) {
			if (line.reward() <= 0) {
				continue;
			}
			boolean ok = economy.getProvider().deposit(line.id(), BigDecimal.valueOf(line.reward()),
				"BELL_REWARD_" + periodKey);
			if (ok) {
				paid++;
				summary.append("\n").append(rankLine(line.rank(), line.name(),
					line.count() + " 次 → 奖金 " + line.reward()));
			} else {
				plugin.getLogger().warning(title + " 给 " + line.name() + " 发奖金失败");
			}
		}
		plugin.getLogger().info(title + " " + periodKey + " 结算完成：发出 " + paid + " 份奖金");
		if (paid > 0) {
			Bukkit.broadcast(HubText.component("&6&l" + title + " &7（" + periodKey + "）&a结算完成，"
				+ paid + " 位玩家获得奖金：" + summary));
		}
	}

	/** 手动结算周榜（管理命令用）；自动结算走 checkSettlement。 */
	public void settleWeeklyNow() {
		HubConfig config = plugin.settings();
		settlePeriod("每周敲钟排名", "W手动" + LocalDate.now(ZoneId.systemDefault()),
			rankedWeekly(), config.bellRewardsWeekly());
		weekly.clear();
		weeklyBoardDirty = true;
		saveDirty = true;
		save();
	}

	/** 手动结算月榜（管理命令用）；自动结算走 checkSettlement。 */
	public void settleMonthlyNow() {
		HubConfig config = plugin.settings();
		settlePeriod("每月敲钟排名", "M手动" + LocalDate.now(ZoneId.systemDefault()),
			rankedMonthly(), config.bellRewardsMonthly());
		monthly.clear();
		monthlyBoardDirty = true;
		saveDirty = true;
		save();
	}

	// ------------------------------------------------------------------ 维护

	/** 每秒一次的例行检查：补榜牌、按需重画、按需落盘。 */
	public void tick() {
		if (!plugin.settings().bellEnabled()) {
			return;
		}
		if (boardDirty) {
			ensureBoard();
		}
		long now = System.currentTimeMillis();
		if (saveDirty && now - lastSave >= FLUSH_INTERVAL_MILLIS) {
			save();
		}
	}

	/** 整块榜牌重新生成（改了坐标或被删除时用）。 */
	public void rebuildBoards() {
		World world = plugin.hubWorld().ready() ? plugin.hubWorld().world() : null;
		if (world == null) {
			return;
		}
		for (String tag : List.of(BOARD_TAG, WEEKLY_BOARD_TAG, MONTHLY_BOARD_TAG)) {
			for (Entity entity : world.getEntities()) {
				if (entity instanceof TextDisplay && entity.getScoreboardTags().contains(tag)) {
					entity.remove();
				}
			}
		}
		boardId = null;
		weeklyBoardId = null;
		monthlyBoardId = null;
		ensureBoard();
	}

	/** 兼容旧调用：只重建总数榜（其余两块跟着 ensureBoard 一起补）。 */
	public void rebuildBoard() {
		rebuildBoards();
	}

	public void save() {
		JsonObject root = new JsonObject();
		JsonObject totals = new JsonObject();
		for (Map.Entry<UUID, Score> entry : scores.entrySet()) {
			JsonObject score = new JsonObject();
			score.addProperty("name", entry.getValue().name());
			score.addProperty("count", entry.getValue().count());
			totals.add(entry.getKey().toString(), score);
		}
		root.add("totals", totals);
		JsonObject weeklyJson = new JsonObject();
		for (Map.Entry<UUID, Integer> entry : weekly.entrySet()) {
			weeklyJson.addProperty(entry.getKey().toString(), entry.getValue());
		}
		root.add("weekly", weeklyJson);
		JsonObject monthlyJson = new JsonObject();
		for (Map.Entry<UUID, Integer> entry : monthly.entrySet()) {
			monthlyJson.addProperty(entry.getKey().toString(), entry.getValue());
		}
		root.add("monthly", monthlyJson);
		root.addProperty("lastWeeklySettle", lastWeeklySettle);
		root.addProperty("lastMonthlySettle", lastMonthlySettle);
		try {
			file.getParentFile().mkdirs();
			Files.writeString(file.toPath(), GSON.toJson(root), StandardCharsets.UTF_8);
			saveDirty = false;
			lastSave = System.currentTimeMillis();
		} catch (IOException exception) {
			plugin.getLogger().log(Level.WARNING, "保存敲钟记录失败", exception);
		}
	}

	/** 清空某个玩家的记录；返回是否真的删掉了。 */
	public boolean reset(String name) {
		UUID target = null;
		for (Map.Entry<UUID, Score> entry : scores.entrySet()) {
			if (entry.getValue().name().equalsIgnoreCase(name)) {
				target = entry.getKey();
				break;
			}
		}
		if (target == null) {
			return false;
		}
		scores.remove(target);
		weekly.remove(target);
		monthly.remove(target);
		saveDirty = true;
		boardDirty = true;
		weeklyBoardDirty = true;
		monthlyBoardDirty = true;
		save();
		return true;
	}

	/** 自检用：钟在不在、三块榜牌在不在。 */
	public List<String> verifyLines() {
		List<String> lines = new ArrayList<>();
		HubConfig config = plugin.settings();
		if (!config.bellEnabled()) {
			lines.add("&7敲钟功能已关闭（bell.enabled=false）");
			return lines;
		}
		if (!plugin.hubWorld().ready()) {
			lines.add("&c✘ 主城维度未加载，无法检查敲钟");
			return lines;
		}
		World world = plugin.hubWorld().world();
		Block bell = world.getBlockAt(config.bellX(), config.bellY(), config.bellZ());
		boolean bellOk = bell.getType() == org.bukkit.Material.BELL;
		lines.add((bellOk ? "&a✔ " : "&c✘ ") + "敲钟点 &7(" + config.bellX() + "," + config.bellY() + ","
			+ config.bellZ() + " " + bell.getType().name() + ")");
		boolean totalOk = findBoard(world, BOARD_TAG) != null;
		lines.add((totalOk ? "&a✔ " : "&c✘ ") + "总数榜牌 &7(" + config.bellBoardX() + ","
			+ config.bellBoardY() + "," + config.bellBoardZ() + (totalOk ? " 在位" : " 缺失") + ")");
		boolean weeklyOk = findBoard(world, WEEKLY_BOARD_TAG) != null;
		lines.add((weeklyOk ? "&a✔ " : "&c✘ ") + "周榜牌 &7(" + config.bellWeeklyBoardX() + ","
			+ config.bellWeeklyBoardY() + "," + config.bellWeeklyBoardZ() + (weeklyOk ? " 在位" : " 缺失") + ")");
		boolean monthlyOk = findBoard(world, MONTHLY_BOARD_TAG) != null;
		lines.add((monthlyOk ? "&a✔ " : "&c✘ ") + "月榜牌 &7(" + config.bellMonthlyBoardX() + ","
			+ config.bellMonthlyBoardY() + "," + config.bellMonthlyBoardZ() + (monthlyOk ? " 在位" : " 缺失") + ")");
		lines.add("&7总数：&f" + scores.size() + " 人 / &f"
			+ scores.values().stream().mapToInt(Score::count).sum() + " 次"
			+ " &7本周：&f" + weekly.values().stream().mapToInt(Integer::intValue).sum()
			+ " &7本月：&f" + monthly.values().stream().mapToInt(Integer::intValue).sum());
		if (!totalOk || !weeklyOk || !monthlyOk) {
			Bukkit.getScheduler().runTask(plugin, this::ensureBoard);
		}
		return lines;
	}
}
