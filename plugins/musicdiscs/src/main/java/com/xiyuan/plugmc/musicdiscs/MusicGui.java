package com.xiyuan.plugmc.musicdiscs;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 唱片列表界面：左键试听；Shift+左键领取（自己的曲子，或管理员任意曲子）。
 */
public final class MusicGui implements InventoryHolder {
	private static final int PAGE_SIZE = 45;

	private final MusicPlugin plugin;
	private final Inventory inventory;
	private final Map<Integer, Song> songs = new HashMap<>();
	private final int page;

	public MusicGui(MusicPlugin plugin, int page) {
		this.plugin = plugin;
		this.page = Math.max(0, page);
		this.inventory = Bukkit.createInventory(
			this,
			54,
			MusicText.component("&8自定义唱片 &7(" + (this.page + 1) + ")")
		);
		fill();
	}

	public Inventory inventory() {
		return inventory;
	}

	@Override
	public Inventory getInventory() {
		return inventory;
	}

	public Song songAt(int slot) {
		return songs.get(slot);
	}

	public int page() {
		return page;
	}

	private void fill() {
		ItemStack filler = button(Material.GRAY_STAINED_GLASS_PANE, "&8 ", List.of());
		for (int slot = 0; slot < 54; slot++) {
			inventory.setItem(slot, filler.clone());
		}
		List<Song> all = plugin.library().songs();
		int start = page * PAGE_SIZE;
		for (int index = 0; index < PAGE_SIZE && start + index < all.size(); index++) {
			Song song = all.get(start + index);
			songs.put(index, song);
			inventory.setItem(index, icon(song));
		}
		if (page > 0) {
			inventory.setItem(45, button(Material.ARROW, "&e上一页", List.of()));
		}
		if (start + PAGE_SIZE < all.size()) {
			inventory.setItem(53, button(Material.ARROW, "&e下一页", List.of()));
		}
		inventory.setItem(49, button(Material.BARRIER, "&c关闭", List.of()));
		long playerSongs = all.stream().filter(Song::playerSong).count();
		inventory.setItem(4, button(Material.JUKEBOX, "&6共 " + all.size() + " 首自定义唱片", List.of(
			"&7玩家上传：" + playerSongs + " 首",
			"&7左键试听 · Shift+左键领取自己的唱片",
			"&7上传自己的音乐：&f/music upload",
			"&7资源包：" + (plugin.packServer().running() ? "&a已启动" : "&c未启动"),
			"&7数据包：" + (plugin.datapacks().datapackRoot().toFile().isDirectory()
				? "&a已导出" : "&7未导出")
		)));
	}

	private ItemStack icon(Song song) {
		ItemStack stack = plugin.discs().create(song, plugin.settings());
		ItemMeta meta = stack.getItemMeta();
		if (meta != null) {
			meta.lore(MusicText.components(List.of(
				"&7" + (song.author().isEmpty() ? "未知作者" : song.author()),
				"&8ID：" + song.id() + " · " + song.durationText(),
				"&8",
				"&a左键&7 试听",
				"&aShift+左键&7 领取唱片"
			)));
			stack.setItemMeta(meta);
		}
		return stack;
	}

	private ItemStack button(Material material, String name, List<String> lore) {
		ItemStack stack = new ItemStack(material);
		ItemMeta meta = stack.getItemMeta();
		if (meta != null) {
			meta.displayName(MusicText.component(name));
			if (!lore.isEmpty()) {
				meta.lore(MusicText.components(lore));
			}
			stack.setItemMeta(meta);
		}
		return stack;
	}

	/**
	 * 点击处理：返回 true 表示已处理。
	 */
	public static boolean handle(
		MusicPlugin plugin,
		Player player,
		MusicGui gui,
		int slot,
		boolean shift
	) {
		if (slot == 49) {
			player.closeInventory();
			return true;
		}
		if (slot == 45) {
			player.openInventory(new MusicGui(plugin, gui.page() - 1).inventory());
			return true;
		}
		if (slot == 53) {
			player.openInventory(new MusicGui(plugin, gui.page() + 1).inventory());
			return true;
		}
		Song song = gui.songAt(slot);
		if (song == null) {
			return false;
		}
		if (!shift) {
			plugin.playback().play(player, song);
			return true;
		}
		boolean own = song.owner().equals(player.getUniqueId().toString());
		if (!own && !player.hasPermission("music.admin")) {
			player.sendMessage(MusicText.component("&c只能领取自己上传的唱片。"));
			return true;
		}
		if (!player.getInventory().addItem(plugin.discs().create(song, plugin.settings())).isEmpty()) {
			player.sendMessage(MusicText.component("&c背包已满，唱片没有发放成功。"));
			return true;
		}
		player.sendMessage(MusicText.component("&a已获得唱片：&f" + song.displayName()));
		return true;
	}
}
