package com.xiyuan.plugmc.servermenu;

import io.papermc.paper.event.player.AsyncChatEvent;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 菜单内的聊天栏输入：菜单按钮可先让玩家输入一段文本，再执行命令。
 */
public final class ChatPrompt implements Listener {
	private static final long TIMEOUT_MILLIS = 30_000L;

	private final ServerMenuPlugin plugin;
	private final Map<UUID, Pending> pending = new HashMap<>();

	public ChatPrompt(ServerMenuPlugin plugin) {
		this.plugin = plugin;
	}

	public void request(Player player, String prompt, Consumer<String> callback) {
		pending.put(player.getUniqueId(), new Pending(callback, System.currentTimeMillis() + TIMEOUT_MILLIS));
		player.closeInventory();
		player.sendMessage(MenuText.component(prompt));
		player.sendMessage(MenuText.component("&8（30 秒内输入，输入 cancel 取消）"));
	}

	public void cancel(Player player) {
		if (pending.remove(player.getUniqueId()) != null) {
			player.sendMessage(MenuText.component("&7已取消输入。"));
		}
	}

	public boolean awaiting(Player player) {
		return pending.containsKey(player.getUniqueId());
	}

	@EventHandler(priority = EventPriority.LOWEST)
	public void onChat(AsyncChatEvent event) {
		Pending current = pending.remove(event.getPlayer().getUniqueId());
		if (current == null) {
			return;
		}
		event.setCancelled(true);
		String text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
		Player player = event.getPlayer();
		if (current.expired()) {
			player.sendMessage(MenuText.component("&c输入已超时，请重新操作。"));
			return;
		}
		if (text.equalsIgnoreCase("cancel")) {
			player.sendMessage(MenuText.component("&7已取消输入。"));
			return;
		}
		Bukkit.getScheduler().runTask(plugin, () -> current.callback().accept(text));
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		pending.remove(event.getPlayer().getUniqueId());
	}

	private record Pending(Consumer<String> callback, long expiresAt) {
		boolean expired() {
			return System.currentTimeMillis() > expiresAt;
		}
	}
}
