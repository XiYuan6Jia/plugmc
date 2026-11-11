package com.xiyuan.plugmc.musicdiscs;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * /music 命令：界面、试听、领唱片、导入、导出资源包与数据包。
 */
public final class MusicCommand implements CommandExecutor, TabCompleter {
	private final MusicPlugin plugin;

	public MusicCommand(MusicPlugin plugin) {
		this.plugin = plugin;
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (args.length == 0) {
			if (sender instanceof Player player && player.hasPermission("music.use")) {
				player.openInventory(new MusicGui(plugin, 0).inventory());
			} else {
				list(sender);
			}
			return true;
		}
		String sub = args[0].toLowerCase(Locale.ROOT);
		switch (sub) {
			case "list", "列表" -> list(sender);
			case "play", "播放" -> {
				if (!(sender instanceof Player player)) {
					sender.sendMessage("只有玩家可以试听唱片。");
					return true;
				}
				if (args.length < 2) {
					player.sendMessage(MusicText.component("&e用法：/music play <ID>"));
					return true;
				}
				Optional<Song> song = plugin.library().find(args[1]);
				if (song.isEmpty()) {
					player.sendMessage(MusicText.component("&c没有这首唱片：" + args[1]));
					return true;
				}
				plugin.playback().play(player, song.get());
			}
			case "stop", "停止" -> {
				if (sender instanceof Player player) {
					if (plugin.playback().isPlaying(player)) {
						plugin.playback().stop(player);
						player.sendMessage(MusicText.component("&7播放已停止。"));
					} else {
						player.sendMessage(MusicText.component("&7你当前没有在播放唱片。"));
					}
				}
			}
			case "give", "领取" -> give(sender, args);
			case "import", "导入" -> importSong(sender, args);
			case "delete", "remove", "删除" -> delete(sender, args);
			case "info", "详情" -> info(sender, args);
			case "reload", "重载" -> reload(sender);
			case "pack", "资源包" -> sendPack(sender, args);
			case "export", "datapack", "导出" -> export(sender);
			case "deleteall", "清空" -> {
				sender.sendMessage(MusicText.component("&7已取消该子命令，避免误删整库。"));
			}
			default -> usage(sender);
		}
		return true;
	}

	private void usage(CommandSender sender) {
		sender.sendMessage(MusicText.component("&6/music 用法："));
		sender.sendMessage(MusicText.component("&f/music &7打开唱片列表"));
		sender.sendMessage(MusicText.component("&f/music play <ID> &7试听 &f/music stop &7停止"));
		sender.sendMessage(MusicText.component("&f/music give [玩家] <ID> &7领取唱片"));
		sender.sendMessage(MusicText.component("&f/music import <ID> [标题] [作者] &7导入 incoming/<ID>.ogg"));
		sender.sendMessage(MusicText.component("&f/music info <ID> &7查看信息 &f/music delete <ID> &7删除"));
		sender.sendMessage(MusicText.component("&f/music pack [玩家] &7下发资源包 &f/music export &7导出数据包"));
		sender.sendMessage(MusicText.component("&f/music reload &7重载配置与唱片库"));
	}

	private void list(CommandSender sender) {
		List<Song> songs = plugin.library().songs();
		if (songs.isEmpty()) {
			sender.sendMessage(MusicText.component("&7唱片库是空的。把 .ogg 放进 &f"
				+ plugin.library().incomingDir() + " &7再执行 &f/music import <ID> <标题> [作者]"));
			return;
		}
		sender.sendMessage(MusicText.component("&6自定义唱片（" + songs.size() + " 首）："));
		for (Song song : songs) {
			sender.sendMessage(MusicText.component("&f- " + song.id() + " &7| &f"
				+ song.displayName() + " &7| " + song.durationText()));
		}
	}

	private void give(CommandSender sender, String[] args) {
		if (!sender.hasPermission("music.give")) {
			sender.sendMessage("你没有领取自定义唱片的权限。");
			return;
		}
		Player target;
		String id;
		if (args.length >= 3) {
			target = Bukkit.getPlayerExact(args[1]);
			id = args[2];
			if (target == null) {
				sender.sendMessage("玩家不在线：" + args[1]);
				return;
			}
		} else if (args.length == 2 && sender instanceof Player player) {
			target = player;
			id = args[1];
		} else {
			sender.sendMessage("用法：/music give [玩家] <ID>");
			return;
		}
		Optional<Song> song = plugin.library().find(id);
		if (song.isEmpty()) {
			sender.sendMessage("没有这首唱片：" + id);
			return;
		}
		if (!target.hasPermission("music.use")) {
			sender.sendMessage("该玩家没有使用自定义唱片的权限。");
			return;
		}
		var leftover = target.getInventory().addItem(plugin.discs().create(song.get(), plugin.settings()));
		if (!leftover.isEmpty()) {
			sender.sendMessage("对方背包已满，唱片没有发放。");
			return;
		}
		target.sendMessage(MusicText.component("&a你收到了唱片：&f" + song.get().displayName()));
		sender.sendMessage("已发放 &f" + song.get().id() + " &7-> &f" + target.getName());
	}

