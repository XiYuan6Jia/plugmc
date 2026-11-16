package com.xiyuan.plugmc.train;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.PoweredMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 连接与解开：玩家手持拴绳 shift 右键两台矿车连起来，再 shift 右键已连接的矿车解开。
 *
 * <p>原版拴绳拴不住矿车（矿车不是 Leashable），所以「用拴绳」这件事完全由插件模拟：
 * 连接成功扣一根拴绳、解开还一根，中间用粒子画出绳子。连接不会被拴绳长度拽断，
 * 只有矿车被破坏或者玩家主动解开才会散。</p>
 */
public final class TrainListener implements Listener {
	/** 同一台矿车在极短时间内的重复右键保护（左右手会各触发一次）。 */
	private static final long CLICK_GUARD_MILLIS = 250L;

	private final TrainPlugin plugin;
	/** 每个玩家手上那一节「已经选好的第一台矿车」。 */
	private final Map<UUID, UUID> pending = new HashMap<>();
	/** 上一次右键的目标：只对「同一台矿车」做节流，连着点两台不同车厢不该被吃掉。 */
	private final Map<UUID, String> lastClick = new HashMap<>();
	private int particles = 0;

	public TrainListener(TrainPlugin plugin) {
		this.plugin = plugin;
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onInteract(PlayerInteractEntityEvent event) {
		if (event.getHand() != EquipmentSlot.HAND) {
			return;
		}
		if (!(event.getRightClicked() instanceof Minecart cart)) {
			return;
		}
		Player player = event.getPlayer();
		if (!(player.isSneaking())) {
			return;
		}
		ItemStack hand = player.getInventory().getItemInMainHand();
		boolean holdingLeash = hand.getType() == Material.LEAD;

		long now = System.currentTimeMillis();
		String key = cart.getUniqueId() + "@" + now / CLICK_GUARD_MILLIS;
		String previous = lastClick.put(player.getUniqueId(), key);
		if (key.equals(previous)) {
			return;
		}

		// 顺序很重要：手里已经选中过一节时，这一下永远是「连第二台」，
		// 哪怕目标是已连接的车厢（往列车上挂新车的场景）。只有当没有选中时，
		// 点已连接的车厢才是「解开」。
		UUID first = pending.get(player.getUniqueId());
		if (first != null && first.equals(cart.getUniqueId())) {
			// 又点回刚才选中的那节 —— 当作取消，免得玩家被卡在「选中」状态里出不来
			event.setCancelled(true);
			pending.remove(player.getUniqueId());
			player.sendMessage(TrainText.component("&7已取消选中。"));
			return;
		}
		if (first != null) {
			if (!holdingLeash) {
				return;
			}
			event.setCancelled(true);
			pending.remove(player.getUniqueId());
			Entity firstEntity = org.bukkit.Bukkit.getEntity(first);
			if (!(firstEntity instanceof Minecart head)) {
				player.sendMessage(TrainText.component("&7之前选中那台矿车已经不在了。"));
				return;
			}
			String error = plugin.trains().attach(head, cart);
			if (error != null) {
				player.sendMessage(TrainText.component("&c" + error));
				return;
			}
			consumerLeash(player);
			player.sendMessage(TrainText.component("&a已连接。&7列车现在有 &f"
				+ plugin.trains().consistOf(cart.getUniqueId()).size() + " &7节车厢。"));
			if (plugin.settings().linkParticles()) {
				drawRope(head, cart);
			}
			return;
		}

		if (plugin.trains().isLinked(cart.getUniqueId())) {
			// 没有待连目标，点在已连接的车厢上就是解开这一段
			event.setCancelled(true);
			pending.remove(player.getUniqueId());
			UUID detached = plugin.trains().detach(cart.getUniqueId());
			if (detached == null) {
				plugin.trains().dissolve(cart.getUniqueId());
			}
			plugin.driver().forget(cart.getUniqueId());
			player.sendMessage(TrainText.component("&7已解开这一节车厢的连接。"));
			giveLeash(player);
			return;
		}

		if (!holdingLeash) {
			return;
		}
		event.setCancelled(true);
		pending.put(player.getUniqueId(), cart.getUniqueId());
		player.sendMessage(TrainText.component("&e已选中一节车厢，再 shift 右键另一台矿车连接。"));
	}

	/** 消耗一根拴绳：创造模式和开了 free-in-creative 时不扣。 */
	private void consumerLeash(Player player) {
		if (player.getGameMode() == org.bukkit.GameMode.CREATIVE && plugin.settings().freeInCreative()) {
			return;
		}
		ItemStack hand = player.getInventory().getItemInMainHand();
		if (hand.getType() == Material.LEAD) {
			hand.setAmount(hand.getAmount() - 1);
			player.getInventory().setItemInMainHand(hand.getAmount() <= 0 ? new ItemStack(Material.AIR) : hand);
		}
	}

	/** 还一根拴绳：背包满了就掉在脚下，不会静默吞掉。 */
	private void giveLeash(Player player) {
		if (player.getGameMode() == org.bukkit.GameMode.CREATIVE && plugin.settings().freeInCreative()) {
			return;
		}
		ItemStack leash = new ItemStack(Material.LEAD);
		var leftover = player.getInventory().addItem(leash);
		if (!leftover.isEmpty()) {
			player.getWorld().dropItemNaturally(player.getLocation(), leash);
			player.sendMessage(TrainText.component("&7背包满了，拴绳掉在脚下。"));
		}
	}

	/** 在两台矿车之间打一条粒子绳：沿线均匀撒点。 */
	public void drawRope(Minecart one, Minecart two) {
		Location a = one.getLocation().clone().add(0, 0.4, 0);
		Location b = two.getLocation().clone().add(0, 0.4, 0);
		if (!a.getWorld().equals(b.getWorld())) {
			return;
		}
		double distance = a.distance(b);
		int steps = Math.max(2, (int) Math.round(distance * 3));
		Vector step = b.toVector().subtract(a.toVector()).multiply(1.0 / steps);
		Location cursor = a.clone();
		for (int i = 0; i <= steps; i++) {
			a.getWorld().spawnParticle(Particle.END_ROD, cursor, 1, 0, 0, 0, 0);
			cursor.add(step);
		}
	}

	/** 每秒给已连接的车厢补一次粒子，让绳子看得见。 */
	public void tickParticles() {
		if (!plugin.settings().linkParticles()) {
			return;
		}
		for (UUID id : plugin.trains().members()) {
			Entity entity = org.bukkit.Bukkit.getEntity(id);
			UUID front = plugin.trains().frontOf(id);
			if (entity == null || front == null) {
				continue;
			}
			Entity ahead = org.bukkit.Bukkit.getEntity(front);
			if (ahead == null) {
				continue;
			}
			if (particles++ % 2 == 0 && entity instanceof Minecart self && ahead instanceof Minecart other) {
				drawRope(self, other);
			}
		}
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onDestroy(VehicleDestroyEvent event) {
		if (!(event.getVehicle() instanceof Minecart cart)) {
			return;
		}
		if (!plugin.trains().isLinked(cart.getUniqueId())) {
			return;
		}
		int removed = plugin.trains().dissolve(cart.getUniqueId());
		plugin.driver().forget(cart.getUniqueId());
		if (removed > 0) {
			Entity attacker = event.getAttacker();
			if (attacker instanceof Player player) {
				player.sendMessage(TrainText.component("&7列车有车厢被破坏，编织已解开。"));
			}
		}
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onRemove(EntityRemoveEvent event) {
		if (!(event.getEntity() instanceof Minecart cart)) {
			return;
		}
		if (plugin.trains().isLinked(cart.getUniqueId())) {
			plugin.trains().dissolve(cart.getUniqueId());
			plugin.driver().forget(cart.getUniqueId());
		}
		pending.remove(cart.getUniqueId());
	}

	public void forgetPending(Player player) {
		pending.remove(player.getUniqueId());
	}

	public List<String> pendingNames() {
		return pending.keySet().stream().map(UUID::toString).toList();
	}
}
