package com.xiyuan.plugmc.train;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.minecart.PoweredMinecart;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 列车编组的维护：哪些矿车连在一起、谁是车头、怎么解开。
 *
 * <p>26.3 的矿车不是 {@code Leashable}（{@code AbstractMinecart} 没实现那个接口），
 * 所以没法用原版拴绳把矿车挂起来。这里的做法是插件自己记连接关系，视觉效果另用粒子画。</p>
 *
 * <p>连接语义按需求：手持拴绳 shift 右键第一个矿车，再 shift 右键第二个矿车，
 * 就把两台连起来并消耗一根拴绳；对已连接的车厢再 shift 右键就解开并还回一根拴绳。
 * 车头必须是动力矿车，且动力矿车只能挂在列车的一端（头或尾），双向列车两端各一个。</p>
 */
public final class TrainManager {
	/** 连接方向：后车连着前车。改完编组只动这一张表。 */
	private final Map<UUID, UUID> towardFront = new ConcurrentHashMap<>();
	/** 最近一次取不到实体的车厢 → 连续取不到的次数，用于区分「没加载」与「真没了」。 */
	private final Map<UUID, Integer> missing = new ConcurrentHashMap<>();
	/** 连续 20 次（约 1 秒）都取不到实体才判定车厢确实没了。 */
	private static final int MISSING_GRACE_TICKS = 20;
	private final TrainPlugin plugin;
	private final java.io.File file;

	public TrainManager(TrainPlugin plugin) {
		this.plugin = plugin;
		this.file = new java.io.File(plugin.getDataFolder(), "consists.yml");
	}

	/** 从磁盘读回编组。矿车 UUID 是持久的，重启后照旧认得出同一列车。 */
	public void load() {
		towardFront.clear();
		if (!file.isFile()) {
			return;
		}
		org.bukkit.configuration.file.YamlConfiguration yml =
			org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
		for (String key : yml.getKeys(false)) {
			String front = yml.getString(key);
			if (front == null || front.isBlank()) {
				continue;
			}
			try {
				towardFront.put(UUID.fromString(key), UUID.fromString(front));
			} catch (IllegalArgumentException ignored) {
				// 手改坏了一行就当没这条，别让整份文件读不出来
			}
		}
	}

	/** 把当前编组写回磁盘。只在真正改动编组时调用。 */
	public void save() {
		org.bukkit.configuration.file.YamlConfiguration yml =
			new org.bukkit.configuration.file.YamlConfiguration();
		for (Map.Entry<UUID, UUID> entry : towardFront.entrySet()) {
			yml.set(entry.getKey().toString(), entry.getValue().toString());
		}
		try {
			yml.save(file);
		} catch (java.io.IOException e) {
			plugin.getLogger().warning("编组文件写不进去：" + e.getMessage());
		}
	}

	/**
	 * 从任意一节车厢出发，收集整列车的成员，顺序固定为「队首 → 队尾」。
	 *
	 * <p>做法是先沿着 towardFront 一路走到没有前车的一节（那就是队首），再顺着反向链走回来。
	 * 这样不管从哪节开始点，得到的排列都一样，便于比较两次编组是否相同。</p>
	 */
	public List<UUID> consistOf(UUID any) {
		UUID head = any;
		Set<UUID> seen = new LinkedHashSet<>();
		while (true) {
			UUID front = towardFront.get(head);
			if (front == null || !seen.add(head)) {
				break;
			}
			head = front;
		}
		List<UUID> line = new ArrayList<>();
		line.add(head);
		UUID cursor = head;
		while (true) {
			UUID next = null;
			for (Map.Entry<UUID, UUID> entry : towardFront.entrySet()) {
				if (entry.getValue().equals(cursor)) {
					next = entry.getKey();
					break;
				}
			}
			if (next == null || line.contains(next)) {
				break;
			}
			line.add(next);
			cursor = next;
		}
		return line;
	}

	public boolean isLinked(UUID cart) {
		return towardFront.containsKey(cart) || towardFront.containsValue(cart);
	}

