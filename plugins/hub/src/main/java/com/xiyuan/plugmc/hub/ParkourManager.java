package com.xiyuan.plugmc.hub;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * 跑酷玩法：计时、检查点、坠落后回检查点、最佳成绩（parkour.yml 持久化）。
 */
public final class ParkourManager {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Type BEST_TYPE = new TypeToken<Map<String, Best>>() {
	}.getType();

	public record Best(String name, long millis) {
	}

	private record Run(long startedAt, int checkpoint) {
	}

	private final HubPlugin plugin;
	private final File file;
	private final Map<UUID, Run> runs = new HashMap<>();
	private final Map<UUID, Best> bests = new LinkedHashMap<>();
	private volatile ParkourCourse course;

	public ParkourManager(HubPlugin plugin) {
		this.plugin = plugin;
		this.file = new File(plugin.getDataFolder(), "parkour.yml");
	}

	public void course(ParkourCourse course) {
		this.course = course;
	}

	public ParkourCourse course() {
		return course;
	}

	public void load() {
		bests.clear();
		if (!file.isFile()) {
			return;
		}
		try {
			String json = Files.readString(file.toPath(), StandardCharsets.UTF_8);
			Map<String, Best> raw = GSON.fromJson(json, BEST_TYPE);
			if (raw == null) {
				return;
			}
			for (Map.Entry<String, Best> entry : raw.entrySet()) {
				try {
					bests.put(UUID.fromString(entry.getKey()), entry.getValue());
				} catch (IllegalArgumentException exception) {
					plugin.getLogger().warning("跑酷成绩里的 UUID 无效：" + entry.getKey());
				}
			}
			plugin.getLogger().info("已恢复 " + bests.size() + " 条跑酷最佳成绩");
		} catch (IOException | RuntimeException exception) {
			plugin.getLogger().log(Level.WARNING, "读取跑酷成绩失败", exception);
		}
	}

	public boolean running(Player player) {
		return runs.containsKey(player.getUniqueId());
	}

	/** 传送到起点并开始计时。 */
	public void join(Player player) {
		ParkourCourse current = course;
		if (current == null) {
			player.sendMessage(HubText.component("&c跑酷赛道未生成。"));
			return;
		}
		player.teleport(current.startSpawn());
		runs.put(player.getUniqueId(), new Run(System.currentTimeMillis(), 0));
		player.sendMessage(HubText.component("&a计时开始！踩到 &6金色方块 &a记录检查点，踩到 &b钻石块 &a结束。")
			.append(HubText.component(" &8（/hub parkour quit 退出）")));
	}

	public void quit(Player player, boolean backToSpawn) {
		if (runs.remove(player.getUniqueId()) == null) {
			player.sendMessage(HubText.component("&7你当前没有在跑酷。"));
			return;
		}
		player.sendMessage(HubText.component("&7已退出跑酷，本次未记录成绩。"));
		if (backToSpawn && plugin.hubWorld().ready()) {
			player.teleport(plugin.hubWorld().spawn(plugin.settings()));
		}
	}

	public void onLeaveHub(Player player) {
		runs.remove(player.getUniqueId());
	}

	public void onQuit(Player player) {
		runs.remove(player.getUniqueId());
	}

	public void onPad(Player player, ParkourCourse.Pad pad) {
		ParkourCourse current = course;
		if (current == null) {
			return;
		}
		switch (pad.type()) {
			case START -> {
				runs.put(player.getUniqueId(), new Run(System.currentTimeMillis(), 0));
				player.sendMessage(HubText.component("&a计时开始！"));
			}
			case CHECKPOINT -> {
				Run run = runs.get(player.getUniqueId());
				if (run == null || run.checkpoint() >= pad.index()) {
					return;
				}
				runs.put(player.getUniqueId(), new Run(run.startedAt(), pad.index()));
				player.sendMessage(HubText.component("&e检查点 &f" + pad.index() + "/" + current.checkpointCount()
					+ " &7· 用时 " + seconds(System.currentTimeMillis() - run.startedAt())));
			}
			case FINISH -> finish(player, current);
		}
	}

	private void finish(Player player, ParkourCourse current) {
		Run run = runs.get(player.getUniqueId());
		if (run == null) {
			player.sendMessage(HubText.component("&7先踩起点金色方块开始计时再冲线。"));
			return;
		}
		if (run.checkpoint() < current.checkpointCount()) {
			player.sendMessage(HubText.component("&c还差 " + (current.checkpointCount() - run.checkpoint())
				+ " 个检查点，回去补齐后再次冲线（计时继续）。"));
			return;
		}
		runs.remove(player.getUniqueId());
		long millis = System.currentTimeMillis() - run.startedAt();
		Best previous = bests.get(player.getUniqueId());
		boolean record = previous == null || millis < previous.millis();
		if (record) {
			bests.put(player.getUniqueId(), new Best(player.getName(), millis));
			save();
		}
		player.sendMessage(HubText.component("&a完成跑酷！用时 &e" + seconds(millis)
			+ (record ? " &6&l新纪录！" : " &7（最佳 " + seconds(previous.millis()) + "）")));
		if (record && plugin.getConfig().getBoolean("parkour.announce-records", true)) {
			Bukkit.broadcast(HubText.component("&6[跑酷] &f" + player.getName() + " &7刷新了跑酷纪录：&e"
				+ seconds(millis)));
		}
		player.sendMessage(HubText.component("&7再来一次：")
			.append(HubText.component("&a[回到起点]")
				.clickEvent(ClickEvent.runCommand("hub parkour"))
				.hoverEvent(HoverEvent.showText(HubText.component("&7传送到起点并重新计时")))));
	}

	/** 掉出赛道时回到最近检查点，计时继续。 */
	public void onFall(Player player) {
		Run run = runs.get(player.getUniqueId());
		if (run == null || course == null) {
			return;
		}
		player.teleport(course.spawnOfCheckpoint(run.checkpoint()));
		player.sendMessage(HubText.component("&c掉下去了，已送回"
			+ (run.checkpoint() == 0 ? "起点" : "检查点 " + run.checkpoint()) + "，计时继续。"));
	}

	public List<String> topLines(int limit) {
		List<Map.Entry<UUID, Best>> sorted = new ArrayList<>(bests.entrySet());
		sorted.sort(Comparator.comparingLong(entry -> entry.getValue().millis()));
		List<String> lines = new ArrayList<>();
		int index = 1;
		for (Map.Entry<UUID, Best> entry : sorted) {
			if (index > limit) {
				break;
			}
			lines.add("&e" + index + ". &f" + entry.getValue().name() + " &7- &a"
				+ seconds(entry.getValue().millis()));
			index++;
		}
		return lines;
	}

	public String bestOf(Player player) {
		Best best = bests.get(player.getUniqueId());
		return best == null ? "暂无成绩" : seconds(best.millis());
	}

	private void save() {
		Map<String, Best> raw = new LinkedHashMap<>();
		for (Map.Entry<UUID, Best> entry : bests.entrySet()) {
			raw.put(entry.getKey().toString(), entry.getValue());
		}
		try {
			file.getParentFile().mkdirs();
			Files.writeString(file.toPath(), GSON.toJson(raw, BEST_TYPE), StandardCharsets.UTF_8);
		} catch (IOException exception) {
			plugin.getLogger().log(Level.WARNING, "保存跑酷成绩失败", exception);
		}
	}

	private static String seconds(long millis) {
		return String.format(Locale.ROOT, "%.2f 秒", millis / 1000.0);
	}
}
