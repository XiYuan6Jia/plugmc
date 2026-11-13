package com.xiyuan.plugmc.hub;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 跑酷排行榜牌。
 *
 * <p>挂在跑酷起点旁边的悬浮文字，第一名给金色。数据直接读 {@link ParkourManager}，
 * 那里只有「跑完过」的人才有记录，没完成过的自然不会出现在榜上。
 *
 * <p>榜牌只负责显示，不参与计时 —— 计时、检查点、成绩落盘都在 ParkourManager 里。
 */
public final class ParkourBoard implements Listener {
	/** 榜牌实体的标签，重建/清理时靠它认人。 */
	static final String BOARD_TAG = "plugmc_parkour_board";

	private final HubPlugin plugin;
	private UUID boardId;
	/** 上一次写进榜牌的正文；一样就不重复下发，省得每 5 秒刷一次实体数据。 */
	private String rendered;

	public ParkourBoard(HubPlugin plugin) {
		this.plugin = plugin;
	}

	/** 榜牌不在就补一块，内容变了就刷新。 */
	public void ensureBoard() {
		if (!plugin.settings().parkourEnabled() || !plugin.hubWorld().ready()) {
			return;
		}
		World world = plugin.hubWorld().world();
		if (boardId == null || world.getEntity(boardId) == null) {
			// 必须先把目标区块点开再扫：world.getEntities() 只覆盖已加载的区块，
			// 刚开服时那一带还没加载，扫不到旧牌就会再建一块 —— 重启几次就叠出好几块。
			world.getChunkAt(chunkX(), chunkZ());
			boardId = findBoard(world);
			if (boardId == null) {
				boardId = spawnBoard(world);
			}
		}
		render(world, true);
	}

	private int chunkX() {
		return (int) Math.floor(plugin.settings().parkourBoardX()) >> 4;
	}

	private int chunkZ() {
		return (int) Math.floor(plugin.settings().parkourBoardZ()) >> 4;
	}

	private UUID findBoard(World world) {
		UUID kept = null;
		for (Entity entity : world.getEntities()) {
			if (!(entity instanceof TextDisplay) || !entity.getScoreboardTags().contains(BOARD_TAG)) {
				continue;
			}
			if (kept == null) {
				kept = entity.getUniqueId();
			} else {
				// 历史遗留的多块：只留第一块，其余清掉
				entity.remove();
			}
		}
		return kept;
	}

	private UUID spawnBoard(World world) {
		HubConfig config = plugin.settings();
		Location at = new Location(world, config.parkourBoardX(), config.parkourBoardY(), config.parkourBoardZ());
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

	/** 每 5 秒对一次：牌丢了就补，内容变了就换。 */
	public void tick() {
		if (!plugin.settings().parkourEnabled() || !plugin.hubWorld().ready()) {
			return;
		}
		World world = plugin.hubWorld().world();
		if (boardId == null) {
			// 实体只在区块加载时才能查到；先确认区块在，免得每轮都新建一块
			int chunkX = (int) Math.floor(plugin.settings().parkourBoardX()) >> 4;
			int chunkZ = (int) Math.floor(plugin.settings().parkourBoardZ()) >> 4;
			if (!world.isChunkLoaded(chunkX, chunkZ)) {
				return;
			}
			ensureBoard();
			return;
		}
		render(world, false);
	}

	/** 有人跑出新纪录后立刻推一次，不用等下一轮 tick。 */
	public void refresh() {
		if (plugin.hubWorld().ready()) {
			render(plugin.hubWorld().world(), true);
		}
	}

	private void render(World world, boolean force) {
		if (boardId == null) {
			return;
		}
		Entity entity = world.getEntity(boardId);
		if (!(entity instanceof TextDisplay display)) {
			boardId = null;
			return;
		}
		String text = boardText();
		if (!force && text.equals(rendered)) {
			return;
		}
		display.text(HubText.component(text));
		rendered = text;
	}

	/** 榜牌正文：标题 + 前 N 名；没人跑完过时给一句引导。 */
	public String boardText() {
		HubConfig config = plugin.settings();
		ParkourManager parkour = plugin.parkour();
		StringBuilder builder = new StringBuilder("&b&l跑酷排行榜");
		if (parkour == null) {
			builder.append("\n&7赛道还没准备好");
			return builder.toString();
		}
		List<String> lines = parkour.topLines(Math.max(1, config.parkourBoardRows()));
		if (lines.isEmpty()) {
			builder.append("\n&7还没有人跑完过，去东边赛道试试");
			return builder.toString();
		}
		for (String line : lines) {
			builder.append('\n').append(line);
		}
		int total = parkour.topLines(Integer.MAX_VALUE).size();
		if (total > lines.size()) {
			builder.append("\n&7…还有 &f").append(total - lines.size()).append(" &7位");
		}
		return builder.toString();
	}

	/** 整块榜牌重新生成（改了坐标或被删除时用）。 */
	public void rebuildBoard() {
		if (!plugin.hubWorld().ready()) {
			return;
		}
		World world = plugin.hubWorld().world();
		for (Entity entity : world.getEntities()) {
			if (entity instanceof TextDisplay && entity.getScoreboardTags().contains(BOARD_TAG)) {
				entity.remove();
			}
		}
		boardId = null;
		rendered = null;
		ensureBoard();
	}

	/** 有玩家进来时顺手补一次：关服期间牌可能随区块卸载没查到。 */
	@EventHandler(priority = EventPriority.MONITOR)
	public void onJoin(PlayerJoinEvent event) {
		if (plugin.settings().parkourEnabled()) {
			refresh();
		}
	}

	/** 自检用：榜牌在不在、上面有没有人。 */
	public List<String> verifyLines() {
		HubConfig config = plugin.settings();
		if (!config.parkourEnabled()) {
			return List.of("&7跑酷已关闭（parkour.enabled=false）");
		}
		if (!plugin.hubWorld().ready()) {
			return List.of("&c✘ 主城维度未加载，无法检查跑酷榜牌");
		}
		World world = plugin.hubWorld().world();
		boolean boardOk = findBoard(world) != null;
		long finishers = plugin.parkour() == null ? 0 : plugin.parkour().topLines(Integer.MAX_VALUE).size();
		return List.of(
			(boardOk ? "&a✔ " : "&c✘ ") + "跑酷榜牌 &7("
				+ String.format(Locale.ROOT, "%.1f,%.1f,%.1f", config.parkourBoardX(),
				config.parkourBoardY(), config.parkourBoardZ())
				+ (boardOk ? " 在位" : " 缺失") + ")",
			"&7完成过的人：&f" + finishers + " &7位"
		);
	}
}