	/**
	 * 把「另一台/另一列」接到已有列车的车尾，方向由现状决定，点选先后不重要。
	 *
	 * <p>规则只有一条：<b>新车永远接在车尾</b>。四种情况：</p>
	 * <ul>
	 *   <li>两台都还没连：动力矿车当车头，另一台跟在后面；</li>
	 *   <li>只有一台在列车上：把散车接到这列车的车尾；</li>
	 *   <li>两台各在一个列车上：把后者的车头接到前者的车尾；</li>
	 *   <li>本来就在同一列车上：报「已经连在一起」。</li>
	 * </ul>
	 *
	 * <p>早先的版本按「点中的那节是在车头还是车尾」决定挂前面还是挂后面，
	 * 结果想往车头前加一节时会把动力矿车挤到中间，必然撞上
	 * 「动力矿车只能挂在两端」的规则。固定往车尾加就不会有这个问题：
	 * 要往车头加，反向再点一次就是了。</p>
	 *
	 * @return 失败原因；成功返回 null
	 */
	public String attach(Minecart first, Minecart second) {
		if (first.getUniqueId().equals(second.getUniqueId())) {
			return "不能把矿车连到它自己身上。";
		}
		if (!first.getWorld().equals(second.getWorld())) {
			return "两台矿车不在同一个世界里。";
		}
		boolean firstLinked = isLinked(first.getUniqueId());
		boolean secondLinked = isLinked(second.getUniqueId());
		if (firstLinked && secondLinked) {
			if (consistOf(first.getUniqueId()).contains(second.getUniqueId())) {
				return "这两台矿车已经连在一起了。";
			}
			int merged = consistOf(first.getUniqueId()).size() + consistOf(second.getUniqueId()).size();
			if (merged > plugin.settings().maxCars()) {
				return "一节列车最多 " + plugin.settings().maxCars() + " 节车厢。";
			}
		} else if (firstLinked || secondLinked) {
			List<UUID> line = consistOf(firstLinked ? first.getUniqueId() : second.getUniqueId());
			if (line.size() + 1 > plugin.settings().maxCars()) {
				return "一节列车最多 " + plugin.settings().maxCars() + " 节车厢。";
			}
		}

		Map.Entry<UUID, UUID> added;
		if (!firstLinked && !secondLinked) {
			// 两台都是散车：动力矿车当车头
			if (second instanceof PoweredMinecart && !(first instanceof PoweredMinecart)) {
				added = Map.entry(first.getUniqueId(), second.getUniqueId());
			} else {
				added = Map.entry(second.getUniqueId(), first.getUniqueId());
			}
			towardFront.put(added.getKey(), added.getValue());
		} else if (firstLinked != secondLinked) {
			// 一台在列车上、一台是散车：散车接到车尾；但如果散车是动力矿车、
			// 而这一列车还没有车头动力矿车，就把它放到最前面当车头。
			List<UUID> line = consistOf(firstLinked ? first.getUniqueId() : second.getUniqueId());
			UUID loose = firstLinked ? second.getUniqueId() : first.getUniqueId();
			Minecart looseCart = firstLinked ? second : first;
			UUID front = line.get(0);
			if (looseCart instanceof PoweredMinecart && !isPowered(front)) {
				added = Map.entry(front, loose);
			} else {
				added = Map.entry(loose, tailOf(line));
			}
			towardFront.put(added.getKey(), added.getValue());
		} else {
			List<UUID> left = consistOf(first.getUniqueId());
			List<UUID> right = consistOf(second.getUniqueId());
			UUID leftTail = tailOf(left);
			UUID rightHead = right.get(0);
			// 后者的车头如果是动力矿车、而前者车头不是，就把后者整列倒过来接到前面，
			// 免得动力矿车被夹在两段车厢中间。
			if (isPowered(rightHead) && !isPowered(left.get(0)) && isPowered(tailOf(right))) {
				added = Map.entry(left.get(0), tailOf(right));
			} else {
				added = Map.entry(rightHead, leftTail);
			}
			towardFront.put(added.getKey(), added.getValue());
		}

		// 连上之后统一检查一次：动力矿车只允许坐在最前或最后，最多两个
		String error = poweredLayoutError(consistOf(first.getUniqueId()));
		if (error != null) {
			// 只撤掉刚加的那一条，别的连接一概不动
			if (added != null) {
				towardFront.remove(added.getKey(), added.getValue());
			}
			return error;
		}
		save();
		return null;
	}

	private static UUID tailOf(List<UUID> line) {
		return line.get(line.size() - 1);
	}

	/**
	 * 动力矿车的位置检查。
	 *
	 * <p>两条规则合并成一次判断：① 动力矿车只能占队首或队尾；② 最多两个
	 * （一列车两端各一个就已经是双向列车，再多了没有意义）。</p>
	 */
	private String poweredLayoutError(List<UUID> line) {
		int count = 0;
		for (int i = 0; i < line.size(); i++) {
			if (!isPowered(line.get(i))) {
				continue;
			}
			count++;
			if (i != 0 && i != line.size() - 1) {
				return "动力矿车只能挂在列车的最前面或最后面。";
			}
		}
		if (count > 2) {
			return "一列车最多两端各一个动力矿车。";
		}
		return null;
	}

