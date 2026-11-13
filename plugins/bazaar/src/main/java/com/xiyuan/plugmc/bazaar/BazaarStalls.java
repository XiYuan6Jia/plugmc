package com.xiyuan.plugmc.bazaar;

import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 摊位的物理实现：摆装饰方块、放人偶与浮动文字、以及<b>把它们完整还原</b>。
 *
 * <p><b>还原为什么必须做快照</b>：摊位装饰会压到广场铺装（地毯铺在地板层上），
 * 而「交易全部达成后解放摊位并还原装饰」意味着不能留下任何痕迹 ——
 * 所以摆之前把每个被动过的坐标的 {@link BlockData} 原样记下来，解放时逐格写回。
 * 只恢复自己动过的格子，不会影响别人（或别的插件）在同一片区域做的东西。</p>
 *
 * <p>摊位占地 5×3（沿 x 宽 5、沿 z 深 3），装饰布局固定：
 * 后排两根柱子撑一片顶棚、中间站一个人偶、前排三个货台各托一件商品。</p>
 */
final class BazaarStalls {
	/** 用来识别本插件生成的实体，启动时据此清扫上一轮遗留的孤儿。 */
	static final String TAG = "plugmc_bazaar";

	/** 摊位宽（沿 x）与深（沿 z），与 {@link BazaarConfig.PlayerStalls#slots()} 的 spacing 对应。 */
	static final int WIDTH = 5;
	static final int DEPTH = 3;

	private final JavaPlugin plugin;
	private final BazaarConfig config;

	/** 玩家名（小写）→ 当前占用的摊位状态。 */
	private final Map<String, Occupancy> occupied = new LinkedHashMap<>();

	/** 固定摊位（官方/银行/活动），不参与分配，随插件启用一起摆出来。 */
	private final List<Occupancy> fixedStalls = new ArrayList<>();

	BazaarStalls(JavaPlugin plugin, BazaarConfig config) {
		this.plugin = plugin;
		this.config = config;
	}

	/**
	 * 清扫上一轮遗留的实体（崩溃、强杀、/reload 都会留下）。
	 *
	 * <p>只清带 {@link #TAG} 标签的实体 —— 摊位装饰方块在磁盘上仍然存在，
	 * 但实体不属于存档里「该有的东西」，重启后必须由本插件重新生成。</p>
	 */
	int sweepOrphans() {
		int removed = 0;
		World world = world();
		if (world == null) {
			return 0;
		}
		for (Entity entity : world.getEntities()) {
			if (entity.getScoreboardTags().contains(TAG)) {
				entity.remove();
				removed++;
			}
		}
		return removed;
	}

	World world() {
		return Bukkit.getWorld(config.worldName());
	}

	int occupiedCount() {
		return occupied.size();
	}

	/** 哪些玩家目前占着摊位（调试与 /bazaar list 用）。 */
	Map<String, Integer> occupancySummary() {
		Map<String, Integer> out = new LinkedHashMap<>();
		for (Map.Entry<String, Occupancy> entry : occupied.entrySet()) {
			out.put(entry.getValue().displayName, entry.getValue().slotIndex);
		}
		return out;
	}

	/** 自检用：按摊位序号取回占用状态（找不到返回 null）。 */
	Occupancy debugOccupancy(int slotIndex) {
		for (Occupancy state : occupied.values()) {
			if (state.slotIndex == slotIndex) {
				return state;
			}
		}
		return null;
	}

	/**
	 * 让一名玩家占住一个摊位并摆好装饰。
	 *
	 * @param slotIndex 摊位序号（由分配器决定，稳定不变）
	 * @param decor     本次随机挑中的装饰方案
	 */
	void occupy(int slotIndex, BazaarTenants.Tenant tenant, BazaarConfig.Decor decor, int[] slot) {
		String key = key(tenant.name());
		release(key);
		World world = world();
		if (world == null) {
			return;
		}
		Occupancy state = new Occupancy(tenant.name(), slotIndex, decor);
		placeDecor(world, state, slot);
		placeActors(world, state, slot, tenant);
		occupied.put(key, state);
	}

