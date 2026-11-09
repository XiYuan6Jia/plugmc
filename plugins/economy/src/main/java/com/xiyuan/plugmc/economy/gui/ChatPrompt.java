package com.xiyuan.plugmc.economy.gui;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 聊天栏输入：借用下一条聊天消息作为 GUI 的文本输入，取消广播并回到主线程处理。
 */
public final class ChatPrompt implements Listener {
	private final JavaPlugin plugin;
	private final Map<UUID, Consumer<String>> pending = new HashMap<>();

	public ChatPrompt(JavaPlugin plugin) {
		this.plugin = plugin;
	}

	public void request(Player player, String prompt, Consumer<String> onInput) {
		pending.put(player.getUniqueId(), onInput);
		player.closeInventory();
		player.sendMessage(MenuText.component("&e" + prompt));
		player.sendMessage(MenuText.component("&7输入 &ccancel &7取消。"));
	}

	public boolean isWaiting(Player player) {
		return pending.containsKey(player.getUniqueId());
	}

	@EventHandler(priority = EventPriority.LOWEST)
	public void onChat(AsyncChatEvent event) {
		Consumer<String> consumer = pending.remove(event.getPlayer().getUniqueId());
		if (consumer == null) {
			return;
		}
		event.setCancelled(true);
		String text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
		Bukkit.getScheduler().runTask(plugin, () -> {
			if (text.isBlank() || text.equalsIgnoreCase("cancel")) {
				event.getPlayer().sendMessage(MenuText.component("&7已取消输入。"));
				return;
			}
			consumer.accept(text);
		});
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		pending.remove(event.getPlayer().getUniqueId());
	}
}
