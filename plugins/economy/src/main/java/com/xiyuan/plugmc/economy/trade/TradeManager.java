package com.xiyuan.plugmc.economy.trade;

import com.xiyuan.plugmc.economy.gui.MenuText;
import com.xiyuan.plugmc.economy.internal.InventoryUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 直接交易：请求 → 双方放置物品 → 双方确认 → 原子交换。
 * 物品在确认前始终留在交易窗口，取消或掉线时退回各自背包。
 */
public final class TradeManager implements Listener {
	private static final long REQUEST_TIMEOUT_MS = 60_000L;

	private final JavaPlugin plugin;
	private final Map<UUID, PendingRequest> requests = new HashMap<>();
	private final Map<UUID, TradeSession> sessions = new HashMap<>();

	public TradeManager(JavaPlugin plugin) {
		this.plugin = plugin;
	}

	public void request(Player from, String targetName) {
		Player target = Bukkit.getPlayerExact(targetName);
		if (target == null) {
			from.sendMessage("找不到在线玩家：" + targetName);
			return;
		}
		if (target.equals(from)) {
			from.sendMessage("不能和自己交易。");
			return;
		}
		if (sessions.containsKey(from.getUniqueId())) {
			from.sendMessage("你已经在交易中。");
			return;
		}
		if (sessions.containsKey(target.getUniqueId())) {
			from.sendMessage("对方正在交易中。");
			return;
		}
		requests.put(target.getUniqueId(), PendingRequest.create(from.getUniqueId(), from.getName()));

		from.sendMessage(MenuText.component("&a已向 &e" + target.getName() + " &a发送交易请求。")
			.append(Component.text(" "))
			.append(button("&c[取消申请]", "trade cancel " + target.getName(), "点击撤回这条交易申请")));

		target.sendMessage(MenuText.component("&e" + from.getName() + " &f请求与你交易：")
			.append(Component.text(" "))
			.append(button("&a&l[点击接受]", "trade accept " + from.getName(), "接受后立即打开交易窗口"))
			.append(Component.text(" "))
			.append(button("&c&l[拒绝]", "trade deny " + from.getName(), "拒绝这条交易申请")));
		target.sendMessage(MenuText.component("&7也可以按 &fShift+F &7打开菜单，在「直接交易」里处理（60 秒内有效）。"));
	}

	/**
	 * 聊天栏可点击按钮：点击后执行对应命令，不需要手动输入。
	 */
	private static Component button(String label, String command, String hover) {
		return MenuText.component(label)
			.clickEvent(ClickEvent.runCommand(command))
			.hoverEvent(HoverEvent.showText(MenuText.component("&7" + hover)));
	}

	/**
	 * 取目标玩家当前待处理交易申请的发起者名字，没有则返回 null。
	 */
	public String pendingRequesterName(Player target) {
		PendingRequest request = requests.get(target.getUniqueId());
		if (request == null) {
			return null;
		}
		if (request.expiresAt() < System.currentTimeMillis()) {
			requests.remove(target.getUniqueId());
			return null;
		}
		return request.fromName();
	}

	/**
	 * 拒绝交易申请，并通知发起者。
	 */
	public boolean deny(Player target, String requesterName) {
		PendingRequest request = requests.get(target.getUniqueId());
		if (request == null) {
			target.sendMessage(MenuText.component("&7当前没有待处理的交易申请。"));
			return false;
		}
		if (requesterName != null && !requesterName.isBlank()
			&& !request.fromName().equalsIgnoreCase(requesterName)) {
			target.sendMessage(MenuText.component("&c没有来自 " + requesterName + " 的交易申请。"));
			return false;
		}
		requests.remove(target.getUniqueId());
		target.sendMessage(MenuText.component("&7已拒绝 " + request.fromName() + " 的交易申请。"));
		Player requester = Bukkit.getPlayer(request.from());
		if (requester != null) {
			requester.sendMessage(MenuText.component("&c" + target.getName() + " 拒绝了你的交易申请。"));
		}
		return true;
	}