	/** 更新一个已占用摊位的展示内容（只改文字与商品，不动装饰）。 */
	void refresh(int slotIndex, BazaarTenants.Tenant tenant, int[] slot) {
		Occupancy state = occupied.get(key(tenant.name()));
		if (state == null) {
			return;
		}
		World world = world();
		if (world == null) {
			return;
		}
		updateText(state, tenant);
		updateItems(state, tenant, world, slot);
	}

	/** 解放一个摊位：撤掉人偶与商品，把装饰方块逐格还原。 */
	void release(String playerName) {
		Occupancy state = occupied.remove(key(playerName));
		if (state == null) {
			return;
		}
		removeEntities(state);
		restoreBlocks(state);
	}

	/** 全部释放（插件停用、重载配置、重建时用）。 */
	void releaseAll() {
		for (String key : new ArrayList<>(occupied.keySet())) {
			release(key);
		}
		releaseFixed();
	}

	// ---------------------------------------------------------------- 固定摊位

	/**
	 * 摆出固定摊位（官方商店、银行、活动摊）。
	 *
	 * <p>固定摊位与玩家摊位共用同一套占地与装饰布局，区别只有两点：坐标来自配置而不是网格、
	 * 招牌内容是一段固定文案而不是玩家交易。它们同样走 {@link Occupancy} 快照，
	 * 所以重载配置时能干净还原，不会在广场上留下一地柱子。</p>
	 */
	void buildFixed() {
		World world = world();
		if (world == null) {
			return;
		}
		releaseFixed();
		int index = 0;
		for (BazaarConfig.FixedStall fixed : config.fixedStalls()) {
			BazaarConfig.Decor decor = fixedDecor(index++);
			Occupancy state = new Occupancy(fixedLabel(fixed.kind()), -1, decor);
			int[] slot = new int[] {fixed.x(), fixed.z()};
			placeDecor(world, state, slot);
			placeFixedActors(world, state, slot, fixed);
			fixedStalls.add(state);
		}
	}

	private void releaseFixed() {
		for (Occupancy state : fixedStalls) {
			removeEntities(state);
			restoreBlocks(state);
		}
		fixedStalls.clear();
	}

	/** 固定摊位的装饰按顺序取，保证每次启动长得一样（管理员摆建筑时要能对得上）。 */
	private BazaarConfig.Decor fixedDecor(int index) {
		List<BazaarConfig.Decor> decors = config.decors();
		if (decors.isEmpty()) {
			return BazaarConfig.Decor.defaults().get(0);
		}
		return decors.get(index % decors.size());
	}

	/** 固定摊位的招牌：人偶立在台后，头顶一块写着用途的牌子。 */
	private void placeFixedActors(World world, Occupancy state, int[] slot, BazaarConfig.FixedStall fixed) {
		int x0 = slot[0];
		int z0 = slot[1];
		int y = config.floorY();

		Location mannequinAt = new Location(world, x0 + WIDTH / 2.0, y + 1.0, z0 + 1.5);
		Mannequin mannequin = world.spawn(mannequinAt, Mannequin.class, entity -> {
			entity.addScoreboardTag(TAG);
			entity.setImmovable(true);
			entity.setInvulnerable(true);
			entity.setSilent(true);
			entity.setCollidable(false);
			entity.setPersistent(false);
			entity.customName(BazaarText.component("&f" + fixedLabel(fixed.kind())));
			entity.setCustomNameVisible(true);
		});
		state.mannequinId = mannequin.getUniqueId();

		Location textAt = new Location(world, x0 + WIDTH / 2.0, y + 3.2, z0 + 1.5);
		TextDisplay text = world.spawn(textAt, TextDisplay.class, entity -> {
			entity.addScoreboardTag(TAG);
			entity.setPersistent(false);
			entity.setBillboard(Display.Billboard.CENTER);
			entity.setSeeThrough(false);
			entity.setShadowed(true);
			entity.setDefaultBackground(false);
			entity.setBackgroundColor(Color.fromARGB(150, 0, 0, 0));
			entity.setLineWidth(220);
			entity.setAlignment(TextDisplay.TextAlignment.CENTER);
			entity.setViewRange(1.0f);
		});
		state.textId = text.getUniqueId();
		text.text(BazaarText.component(fixedSign(fixed.kind())));
		updateFixedItems(state, world, slot, fixed.kind());
	}