	/**
	 * 在 cart 处解开一节：优先断它和前车之间的连接，没有前车就断它后面那一节。
	 *
	 * <p>这样点列车中间任何一节，都是「这一节连同它后面的整段摘下来」；
	 * 点最前面那一节，则是把它后面挂着的第一节摘掉。</p>
	 *
	 * @return 被摘下来的那一节；没断开任何东西时返回 null
	 */
	public UUID detach(UUID cart) {
		UUID front = towardFront.remove(cart);
		if (front != null) {
			save();
			return cart;
		}
		UUID behind = null;
		for (Map.Entry<UUID, UUID> entry : towardFront.entrySet()) {
			if (entry.getValue().equals(cart)) {
				behind = entry.getKey();
				break;
			}
		}
		if (behind == null) {
			return null;
		}
		towardFront.remove(behind);
		save();
		return behind;
	}

	/** 拆掉一整列车的所有连接，返回被解开的连接数。 */
	public int dissolve(UUID any) {
		List<UUID> line = consistOf(any);
		int removed = 0;
		for (UUID id : line) {
			if (towardFront.remove(id) != null) {
				removed++;
			}
		}
		if (removed > 0) {
			save();
		}
		return removed;
	}

	public boolean isPowered(UUID cart) {
		Entity entity = Bukkit.getEntity(cart);
		return entity instanceof PoweredMinecart;
	}

	/**
	 * 车头：队首若是动力矿车就用它；否则（双向列车）用队尾那台，方向相反。
	 */
	public UUID headOf(UUID any) {
		List<UUID> line = consistOf(any);
		if (line.isEmpty()) {
			return any;
		}
		if (isPowered(line.get(0))) {
			return line.get(0);
		}
		if (isPowered(line.get(line.size() - 1))) {
			return line.get(line.size() - 1);
		}
		return line.get(0);
	}

	/**
	 * 清理已经不存在的实体，避免长期运行后记录膨胀。
	 *
	 * <p>{@code Bukkit.getEntity} 返回 null 有两种可能：真的没了，或者那一片区块
	 * 还没加载 —— 后者在刚开服时很常见。所以 null 不立刻清，先记一笔；
	 * 连续 {@link #MISSING_GRACE_TICKS} 次都取不到才认为真的没了。
	 * 已经取得实体但 {@code isValid()} 为假的，一定是没了，当拍就清。</p>
	 */
	public void prune() {
		int before = towardFront.size();
		Set<UUID> gone = new LinkedHashSet<>();
		towardFront.entrySet().removeIf(entry -> {
			Entity key = Bukkit.getEntity(entry.getKey());
			Entity value = Bukkit.getEntity(entry.getValue());
			if ((key != null && !key.isValid()) || (value != null && !value.isValid())) {
				return true;
			}
			if (key == null || value == null) {
				UUID id = entry.getKey();
				int misses = missing.merge(id, 1, Integer::sum);
				if (misses >= MISSING_GRACE_TICKS) {
					gone.add(id);
					return true;
				}
				return false;
			}
			missing.remove(entry.getKey());
			return false;
		});
		gone.forEach(missing::remove);
		// 只在真的清掉了东西时才写盘：这个方法每 tick 都会跑
		if (towardFront.size() != before) {
			save();
		}
	}

	/** 所有被连接关系提到的车厢 UUID。 */
	public Set<UUID> members() {
		Set<UUID> all = new LinkedHashSet<>(towardFront.keySet());
		all.addAll(towardFront.values());
		return Collections.unmodifiableSet(all);
	}

	public Map<UUID, UUID> snapshot() {
		return Map.copyOf(towardFront);
	}

	/** 某节车厢的前车（沿向车头方向），没有时返回 null。 */
	public UUID frontOf(UUID cart) {
		return towardFront.get(cart);
	}

	/** 某节车厢朝车头方向的单位向量，取它和前车的实际位移；算不出来时返回 null。 */
	public Vector forwardOf(UUID cart) {
		Entity self = Bukkit.getEntity(cart);
		UUID front = towardFront.get(cart);
		Entity ahead = front == null ? null : Bukkit.getEntity(front);
		if (self == null || ahead == null) {
			return null;
		}
		Location a = self.getLocation();
		Location b = ahead.getLocation();
		if (!a.getWorld().equals(b.getWorld())) {
			return null;
		}
		Vector delta = b.toVector().subtract(a.toVector());
		if (delta.lengthSquared() < 1.0e-6) {
			return null;
		}
		return delta.normalize();
	}

	/** 整列车所在的世界（取队首那节），没有可用车厢时返回 null。 */
	public World worldOf(UUID any) {
		for (UUID id : consistOf(any)) {
			Entity entity = Bukkit.getEntity(id);
			if (entity != null) {
				return entity.getWorld();
			}
		}
		return null;
	}
}