	/**
	 * 发起者撤回自己发出的申请。
	 */
	public boolean cancelRequest(Player requester, String targetName) {
		for (Map.Entry<UUID, PendingRequest> entry : new HashMap<>(requests).entrySet()) {
			PendingRequest request = entry.getValue();
			if (!request.from().equals(requester.getUniqueId())) {
				continue;
			}
			Player target = Bukkit.getPlayer(entry.getKey());
			if (targetName != null && !targetName.isBlank()
				&& (target == null || !target.getName().equalsIgnoreCase(targetName))) {
				continue;
			}
			requests.remove(entry.getKey());
			requester.sendMessage(MenuText.component("&7已撤回对 "
				+ (target == null ? "对方" : target.getName()) + " 的交易申请。"));
			if (target != null) {
				target.sendMessage(MenuText.component("&7" + requester.getName() + " 撤回了交易申请。"));
			}
			return true;
		}
		requester.sendMessage(MenuText.component("&7没有可撤回的交易申请。"));
		return false;
	}

	public void accept(Player target, String requesterName) {
		PendingRequest request = requests.get(target.getUniqueId());
		if (request == null || request.expiresAt() < System.currentTimeMillis()) {
			requests.remove(target.getUniqueId());
			target.sendMessage("没有有效" + (requesterName == null || requesterName.isBlank()
				? ""
				: "来自 " + requesterName + " 的") + "交易请求。");
			return;
		}
		Player requester = Bukkit.getPlayer(request.from());
		if (requester == null
			|| (requesterName != null && !requesterName.isBlank()
				&& !requester.getName().equalsIgnoreCase(requesterName))) {
			target.sendMessage("发起交易的玩家已离线或请求不匹配。");
			requests.remove(target.getUniqueId());
			return;
		}
		if (sessions.containsKey(target.getUniqueId()) || sessions.containsKey(requester.getUniqueId())) {
			target.sendMessage("双方中有人正在交易中。");
			return;
		}
		requests.remove(target.getUniqueId());

		TradeSession session = new TradeSession(requester, target);
		sessions.put(requester.getUniqueId(), session);
		sessions.put(target.getUniqueId(), session);
		session.render();
		requester.openInventory(session.inventoryOf(requester));
		target.openInventory(session.inventoryOf(target));
		requester.sendMessage("交易已开始，双方把物品放入自己的区域后点击确认。");
		target.sendMessage("交易已开始，双方把物品放入自己的区域后点击确认。");
	}

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onClick(InventoryClickEvent event) {
		if (!(event.getView().getTopInventory().getHolder(false) instanceof TradeSession session)) {
			return;
		}
		if (!(event.getWhoClicked() instanceof Player player) || !session.involves(player)) {
			return;
		}

		int raw = event.getRawSlot();
		int topSize = event.getView().getTopInventory().getSize();
		if (raw < 0) {
			return;
		}
		if (raw >= topSize) {
			if (session.bothConfirmed()) {
				event.setCancelled(true);
			}
			return;
		}

		if (raw == TradeSession.CANCEL_SLOT) {
			event.setCancelled(true);
			cancel(session, player.getName() + " 取消了交易", true);
			return;
		}
		if (raw == TradeSession.CONFIRM_SLOT) {
			event.setCancelled(true);
			toggleConfirm(session, player);
			return;
		}
		if (raw >= TradeSession.OWN_END) {
			event.setCancelled(true);
			return;
		}

		if (session.confirmed(player)) {
			event.setCancelled(true);
			player.sendMessage("请先取消确认再修改物品。");
			return;
		}
		if (event.isShiftClick() && event.getClick().isShiftClick() && raw < TradeSession.OWN_END) {
			// 允许把自己区域内的物品 shift 回背包
			event.setCancelled(false);
		}
		scheduleSync(session, player);
	}

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onDrag(InventoryDragEvent event) {
		if (!(event.getView().getTopInventory().getHolder(false) instanceof TradeSession session)) {
			return;
		}
		if (!(event.getWhoClicked() instanceof Player player) || !session.involves(player)) {
			return;
		}
		for (int slot : event.getRawSlots()) {
			if (slot < event.getView().getTopInventory().getSize()
				&& (slot < TradeSession.OWN_START || slot >= TradeSession.OWN_END)) {
				event.setCancelled(true);
				return;
			}
		}
		if (session.confirmed(player)) {
			event.setCancelled(true);
			player.sendMessage("请先取消确认再修改物品。");
			return;
		}
		scheduleSync(session, player);
	}

	@EventHandler
	public void onClose(InventoryCloseEvent event) {
		if (!(event.getView().getTopInventory().getHolder(false) instanceof TradeSession session)) {
			return;
		}
		if (session.finished()) {
			return;
		}
		if (event.getPlayer() instanceof Player player && session.involves(player)) {
			cancel(session, player.getName() + " 关闭了交易窗口", true);
		}
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		requests.remove(event.getPlayer().getUniqueId());
		TradeSession session = sessions.get(event.getPlayer().getUniqueId());
		if (session != null && !session.finished()) {
			cancel(session, event.getPlayer().getName() + " 离线了", false);
		}
	}