	/** 固定摊位台面上摆的示意物：让玩家一眼看出这里卖什么。 */
	private void updateFixedItems(Occupancy state, World world, int[] slot, String kind) {
		List<Material> sample = switch (kind.toLowerCase(java.util.Locale.ROOT)) {
			case "bank" -> List.of(Material.GOLD_INGOT, Material.EMERALD, Material.DIAMOND);
			case "event" -> List.of(Material.CAKE, Material.FIREWORK_ROCKET, Material.PUMPKIN_PIE);
			default -> List.of(Material.CHEST, Material.EMERALD, Material.BOOK);
		};
		int x0 = slot[0];
		int z0 = slot[1];
		int y = config.floorY();
		for (int i = 0; i < sample.size(); i++) {
			Location at = new Location(world, x0 + 1 + i + 0.5, y + 2.05, z0 + DEPTH - 1 + 0.5);
			ItemDisplay display = world.spawn(at, ItemDisplay.class, entity -> {
				entity.addScoreboardTag(TAG);
				entity.setPersistent(false);
				entity.setBillboard(Display.Billboard.VERTICAL);
				entity.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
				entity.setViewRange(1.0f);
				entity.setTeleportDuration(0);
			});
			display.setItemStack(new ItemStack(sample.get(i)));
			display.setTransformation(spin(i));
			state.itemIds.add(display.getUniqueId());
		}
	}

	/** 固定摊位的用途名（招牌与人偶名牌共用）。 */
	static String fixedLabel(String kind) {
		return switch (kind.toLowerCase(java.util.Locale.ROOT)) {
			case "bank" -> "银行";
			case "event" -> "活动摊";
			case "official" -> "官方商店";
			default -> kind;
		};
	}

	/** 固定摊位的招牌正文：写用途 + 对应的经济系统命令。 */
	private static String fixedSign(String kind) {
		return switch (kind.toLowerCase(java.util.Locale.ROOT)) {
			case "bank" -> "&f&l银行\n&7存取、兑换、质押\n&8/bank 打开";
			case "event" -> "&f&l活动摊\n&7限时活动\n&8公告里看详情";
			case "official" -> "&f&l官方商店\n&7基础物资\n&8/shop 打开";
			default -> "&f&l" + fixedLabel(kind) + "\n&8暂未开放";
		};
	}

	/** 某坐标是否落在固定摊位上（点击提示用）。 */
	String fixedAtBlock(Location location) {
		for (Occupancy state : fixedStalls) {
			if (state.containsBlock(location)) {
				return state.displayName;
			}
		}
		return null;
	}

	/** 某坐标是否被某个摊位占着（点击判定用）。 */
	boolean isStallBlock(Location location) {
		for (Occupancy state : occupied.values()) {
			if (state.containsBlock(location)) {
				return true;
			}
		}
		return false;
	}

	/** 点击某个方块命中了哪个摊主。 */
	String tenantAtBlock(Location location) {
		for (Occupancy state : occupied.values()) {
			if (state.containsBlock(location)) {
				return state.displayName;
			}
		}
		return null;
	}

	/** 点击某个实体（人偶）命中了哪个摊主。 */
	String tenantAtEntity(UUID entityId) {
		for (Occupancy state : occupied.values()) {
			if (entityId.equals(state.mannequinId) || state.itemIds.contains(entityId)) {
				return state.displayName;
			}
		}
		return null;
	}

	// ---------------------------------------------------------------- 装饰

	private void placeDecor(World world, Occupancy state, int[] slot) {
		int x0 = slot[0];
		int z0 = slot[1];
		int y = config.floorY();

		// 地毯：铺在地板层上（会覆盖广场铺装，解放时还原）
		for (int dx = 1; dx <= 3; dx++) {
			for (int dz = 1; dz <= 2; dz++) {
				set(state, world, x0 + dx, y, z0 + dz, state.decor.carpet());
			}
		}
		// 柱：后排两角，三层高
		for (int dy = 1; dy <= 3; dy++) {
			set(state, world, x0, y + dy, z0, state.decor.pillar());
			set(state, world, x0 + WIDTH - 1, y + dy, z0, state.decor.pillar());
		}
		// 顶棚：整片盖住摊位
		for (int dx = 0; dx < WIDTH; dx++) {
			for (int dz = 0; dz < DEPTH; dz++) {
				set(state, world, x0 + dx, y + 4, z0 + dz, state.decor.canopy());
			}
		}
		// 货台：前排三块，用来托商品
		for (int dx = 1; dx <= 3; dx++) {
			set(state, world, x0 + dx, y + 1, z0 + DEPTH - 1, state.decor.base());
		}
	}

