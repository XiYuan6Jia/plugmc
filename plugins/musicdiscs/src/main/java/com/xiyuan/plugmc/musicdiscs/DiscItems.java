package com.xiyuan.plugmc.musicdiscs;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.JukeboxPlayable;
import net.kyori.adventure.key.Key;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/**
 * 自定义唱片物品。
 *
 * <p>默认使用非原版唱片材质（配置 `disc.material`，默认 PAPER），并通过
 * {@code minecraft:item_model} 组件指向资源包里为每首歌生成的独立模型，
 * 因此<b>不占用、也不覆盖任何原版物品</b>。</p>
 *
 * <p>如果数据包已经注册了同名 {@code jukebox_song}，会同时写入原版
 * {@code jukebox_playable} 组件，这样放进原版唱机也能正常播放。</p>
 */
public final class DiscItems {
	private final JavaPlugin plugin;
	private final NamespacedKey songKey;

	public DiscItems(JavaPlugin plugin) {
		this.plugin = plugin;
		this.songKey = new NamespacedKey(plugin, "song");
	}

	public NamespacedKey songKey() {
		return songKey;
	}

	/**
	 * 物品是否已经是最新格式（材质正确且带 jukebox_playable 组件）。
	 */
	public boolean isUpToDate(ItemStack stack, Song song, MusicConfig config) {
		if (stack == null || stack.getType() != config.discMaterial()) {
			return false;
		}
		if (!hasJukeboxSong(song, config)) {
			return true;
		}
		return stack.getData(DataComponentTypes.JUKEBOX_PLAYABLE) != null;
	}

	public ItemStack create(Song song, MusicConfig config) {
		ItemStack stack = new ItemStack(config.discMaterial());
		ItemMeta meta = stack.getItemMeta();
		if (meta != null) {
			meta.displayName(MusicText.component("&6♪ &f" + song.title()));
			meta.lore(MusicText.components(List.of(
				"&7" + (song.author().isEmpty() ? "未知作者" : song.author()),
				"&8ID：" + song.id() + " · " + song.durationText(),
				"&8",
				"&e右键播放 · 潜行右键停止",
				hasJukeboxSong(song, config) ? "&7也可以放进唱机播放" : "&8（数据包未加载时仅插件播放）"
			)));
			meta.getPersistentDataContainer().set(songKey, PersistentDataType.STRING, song.id());
			stack.setItemMeta(meta);
		}
		stack.setData(DataComponentTypes.MAX_STACK_SIZE, 1);
		if (config.useItemModel()) {
			stack.setData(
				DataComponentTypes.ITEM_MODEL,
				Key.key(config.namespace(), song.itemModel())
			);
		}
		if (hasJukeboxSong(song, config)) {
			JukeboxPlayable playable = JukeboxPlayable.jukeboxPlayable(
				Registry.JUKEBOX_SONG.getOrThrow(jukeboxKey(song, config))
			).build();
			stack.setData(DataComponentTypes.JUKEBOX_PLAYABLE, playable);
		}
		return stack;
	}

	public String songId(ItemStack stack) {
		if (stack == null || stack.getType().isAir()) {
			return null;
		}
		ItemMeta meta = stack.getItemMeta();
		if (meta == null) {
			return null;
		}
		PersistentDataContainer container = meta.getPersistentDataContainer();
		return container.get(songKey, PersistentDataType.STRING);
	}

	public NamespacedKey jukeboxKey(Song song, MusicConfig config) {
		return new NamespacedKey(config.namespace(), song.id());
	}

	public boolean hasJukeboxSong(Song song, MusicConfig config) {
		try {
			return Registry.JUKEBOX_SONG.get(jukeboxKey(song, config)) != null;
		} catch (RuntimeException exception) {
			return false;
		}
	}
}
