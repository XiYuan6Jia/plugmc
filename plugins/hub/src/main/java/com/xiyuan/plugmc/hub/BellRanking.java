package com.xiyuan.plugmc.hub;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * 敲钟计数与排行榜。
 *
 * <p>每次敲响主城钟亭里那口钟，敲的人的计数 +1，成绩落在 {@code bell.yml}。
 * 钟亭背面（远离主城的一侧）挂一块悬浮榜牌，只列敲过钟的人，次数多的排前面。
 */
public final class BellRanking implements Listener {
	/** 榜牌实体的标签，重建/清理时靠它认人。 */
	static final String BOARD_TAG = "plugmc_bell_board";

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Type SCORE_TYPE = new TypeToken<Map<String, Score>>() {
	}.getType();

	/** 一次敲击的计数记录；名字跟着最新一次敲击走，改名后榜上显示新名字。 */
	public record Score(String name, int count) {
	}

	/** 同一玩家两次计数的最小间隔（毫秒）。左右手各触发一次、连着狂点都归到一次。 */
	private static final long MIN_GAP_MILLIS = 200L;
	/** 掉盘节流：最多每 20 tick 重画一次榜牌。 */
	private static final long FLUSH_INTERVAL_MILLIS = 30_000L;

	private final HubPlugin plugin;
	private final File file;
	private final Map<UUID, Score> scores = new LinkedHashMap<>();
	private final Map<UUID, Long> lastRing = new LinkedHashMap<>();
	private UUID boardId;
	private volatile boolean boardDirty = true;
	private volatile boolean saveDirty;
	private long lastSave = System.currentTimeMillis();

	public BellRanking(HubPlugin plugin) {
		this.plugin = plugin;
		this.file = new File(plugin.getDataFolder(), "bell.yml");
	}

