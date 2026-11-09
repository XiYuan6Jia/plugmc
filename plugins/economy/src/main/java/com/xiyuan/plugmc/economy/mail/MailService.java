package com.xiyuan.plugmc.economy.mail;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.xiyuan.plugmc.economy.internal.EconomyStore;
import com.xiyuan.plugmc.economy.internal.InventoryUtil;
import com.xiyuan.plugmc.economy.internal.JournalRecord;
import com.xiyuan.plugmc.economy.internal.LedgerService;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public final class MailService {
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

	private final JavaPlugin plugin;
	private final EconomyStore store;
	private final LedgerService ledger;
	private final MailConfig config;

	public MailService(JavaPlugin plugin, EconomyStore store, LedgerService ledger, MailConfig config) {
		this.plugin = plugin;
		this.store = store;
		this.ledger = ledger;
		this.config = config;
	}

	public MailConfig config() {
		return config;
	}

	public boolean canAccept(UUID owner) {
		long count = store.mailEntries().values().stream()
			.filter(dto -> owner.toString().equals(dto.owner))
			.filter(dto -> !dto.claimed)
			.count();
		return count < config.capacity();
	}

	public PreparedMail prepareItem(UUID owner, ItemStack item, String source) {
		long now = System.currentTimeMillis();
		long id = store.nextMailId();
		MailEntry entry = new MailEntry(
			id,
			owner,
			item,
			BigDecimal.ZERO,
			source,
			now,
			config.expiresAt(now),
			false
		);
		JournalRecord.StateOp op = new JournalRecord.StateOp(
			"MAIL_ADD",
			Long.toString(id),
			GSON.toJson(entry.toDto())
		);
		return new PreparedMail(entry, op);
	}

	public boolean sendItem(UUID owner, ItemStack item, String source) {
		if (!canAccept(owner)) {
			return false;
		}
		PreparedMail prepared = prepareItem(owner, item, source);
		ledger.commitState(List.of(prepared.stateOp()), "MAIL_ADD", Long.toString(prepared.entry().id()));
		Player online = plugin.getServer().getPlayer(owner);
		if (online != null) {
			tryAutoClaim(online, prepared.entry().id());
		}
		return true;
	}

	public List<MailEntry> entries(UUID owner) {
		List<MailEntry> result = new ArrayList<>();
		for (EconomyStore.MailEntryDto dto : store.mailEntries().values()) {
			if (!owner.toString().equals(dto.owner) || dto.claimed) {
				continue;
			}
			try {
				result.add(MailEntry.fromDto(dto));
			} catch (RuntimeException exception) {
				plugin.getLogger().warning("邮箱条目无法解析 " + dto.id + "：" + exception.getMessage());
			}
		}
		result.sort(Comparator.comparingLong(MailEntry::createdAt).reversed());
		return result;
	}

	public boolean claim(Player player, long id) {
		EconomyStore.MailEntryDto dto = store.mailEntry(Long.toString(id));
		if (dto == null || !player.getUniqueId().toString().equals(dto.owner) || dto.claimed) {
			player.sendMessage("找不到这封邮件。");
			return false;
		}

		MailEntry entry;
		try {
			entry = MailEntry.fromDto(dto);
		} catch (RuntimeException exception) {
			player.sendMessage("邮件内容损坏，请联系管理员。");
			plugin.getLogger().warning("邮箱条目无法读取 " + id + "：" + exception.getMessage());
			return false;
		}

		if (entry.hasItem()) {
			if (!InventoryUtil.canFit(player, List.of(entry.item()))) {
				player.sendMessage("背包空间不足，无法领取邮件。");
				return false;
			}
			player.getInventory().addItem(entry.item().clone());
		}
		if (entry.money() != null && entry.money().signum() > 0) {
			ledger.deposit(player.getUniqueId(), entry.money(), "MAIL_CLAIM", Long.toString(id));
		}

		boolean removed = ledger.commitState(
			List.of(new JournalRecord.StateOp("MAIL_REMOVE", Long.toString(id), null)),
			"MAIL_CLAIM",
			Long.toString(id)
		);
		if (!removed) {
			plugin.getLogger().warning("邮件 " + id + " 已发放但状态移除失败");
		}
		player.sendMessage("已领取邮件 #" + id + "。");
		return true;
	}

	public int claimAll(Player player) {
		int claimed = 0;
		for (MailEntry entry : entries(player.getUniqueId())) {
			if (!claim(player, entry.id())) {
				break;
			}
			claimed++;
		}
		return claimed;
	}

	public void notify(Player player) {
		long count = entries(player.getUniqueId()).size();
		if (count > 0) {
			player.sendMessage("你有 " + count + " 封未领取的邮件，使用 /mail 查看。");
		}
	}

	public void tryAutoClaim(Player player, long id) {
		EconomyStore.MailEntryDto dto = store.mailEntry(Long.toString(id));
		if (dto == null || dto.claimed) {
			return;
		}
		MailEntry entry;
		try {
			entry = MailEntry.fromDto(dto);
		} catch (RuntimeException exception) {
			return;
		}
		if (entry.hasItem() && !InventoryUtil.canFit(player, List.of(entry.item()))) {
			player.sendMessage("背包空间不足，物品已放入邮箱。");
			return;
		}
		claim(player, id);
	}

	public BukkitTask startExpiryTask() {
		return plugin.getServer().getScheduler().runTaskTimer(
			plugin,
			this::expire,
			20L * 60L * 10L,
			20L * 60L * 10L
		);
	}

	private void expire() {
		long now = System.currentTimeMillis();
		for (EconomyStore.MailEntryDto dto : store.mailEntries().values()) {
			if (dto.expiresAt <= 0 || dto.expiresAt > now) {
				continue;
			}
			Player owner;
			try {
				owner = plugin.getServer().getPlayer(UUID.fromString(dto.owner));
			} catch (RuntimeException exception) {
				continue;
			}
			if (owner == null) {
				continue;
			}
			try {
				MailEntry entry = MailEntry.fromDto(dto);
				if (entry.hasItem() && !InventoryUtil.canFit(owner, List.of(entry.item()))) {
					continue;
				}
				claim(owner, entry.id());
				owner.sendMessage("邮件 #" + entry.id() + " 已到期，已自动领取。");
			} catch (RuntimeException exception) {
				plugin.getLogger().warning("过期邮件处理失败 " + dto.id + "：" + exception.getMessage());
			}
		}
	}

	public record PreparedMail(MailEntry entry, JournalRecord.StateOp stateOp) {
	}
}