	private void set(Occupancy state, World world, int x, int y, int z, String blockData) {
		BlockData data;
		try {
			data = Bukkit.createBlockData(blockData);
		} catch (IllegalArgumentException exception) {
			plugin.getLogger().warning("摊位装饰里的方块写法无效，已跳过：" + blockData);
			return;
		}
		org.bukkit.block.Block block = world.getBlockAt(x, y, z);
		Pos pos = new Pos(x, y, z);
		// 只记第一次见到的原状：同一格被写两次时，快照仍是「最早的原始状态」
		state.snapshot.putIfAbsent(pos, block.getBlockData());
		block.setBlockData(data, false);
	}

	private void restoreBlocks(Occupancy state) {
		World world = world();
		if (world == null) {
			return;
		}
		for (Map.Entry<Pos, BlockData> entry : state.snapshot.entrySet()) {
			Pos pos = entry.getKey();
			world.getBlockAt(pos.x, pos.y, pos.z).setBlockData(entry.getValue(), false);
		}
		state.snapshot.clear();
	}

	// ---------------------------------------------------------------- 人偶与展示

	private void placeActors(World world, Occupancy state, int[] slot, BazaarTenants.Tenant tenant) {
		int x0 = slot[0];
		int z0 = slot[1];
		int y = config.floorY();

		Location mannequinAt = new Location(world, x0 + WIDTH / 2.0 + 0.0, y + 1.0, z0 + 1.5);
		Mannequin mannequin = world.spawn(mannequinAt, Mannequin.class, entity -> {
			entity.addScoreboardTag(TAG);
			entity.setImmovable(true);
			entity.setInvulnerable(true);
			entity.setSilent(true);
			entity.setCollidable(false);
			entity.setPersistent(false);
			entity.setProfile(profileOf(tenant.name()));
			entity.setDescription(BazaarText.component("&f" + tenant.name()));
			entity.customName(BazaarText.component("&f" + tenant.name()));
			entity.setCustomNameVisible(true);
		});
		state.mannequinId = mannequin.getUniqueId();

		Location textAt = new Location(world, x0 + WIDTH / 2.0, y + 3.7, z0 + 1.5);
		TextDisplay text = world.spawn(textAt, TextDisplay.class, entity -> {
			entity.addScoreboardTag(TAG);
			entity.setPersistent(false);
			entity.setBillboard(Display.Billboard.CENTER);
			entity.setSeeThrough(false);
			entity.setShadowed(true);
			entity.setDefaultBackground(false);
			entity.setBackgroundColor(Color.fromARGB(150, 0, 0, 0));
			entity.setLineWidth(220);
			entity.setAlignment(TextDisplay.TextAlignment.CENTER);
			entity.setViewRange(1.0f);
		});
		state.textId = text.getUniqueId();
		updateText(state, tenant);
		updateItems(state, tenant, world, slot);
	}

	private void updateText(Occupancy state, BazaarTenants.Tenant tenant) {
		Entity entity = state.textId == null ? null : Bukkit.getEntity(state.textId);
		if (!(entity instanceof TextDisplay display)) {
			return;
		}
		Component body = BazaarText.component(String.join("\n", lines(tenant)));
		display.text(body);
	}

	private List<String> lines(BazaarTenants.Tenant tenant) {
		List<String> out = new ArrayList<>();
		out.add(BazaarText.headerLine(tenant));
		out.add(BazaarText.kindLine(tenant));
		out.addAll(BazaarText.signLines(config, tenant));
		return out;
	}

