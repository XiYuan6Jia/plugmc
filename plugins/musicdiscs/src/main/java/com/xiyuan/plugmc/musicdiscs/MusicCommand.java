package com.xiyuan.plugmc.musicdiscs;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * /music 命令：界面、试听、领取、玩家上传、导入、导出资源包与数据包。
 */
public final class MusicCommand implements CommandExecutor, TabCompleter {
	private static final List<String> SUBCOMMANDS = List.of(
		"list", "play", "stop", "upload", "uploadtoken", "get", "fix", "mine", "give",
		"import", "delete", "info", "reload", "pack", "export"
	);

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
			case "play", "播放" -> play(sender, args);
			case "stop", "停止" -> stop(sender);
			case "upload", "上传" -> upload(sender);
			case "uploadtoken", "上传码" -> uploadToken(sender, args);
			case "get", "领取" -> get(sender);
			case "fix", "upgrade", "升级" -> fix(sender);
			case "mine", "我的" -> mine(sender);
			case "give", "给予" -> give(sender, args);
			case "import", "导入" -> importSong(sender, args);
			case "delete", "remove", "删除" -> delete(sender, args);
			case "info", "详情" -> info(sender, args);
			case "reload", "重载" -> reload(sender);
			case "pack", "资源包" -> sendPack(sender, args);
			case "export", "datapack", "导出" -> export(sender);
			default -> usage(sender);
		}
		return true;
	}

	private void usage(CommandSender sender) {
		sender.sendMessage(MusicText.component("&6/music 用法："));
		sender.sendMessage(MusicText.component("&f/music &7打开唱片列表"));
		sender.sendMessage(MusicText.component("&f/music upload &7上传自己的唱片（每账号一首）"));
		sender.sendMessage(MusicText.component("&f/music get &7领取自己的唱片 &f/music mine &7查看"));
		sender.sendMessage(MusicText.component("&f/music play <ID> &7试听 &f/music stop &7停止"));
		sender.sendMessage(MusicText.component("&f/music delete <ID> &7删除（自己的或管理员）"));
		if (sender.hasPermission("music.admin")) {
			sender.sendMessage(MusicText.component("&f/music import <ID> [标题] [作者] &7导入 incoming/<ID>.ogg"));
			sender.sendMessage(MusicText.component("&f/music give [玩家] <ID> &7发放"
				+ " &f/music pack [玩家] &7下发资源包"));
			sender.sendMessage(MusicText.component("&f/music export &7导出数据包 &f/music reload &7重载"));
		}
	}

	private void list(CommandSender sender) {
		List<Song> songs = plugin.library().songs();
		if (songs.isEmpty()) {
			sender.sendMessage(MusicText.component("&7唱片库是空的。玩家可用 &f/music upload &7上传自己的唱片。"));
			return;
		}
		sender.sendMessage(MusicText.component("&6自定义唱片（" + songs.size() + " 首）："));
		for (Song song : songs) {
			sender.sendMessage(MusicText.component("&f- " + song.id() + " &7| &f"
				+ song.displayName() + " &7| " + song.durationText()
				+ (song.playerSong() ? " &8(by " + song.ownerName() + ")" : " &8(服务器)")));
		}
	}

	private void play(CommandSender sender, String[] args) {
		if (!(sender instanceof Player player)) {
			sender.sendMessage("只有玩家可以试听唱片。");
			return;
		}
		if (args.length < 2) {
			player.sendMessage(MusicText.component("&e用法：/music play <ID>"));
			return;
		}
		Optional<Song> song = plugin.library().find(args[1]);
		if (song.isEmpty()) {
			player.sendMessage(MusicText.component("&c没有这首唱片：" + args[1]));
			return;
		}
		plugin.playback().play(player, song.get());
	}

	private void stop(CommandSender sender) {
		if (!(sender instanceof Player player)) {
			return;
		}
		if (plugin.playback().isPlaying(player)) {
			plugin.playback().stop(player);
			player.sendMessage(MusicText.component("&7播放已停止。"));
		} else {
			player.sendMessage(MusicText.component("&7你当前没有在播放唱片。"));
		}
	}

	/**
	 * 玩家上传：给出网页地址与一次性上传码。
	 */
	private void upload(CommandSender sender) {
		if (!(sender instanceof Player player)) {
			sender.sendMessage("只有玩家可以上传自己的唱片。");
			return;
		}
		if (!player.hasPermission("music.upload")) {
			player.sendMessage(MusicText.component("&c你没有上传唱片的权限。"));
			return;
		}
		MusicConfig config = plugin.settings();
		Optional<Song> owned = plugin.library().ownedBy(player.getUniqueId());
		if (owned.isPresent()) {
			player.sendMessage(MusicText.component("&e你这个账号已经有唱片了：&f"
				+ owned.get().displayName() + " &7(" + owned.get().durationText() + ")"));
			player.sendMessage(MusicText.component("&7一个账号限一首，先 &f/music delete "
				+ owned.get().id() + " &7再上传新的。"));
			return;
		}
		String token = plugin.uploads().createToken(player, config);
		if (token == null) {
			player.sendMessage(MusicText.component("&c上传码生成失败，请稍后再试。"));
			return;
		}
		long minutes = Math.max(1, plugin.uploads().tokenSecondsLeft(token) / 60);
		player.sendMessage(MusicText.component("&6请在浏览器打开：&f" + plugin.publicBaseUrl()));
		player.sendMessage(MusicText.component("&6上传码：&e&l" + token
			+ " &7（" + minutes + " 分钟内有效，只能用一次）"));
		player.sendMessage(MusicText.component("&7限制：&f" + (config.uploadMaxBytes() / 1024 / 1024)
			+ " MB &7以内、时长 &f" + MusicText.duration(config.uploadMaxSeconds())
			+ " &7以内的 &fOGG Vorbis &7文件，每账号限一首。"));
	}

	/**
	 * 管理员为其他玩家生成上传码（玩家不方便自己执行时使用）。
	 */
	private void uploadToken(CommandSender sender, String[] args) {
		if (!sender.hasPermission("music.admin")) {
			sender.sendMessage("你没有生成上传码的权限。");
			return;
		}
		if (args.length < 2) {
			sender.sendMessage("用法：/music uploadtoken <玩家>");
			return;
		}
		Player target = Bukkit.getPlayerExact(args[1]);
		UUID playerId;
		String playerName;
		if (target != null) {
			playerId = target.getUniqueId();
			playerName = target.getName();
		} else {
			org.bukkit.OfflinePlayer offline = Bukkit.getOfflinePlayer(args[1]);
			if (!offline.hasPlayedBefore()) {
				sender.sendMessage("找不到这个玩家（需要至少进服过一次）：" + args[1]);
				return;
			}
			playerId = offline.getUniqueId();
			playerName = offline.getName() == null ? args[1] : offline.getName();
		}
		Optional<Song> owned = plugin.library().ownedBy(playerId);
		if (owned.isPresent()) {
			sender.sendMessage(playerName + " 已经有唱片了：" + owned.get().id());
			return;
		}
		MusicConfig config = plugin.settings();
		String token = plugin.uploads().createToken(playerId, playerName, config);
		sender.sendMessage("已为 " + playerName + " 生成上传码：" + token
			+ "（" + Math.max(1, plugin.uploads().tokenSecondsLeft(token) / 60) + " 分钟内有效）");
		sender.sendMessage("上传页面：" + plugin.publicBaseUrl());
	}

	private void mine(CommandSender sender) {		if (!(sender instanceof Player player)) {
			sender.sendMessage("只有玩家可以查看自己的唱片。");
			return;
		}
		Optional<Song> owned = plugin.library().ownedBy(player.getUniqueId());
		if (owned.isEmpty()) {
			player.sendMessage(MusicText.component("&7你还没有唱片，用 &f/music upload &7上传一首。"));
			return;
		}
		Song song = owned.get();
		player.sendMessage(MusicText.component("&6你的唱片：&f" + song.displayName()
			+ " &7(" + song.durationText() + "，ID " + song.id() + ")"));
		player.sendMessage(MusicText.component("&7/music get 领取 · /music play " + song.id()
			+ " 试听 · /music delete " + song.id() + " 删除"));
	}

	private void get(CommandSender sender) {
		if (!(sender instanceof Player player)) {
			sender.sendMessage("只有玩家可以领取唱片。");
			return;
		}
		Optional<Song> owned = plugin.library().ownedBy(player.getUniqueId());
		if (owned.isEmpty()) {
			player.sendMessage(MusicText.component("&7你还没有唱片，用 &f/music upload &7上传一首。"));
			return;
		}
		Song song = owned.get();
		if (countDisc(player, song.id()) > 0) {
			player.sendMessage(MusicText.component("&7你背包里已经有一张这张唱片了。"));
			return;
		}
		if (!player.getInventory().addItem(plugin.discs().create(song, plugin.settings())).isEmpty()) {
			player.sendMessage(MusicText.component("&c背包已满，请先清理背包。"));
			return;
		}
		player.sendMessage(MusicText.component("&a已领取唱片：&f" + song.displayName()));
	}

	/**
	 * 把背包里所有旧版自定义唱片升级成新物品（带 jukebox_playable 组件）。
	 */
	private void fix(CommandSender sender) {
		if (!(sender instanceof Player player)) {
			sender.sendMessage("只有玩家可以升级自己的唱片。");
			return;
		}
		int fixed = 0;
		ItemStack[] contents = player.getInventory().getStorageContents();
		for (int slot = 0; slot < contents.length; slot++) {
			ItemStack stack = contents[slot];
			String id = plugin.discs().songId(stack);
			if (id == null) {
				continue;
			}
			Optional<Song> song = plugin.library().find(id);
			if (song.isEmpty() || plugin.discs().isUpToDate(stack, song.get(), plugin.settings())) {
				continue;
			}
			player.getInventory().setItem(slot, plugin.discs().create(song.get(), plugin.settings()));
			fixed++;
		}
		if (fixed == 0) {
			player.sendMessage(MusicText.component("&7背包里没有需要升级的唱片。"));
			return;
		}
		player.sendMessage(MusicText.component("&a已升级 &f" + fixed + " &a张唱片，现在可以放进原版唱机。"));
	}

	private int countDisc(Player player, String songId) {
		int count = 0;
		for (var stack : player.getInventory().getStorageContents()) {
			if (songId.equals(plugin.discs().songId(stack))) {
				count += stack.getAmount();
			}
		}
		return count;
	}

	private void give(CommandSender sender, String[] args) {
		if (!sender.hasPermission("music.admin")) {
			sender.sendMessage("你没有发放自定义唱片的权限。");
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
			comparator,
			plugin.settings()
		);
		sender.sendMessage(MusicText.component((result.ok() ? "&a" : "&c") + result.message()));
		if (result.ok()) {
			plugin.refreshPack(sender::sendMessage);
		}
	}

	private void delete(CommandSender sender, String[] args) {
		if (args.length < 2) {
			sender.sendMessage("用法：/music delete <ID>");
			return;
		}
		String id = args[1].toLowerCase(Locale.ROOT);
		Optional<Song> song = plugin.library().find(id);
		if (song.isEmpty()) {
			sender.sendMessage("没有这首唱片：" + id);
			return;
		}
		boolean admin = sender.hasPermission("music.admin");
		boolean owner = sender instanceof Player player
			&& song.get().owner().equals(player.getUniqueId().toString());
		if (!admin && !owner) {
			sender.sendMessage("这不是你的唱片，无法删除。");
			return;
		}
		if (!plugin.library().delete(id)) {
			sender.sendMessage("删除失败，唱片可能已经不存在。");
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
			+ (song.playerSong() ? " &7上传者：" + song.ownerName() : " &7来源：服务器")));
		sender.sendMessage(MusicText.component("&7声音事件：" + song.soundKey(plugin.settings().namespace())
			+ " &7物品模型：" + (plugin.settings().useItemModel()
				? plugin.settings().namespace() + ":" + song.itemModel() : "未启用")));
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
			return filter(SUBCOMMANDS, args[0]);
		}
		if (args.length == 2) {
			String sub = args[0].toLowerCase(Locale.ROOT);
			if (sub.equals("play") || sub.equals("delete") || sub.equals("info")) {
				return filter(ids(), args[1]);
			}
			if (sub.equals("give") || sub.equals("pack")) {
				List<String> options = new ArrayList<>(ids());
				Bukkit.getOnlinePlayers().forEach(player -> options.add(player.getName()));
				return filter(options, args[1]);
			}
			if (sub.equals("uploadtoken")) {
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