	public void closeAll() {
		for (TradeSession session : List.copyOf(new java.util.LinkedHashSet<>(sessions.values()))) {
			if (!session.finished()) {
				cancel(session, "服务器关闭交易", false);
			}
		}
		requests.clear();
	}

	private void toggleConfirm(TradeSession session, Player player) {
		scheduleSync(session, player);
		session.toggleConfirm(player);
		session.render();
		if (!session.bothConfirmed()) {
			return;
		}
		Player initiator = Bukkit.getPlayer(session.initiator());
		Player partner = Bukkit.getPlayer(session.partner());
		if (initiator == null || partner == null) {
			cancel(session, "对方已离线", true);
			return;
		}
		Bukkit.getScheduler().runTask(plugin, () -> execute(session, initiator, partner));
	}

	private void scheduleSync(TradeSession session, Player player) {
		Bukkit.getScheduler().runTask(plugin, () -> {
			if (!session.finished()) {
				session.syncDisplay(player);
			}
		});
	}

	private void execute(TradeSession session, Player initiator, Player partner) {
		if (session.finished()) {
			return;
		}
		session.syncDisplay(initiator);
		session.syncDisplay(partner);

		List<ItemStack> initiatorItems = session.ownItems(initiator);
		List<ItemStack> partnerItems = session.ownItems(partner);
		if (initiatorItems.isEmpty() && partnerItems.isEmpty()) {
			session.resetConfirmations();
			session.render();
			initiator.sendMessage("双方都没有放入物品，交易已重置。");
			partner.sendMessage("双方都没有放入物品，交易已重置。");
			return;
		}
		if (!InventoryUtil.canFit(initiator, partnerItems) || !InventoryUtil.canFit(partner, initiatorItems)) {
			session.resetConfirmations();
			session.render();
			initiator.sendMessage("背包空间不足，交易已重置，请清理背包后重试。");
			partner.sendMessage("背包空间不足，交易已重置，请清理背包后重试。");
			return;
		}

		session.clearOwn(initiator);
		session.clearOwn(partner);
		session.finish();
		sessions.remove(initiator.getUniqueId());
		sessions.remove(partner.getUniqueId());

		give(partner, initiatorItems);
		give(initiator, partnerItems);
		initiator.closeInventory();
		partner.closeInventory();
		initiator.sendMessage("交易完成。");
		partner.sendMessage("交易完成。");
	}

	private void cancel(TradeSession session, String reason, boolean closeInventories) {
		if (session.finished()) {
			return;
		}
		session.finish();
		sessions.remove(session.initiator());
		sessions.remove(session.partner());

		Player initiator = Bukkit.getPlayer(session.initiator());
		Player partner = Bukkit.getPlayer(session.partner());
		if (initiator != null) {
			give(initiator, session.ownItems(initiator));
			session.clearOwn(initiator);
		}
		if (partner != null) {
			give(partner, session.ownItems(partner));
			session.clearOwn(partner);
		}
		if (closeInventories) {
			if (initiator != null) {
				initiator.closeInventory();
			}
			if (partner != null) {
				partner.closeInventory();
			}
		}
		if (initiator != null) {
			initiator.sendMessage("交易结束：" + reason);
		}
		if (partner != null) {
			partner.sendMessage("交易结束：" + reason);
		}
	}

	private void give(Player player, List<ItemStack> items) {
		for (ItemStack stack : items) {
			Map<Integer, ItemStack> leftover = player.getInventory().addItem(stack.clone());
			if (!leftover.isEmpty()) {
				for (ItemStack drop : leftover.values()) {
					player.getWorld().dropItemNaturally(player.getLocation(), drop);
				}
				player.sendMessage("背包已满，部分物品掉落在脚下。");
			}
		}
	}

	public boolean inSession(Player player) {
		return sessions.containsKey(player.getUniqueId());
	}

	public boolean hasPendingFrom(Player target, Player requester) {
		PendingRequest request = requests.get(target.getUniqueId());
		return request != null
			&& request.from().equals(requester.getUniqueId())
			&& request.expiresAt() >= System.currentTimeMillis();
	}

	private record PendingRequest(UUID from, String fromName, long expiresAt) {
		private static PendingRequest create(UUID from, String fromName) {
			return new PendingRequest(from, fromName, System.currentTimeMillis() + REQUEST_TIMEOUT_MS);
		}
	}
}