	/** 前排三个货台各托一件商品，展示这个摊主的头三笔交易。 */
	private void updateItems(Occupancy state, BazaarTenants.Tenant tenant, World world, int[] slot) {
		int x0 = slot[0];
		int z0 = slot[1];
		int y = config.floorY();
		int want = Math.min(3, tenant.deals().size());
		// 先补齐/裁剪实体数量
		while (state.itemIds.size() < want) {
			int index = state.itemIds.size();
			Location at = new Location(world, x0 + 1 + index + 0.5, y + 2.05, z0 + DEPTH - 1 + 0.5);
			ItemDisplay display = world.spawn(at, ItemDisplay.class, entity -> {
				entity.addScoreboardTag(TAG);
				entity.setPersistent(false);
				entity.setBillboard(Display.Billboard.VERTICAL);
				entity.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
				entity.setViewRange(1.0f);
				entity.setTeleportDuration(0);
			});
			state.itemIds.add(display.getUniqueId());
		}
		while (state.itemIds.size() > want) {
			UUID id = state.itemIds.remove(state.itemIds.size() - 1);
			Entity entity = Bukkit.getEntity(id);
			if (entity != null) {
				entity.remove();
			}
		}
		// 再刷新内容
		for (int i = 0; i < want; i++) {
			Entity entity = Bukkit.getEntity(state.itemIds.get(i));
			if (!(entity instanceof ItemDisplay display)) {
				continue;
			}
			BazaarDeal deal = tenant.deals().get(i);
			display.setItemStack(deal.icon());
			display.setTransformation(spin(i));
		}
	}

	/** 让商品缓缓自转，三个错开相位。 */
	private static Transformation spin(int index) {
		float angle = (float) ((System.currentTimeMillis() / 40.0 + index * 40) % 360.0);
		float radians = (float) Math.toRadians(angle);
		return new Transformation(
			new Vector3f(0f, 0f, 0f),
			new Quaternionf().rotateY(radians),
			new Vector3f(0.45f, 0.45f, 0.45f),
			new Quaternionf()
		);
	}

	private void removeEntities(Occupancy state) {
		removeEntity(state.mannequinId);
		removeEntity(state.textId);
		for (UUID id : state.itemIds) {
			removeEntity(id);
		}
		state.mannequinId = null;
		state.textId = null;
		state.itemIds.clear();
	}

	private void removeEntity(UUID id) {
		if (id == null) {
			return;
		}
		Entity entity = Bukkit.getEntity(id);
		if (entity != null) {
			entity.remove();
		}
	}

	/** 在线玩家用他的真实皮肤；离线玩家用离线档案（服务端解析不出皮肤时就退回默认人偶外观）。 */
	private static ResolvableProfile profileOf(String name) {
		org.bukkit.entity.Player online = Bukkit.getPlayerExact(name);
		com.destroystokyo.paper.profile.PlayerProfile profile =
			online != null ? online.getPlayerProfile() : Bukkit.createProfile(name);
		return ResolvableProfile.resolvableProfile(profile);
	}

	private static String key(String playerName) {
		return playerName == null ? "" : playerName.toLowerCase(java.util.Locale.ROOT);
	}

	/** 方块坐标（{@link Location} 带 yaw/pitch/world，不适合当 Map 键）。 */
	private record Pos(int x, int y, int z) {
	}

	/** 一个已被占用的摊位：装饰方案、方块快照、实体 id。 */
	static final class Occupancy {
		final String displayName;
		final int slotIndex;
		final BazaarConfig.Decor decor;
		final Map<Pos, BlockData> snapshot = new LinkedHashMap<>();
		final List<UUID> itemIds = new ArrayList<>();
		UUID mannequinId;
		UUID textId;

		Occupancy(String displayName, int slotIndex, BazaarConfig.Decor decor) {
			this.displayName = displayName;
			this.slotIndex = slotIndex;
			this.decor = decor;
		}

		UUID textId() {
			return textId;
		}

		boolean containsBlock(Location location) {
			for (Pos pos : snapshot.keySet()) {
				if (pos.x == location.getBlockX()
					&& pos.y == location.getBlockY()
					&& pos.z == location.getBlockZ()) {
					return true;
				}
			}
			return false;
		}
	}
}