	private void importSong(CommandSender sender, String[] args) {
		if (!sender.hasPermission("music.admin")) {
			sender.sendMessage("你没有导入唱片的权限。");
			return;
		}
		if (args.length < 2) {
			sender.sendMessage("用法：/music import <ID> [标题] [作者] [比较器0-15]");
			sender.sendMessage("请先把 OGG 文件放到：" + plugin.library().incomingDir());
			return;
		}
		String id = args[1].toLowerCase(Locale.ROOT);
		String title = args.length >= 3 ? args[2] : id;
		String author = args.length >= 4 ? args[3] : "";
		int comparator = 1;
		if (args.length >= 5) {
			try {
				comparator = Integer.parseInt(args[4]);
			} catch (NumberFormatException ignored) {
				comparator = 1;
			}
		}
		SongLibrary.ImportResult result = plugin.library().importFromIncoming(
			id,
			title,
			author,
			comparator
		);
		sender.sendMessage(MusicText.component((result.ok() ? "&a" : "&c") + result.message()));
		if (result.ok()) {
			plugin.refreshPack(sender::sendMessage);
		}
	}

	private void delete(CommandSender sender, String[] args) {
		if (!sender.hasPermission("music.admin")) {
			sender.sendMessage("你没有删除唱片的权限。");
			return;
		}
		if (args.length < 2) {
			sender.sendMessage("用法：/music delete <ID>");
			return;
		}
		String id = args[1].toLowerCase(Locale.ROOT);
		if (!plugin.library().delete(id)) {
			sender.sendMessage("没有这首唱片：" + id);
			return;
		}
		sender.sendMessage("已删除唱片 " + id);
		plugin.refreshPack(sender::sendMessage);
	}

	private void info(CommandSender sender, String[] args) {
		if (args.length < 2) {
			sender.sendMessage("用法：/music info <ID>");
			return;
		}
		Optional<Song> found = plugin.library().find(args[1]);
		if (found.isEmpty()) {
			sender.sendMessage("没有这首唱片：" + args[1]);
			return;
		}
		Song song = found.get();
		sender.sendMessage(MusicText.component("&6" + song.id() + " &7| &f" + song.displayName()));
		sender.sendMessage(MusicText.component("&7时长：" + song.durationText()
			+ " &7比较器：" + song.comparator()
			+ " &7模型数据：" + song.customModelData()));
		sender.sendMessage(MusicText.component("&7声音事件：" + song.soundKey(plugin.settings().namespace())));
		sender.sendMessage(MusicText.component("&7数据包注册表："
			+ (plugin.discs().hasJukeboxSong(song, plugin.settings())
				? "&a已加载（可用原版唱机）"
				: "&c未加载（执行 /music export 并重启）")));
		plugin.library().audioFile(song.id()).ifPresent(path ->
			sender.sendMessage(MusicText.component("&7文件：" + path + "（"
				+ path.toFile().length() / 1024 + " KB）")));
	}

	private void reload(CommandSender sender) {
		if (!sender.hasPermission("music.admin")) {
			sender.sendMessage("你没有重载自定义唱片的权限。");
			return;
		}
		plugin.reloadEverything();
		sender.sendMessage("配置与唱片库已重载，共 " + plugin.library().songs().size() + " 首。");
	}

	private void sendPack(CommandSender sender, String[] args) {
		Player target;
		if (args.length >= 2) {
			if (!sender.hasPermission("music.admin")) {
				sender.sendMessage("你没有给其他玩家下发资源包的权限。");
				return;
			}
			target = Bukkit.getPlayerExact(args[1]);
			if (target == null) {
				sender.sendMessage("玩家不在线：" + args[1]);
				return;
			}
		} else if (sender instanceof Player player) {
			target = player;
		} else {
			sender.sendMessage("用法：/music pack [玩家]");
			return;
		}
		plugin.packServer().send(target, plugin.settings());
		sender.sendMessage("已向 " + target.getName() + " 下发资源包。");
	}

	private void export(CommandSender sender) {
		if (!sender.hasPermission("music.admin")) {
			sender.sendMessage("你没有导出数据包的权限。");
			return;
		}
		try {
			DatapackExporter.ExportResult result = plugin.datapacks().export();
			sender.sendMessage("已导出数据包（" + result.songs() + " 首）：" + result.root());
			sender.sendMessage("把整个目录复制到 <世界>/datapacks/ 后重启或 /reload，"
				+ "唱片即可放进原版唱机。");
			for (String warning : result.warnings()) {
				sender.sendMessage("注意：" + warning);
			}
		} catch (IOException exception) {
			sender.sendMessage("导出失败：" + exception.getMessage());
		}
	}

	@Override
	public List<String> onTabComplete(
		CommandSender sender,
		Command command,
		String alias,
		String[] args
	) {
		if (args.length == 1) {
			return filter(
				List.of("list", "play", "stop", "give", "import", "delete", "info", "reload", "pack", "export"),
				args[0]
			);
		}
		if (args.length == 2) {
			String sub = args[0].toLowerCase(Locale.ROOT);
			if (sub.equals("play") || sub.equals("delete") || sub.equals("info")) {
				return filter(ids(), args[1]);
			}
			if (sub.equals("give")) {
				List<String> options = new ArrayList<>(ids());
				Bukkit.getOnlinePlayers().forEach(player -> options.add(player.getName()));
				return filter(options, args[1]);
			}
			if (sub.equals("pack")) {
				return filter(
					Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(),
					args[1]
				);
			}
		}
		if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
			return filter(ids(), args[2]);
		}
		return List.of();
	}

	private List<String> ids() {
		return plugin.library().songs().stream().map(Song::id).toList();
	}

	private List<String> filter(List<String> options, String prefix) {
		String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
		return options.stream()
			.filter(option -> option.toLowerCase(Locale.ROOT).startsWith(lower))
			.toList();
	}
}