	public void load() {
		scores.clear();
		if (!file.isFile()) {
			return;
		}
		try {
			String json = Files.readString(file.toPath(), StandardCharsets.UTF_8);
			Map<String, Score> raw = GSON.fromJson(json, SCORE_TYPE);
			if (raw == null) {
				return;
			}
			for (Map.Entry<String, Score> entry : raw.entrySet()) {
				try {
					UUID id = UUID.fromString(entry.getKey());
					Score score = entry.getValue();
					if (score != null && score.count() > 0) {
						scores.put(id, score);
					}
				} catch (IllegalArgumentException exception) {
					plugin.getLogger().warning("敲钟记录里的 UUID 无效：" + entry.getKey());
				}
			}
			plugin.getLogger().info("已恢复 " + scores.size() + " 条敲钟记录");
		} catch (IOException | RuntimeException exception) {
			plugin.getLogger().log(Level.WARNING, "读取敲钟记录失败", exception);
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
		player.sendActionBar(HubText.component("&6敲钟 &f" + total + " &7次"));
	}

	/** 计入一次并返回该玩家的累计次数。 */
	public int increment(Player player) {
		UUID id = player.getUniqueId();
		Score previous = scores.get(id);
		int count = (previous == null ? 0 : previous.count()) + 1;
		scores.put(id, new Score(player.getName(), count));
		saveDirty = true;
		boardDirty = true;
		return count;
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

	// ------------------------------------------------------------------ 排行

	/** 按次数从多到少；同次数按名字排序，保证两次刷新之间名次不抖。 */
	public List<Map.Entry<UUID, Score>> ranked() {
		List<Map.Entry<UUID, Score>> list = new ArrayList<>(scores.entrySet());
		list.sort(Comparator
			.comparingInt((Map.Entry<UUID, Score> entry) -> entry.getValue().count()).reversed()
			.thenComparing(entry -> entry.getValue().name(), String.CASE_INSENSITIVE_ORDER));
		return list;
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

	/** 名次行；前三名给金/银/铜色。 */
	private static String line(int index, Score score) {
		String color = switch (index) {
			case 1 -> "&6";
			case 2 -> "&7";
			case 3 -> "&c";
			default -> "&e";
		};
		return color + String.format(Locale.ROOT, "%2d", index) + ". &f" + score.name()
			+ " &7- &f" + score.count() + " 次";
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
		World world = plugin.hubWorld().world();
		// 先把榜牌所在的区块点开：world.getEntities() 只覆盖已加载区块，
		// 刚开服时那一带还没加载，扫不到旧牌就会再建一块 —— 重启几次就叠出好几块。
		world.getChunkAt(boardChunkX(), boardChunkZ());
		boardId = findBoard(world);
		if (boardId == null) {
			boardId = spawnBoard(world);
			boardDirty = true;
		}
		if (boardDirty) {
			render(world);
			boardDirty = false;
		}
	}

	/** 找出榜牌；顺手把重复的（历史遗留的多块）清掉，只留一块。 */
	private UUID findBoard(World world) {
		UUID kept = null;
		for (Entity entity : world.getEntities()) {
			if (!(entity instanceof TextDisplay) || !entity.getScoreboardTags().contains(BOARD_TAG)) {
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

	private UUID spawnBoard(World world) {
		HubConfig config = plugin.settings();
		Location at = new Location(world, config.bellBoardX(), config.bellBoardY(), config.bellBoardZ());
		TextDisplay display = world.spawn(at, TextDisplay.class, entity -> {
			entity.addScoreboardTag(BOARD_TAG);
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

	private void render(World world) {
		if (boardId == null) {
			return;
		}
		Entity entity = world.getEntity(boardId);
		if (!(entity instanceof TextDisplay display)) {
			boardId = null;
			boardDirty = true;
			return;
		}
		display.text(HubText.component(boardText()));
	}

	/** 榜牌正文：标题 + 前 N 名；一个都没敲过时给一句引导。 */
	public String boardText() {
		HubConfig config = plugin.settings();
		List<Map.Entry<UUID, Score>> list = ranked();
		StringBuilder builder = new StringBuilder("&6&l敲钟排行榜");
		if (list.isEmpty()) {
			builder.append("\n&7还没有人敲过钟，去钟亭敲一下试试");
			return builder.toString();
		}
		int rows = Math.max(1, config.bellBoardRows());
		int shown = 0;
		for (Map.Entry<UUID, Score> entry : list) {
			if (shown >= rows) {
				break;
			}
			builder.append('\n').append(line(shown + 1, entry.getValue()));
			shown++;
		}
		if (list.size() > shown) {
			builder.append("\n&7…还有 &f").append(list.size() - shown).append(" &7位");
		}
		return builder.toString();
	}

	// ------------------------------------------------------------------ 维护

	/** 每秒一次的例行检查：补榜牌、按需重画、按需落盘。 */
	public void tick() {
		if (!plugin.settings().bellEnabled()) {
			return;
		}
		boolean needRefresh = boardDirty;
		World world = plugin.hubWorld().ready() ? plugin.hubWorld().world() : null;
		if (world != null && boardId == null && world.isChunkLoaded(boardChunkX(), boardChunkZ())) {
			// 榜牌实体只在区块加载时才能查到，所以先确认区块在，免得重复生成
			ensureBoard();
			needRefresh = false;
		}
		if (needRefresh) {
			ensureBoard();
		}
		long now = System.currentTimeMillis();
		if (saveDirty && now - lastSave >= FLUSH_INTERVAL_MILLIS) {
			save();
		}
	}

	private int boardChunkX() {
		return ((int) Math.floor(plugin.settings().bellBoardX())) >> 4;
	}

	private int boardChunkZ() {
		return ((int) Math.floor(plugin.settings().bellBoardZ())) >> 4;
	}

	/** 整块榜牌重新生成（改了坐标或被删除时用）。 */
	public void rebuildBoard() {
		World world = plugin.hubWorld().ready() ? plugin.hubWorld().world() : null;
		if (world == null) {
			return;
		}
		removeBoard(world);
		boardId = null;
		ensureBoard();
	}

	private void removeBoard(World world) {
		if (boardId != null) {
			Entity entity = world.getEntity(boardId);
			if (entity != null) {
				entity.remove();
			}
			boardId = null;
		}
		for (Entity entity : world.getEntities()) {
			if (entity instanceof TextDisplay && entity.getScoreboardTags().contains(BOARD_TAG)) {
				entity.remove();
			}
		}
	}

	public void save() {
		Map<String, Score> raw = new LinkedHashMap<>();
		for (Map.Entry<UUID, Score> entry : scores.entrySet()) {
			raw.put(entry.getKey().toString(), entry.getValue());
		}
		try {
			file.getParentFile().mkdirs();
			Files.writeString(file.toPath(), GSON.toJson(raw, SCORE_TYPE), StandardCharsets.UTF_8);
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
		saveDirty = true;
		boardDirty = true;
		save();
		return true;
	}

	/** 自检用：钟在不在、榜牌在不在。 */
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
		boolean boardOk = findBoard(world) != null;
		lines.add((boardOk ? "&a✔ " : "&c✘ ") + "排行榜牌 &7(" + config.bellBoardX() + ","
			+ config.bellBoardY() + "," + config.bellBoardZ() + (boardOk ? " 在位" : " 缺失") + ")");
		lines.add("&7上榜人数：&f" + scores.size()
			+ " &7累计敲钟：&f" + scores.values().stream().mapToInt(Score::count).sum());
		if (!boardOk) {
			Bukkit.getScheduler().runTask(plugin, this::ensureBoard);
		}
		return lines;
	}
}
