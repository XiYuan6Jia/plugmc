package com.xiyuan.plugmc.bazaar;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 自检：真的把一个摊位摆出来、刷新一遍、再解放，逐项核对「该出现的出现了、该还原的还原了」。
 *
 * <p>为什么要在服务器里跑而不是单元测试：摊位用到的是实体生成、方块写入、快照还原这些
 * <b>只有真实世界才有的行为</b>。尤其是「解放后广场铺装必须恢复原样」这一条，
 * 光靠读代码看不出有没有漏格。</p>
 *
 * <p>自检用的摊主数据是临时造的，跑完立即释放并核对方块还原 —— 不会在商业街上留下任何痕迹，
 * 也不会写进经济系统的账目。</p>
 */
final class BazaarSelfTest {
	private final BazaarPlugin plugin;
	private final BazaarStalls stalls;

	BazaarSelfTest(BazaarPlugin plugin, BazaarStalls stalls) {
		this.plugin = plugin;
		this.stalls = stalls;
	}

	/** @return 每一项的检查结果（名称 + 是否通过 + 说明） */
	List<String> run() {
		List<String> report = new ArrayList<>();
		BazaarConfig config = plugin.config();
		World world = Bukkit.getWorld(config.worldName());
		if (world == null) {
			report.add("&c× 世界 " + config.worldName() + " 没加载，跳过");
			return report;
		}
		List<int[]> slots = config.playerStalls().slots();
		if (slots.isEmpty()) {
			report.add("&c× 摊位区没有配置（stalls.player 的 x1/z1 要大于 x0/z0）");
			return report;
		}

		int[] slot = slots.get(0);
		BazaarConfig.Decor decor = config.decors().isEmpty()
			? BazaarConfig.Decor.defaults().get(0)
			: config.decors().get(0);

		// 摆之前先把这一片方块原样记下来，跑完逐格对比
		Map<String, BlockData> before = snapshotArea(world, slot, config.floorY());

		BazaarTenants.Tenant tenant = syntheticTenant();
		stalls.occupy(0, tenant, decor, slot);

		// 1) 装饰方块是否真的写进了世界
		String pillar = decor.pillar().split("\\[", 2)[0];
		BlockData atPillar = world.getBlockAt(slot[0], config.floorY() + 1, slot[1]).getBlockData();
		boolean pillarOk = atPillar.getAsString(false).equals(
			Bukkit.createBlockData(pillar).getAsString(false));
		report.add(mark(pillarOk, "摊位柱子已摆出", atPillar.getAsString(false)));

		// 2) 人偶与招牌是否生成
		boolean mannequin = hasEntity(world, Mannequin.class, slot);
		report.add(mark(mannequin, "玩家模型已生成（人偶）", mannequin ? "1 个" : "0 个"));

		// 2b) 名牌只有一份名字：人偶出厂自带一段描述（原版文本是 "NPC"），不藏起来会和 CustomName 叠着画
		String description = mannequinDescription(world, slot);
		boolean descHidden = mannequin && description == null;
		report.add(mark(descHidden, "名牌只剩一份名字",
			description == null ? "描述已隐藏" : "描述=" + description));

		// 2c) 人偶脚底贴着地毯，不是浮在地毯上方一格
		//     地毯只有 1/16 格高，人偶若按"整方块顶面"算就会悬空将近一格
		double mannequinY = mannequinY(world, slot);
		double footY = carpetTopY(world, slot, config.floorY());
		boolean standing = mannequin && Math.abs(mannequinY - footY) < 0.02;
		report.add(mark(standing, "人偶站在地毯上",
			mannequin ? String.format(java.util.Locale.ROOT, "脚底 %.3f / 地毯面 %.3f", mannequinY, footY) : "没人偶"));

		// 3) 招牌是否带上了交易条目
		String sign = signText(stateOf(0));
		boolean signOk = sign != null && sign.contains(tenant.name());
		report.add(mark(signOk, "招牌已写入摊主与交易", sign == null ? "无招牌" : oneLine(sign)));

		// 4) 商品展示实体数量与交易数一致（只数这个摊位范围内的，固定摊位的不算）
		int shown = Math.min(3, tenant.deals().size());
		int items = countItemDisplays(world, slot, config.floorY());
		report.add(mark(items >= shown, "货台商品已就位", items + " / " + shown));

		// 5) 刷新后内容跟着变：同一位摊主换了挂单（真实场景就是他又上/下架了一件）
		BazaarTenants.Tenant updated = syntheticTenantUpdated();
		stalls.refresh(0, updated, slot);
		String refreshed = signText(stateOf(0));
		boolean refreshOk = refreshed != null && refreshed.contains("青金石");
		report.add(mark(refreshOk, "刷新后招牌跟着交易变", oneLine(String.valueOf(refreshed))));

		// 6) 解放后实体全清、方块逐格还原
		stalls.release(tenant.name());
		boolean gone = !hasEntity(world, Mannequin.class, slot);
		report.add(mark(gone, "解放后摊位实体已撤除", gone ? "0 个" : "仍有残留"));

		Map<String, BlockData> after = snapshotArea(world, slot, config.floorY());
		int diff = 0;
		for (Map.Entry<String, BlockData> entry : before.entrySet()) {
			BlockData now = after.get(entry.getKey());
			if (now == null || !now.getAsString(false).equals(entry.getValue().getAsString(false))) {
				diff++;
			}
		}
		report.add(mark(diff == 0, "装饰已还原（广场铺装恢复原样）",
			diff == 0 ? before.size() + " 格全部一致" : diff + " 格没还原"));

		// 8) 固定摊位是否等距排列：一排摊位疏密不齐看着就像没摆完
		report.add(fixedSpacing(config));
		return report;
	}

	/**
	 * 检查固定摊位沿 x 的间距是否一致。
	 *
	 * <p>官方商店/银行/活动摊是手工填坐标的，漏改一个就会一排摊疏密不齐。
	 * 这项只在有三个及以上固定摊位、且它们都在同一条 z 上时才有意义。</p>
	 */
	private static String fixedSpacing(BazaarConfig config) {
		List<BazaarConfig.FixedStall> fixed = config.fixedStalls();
		if (fixed.size() < 3) {
			return mark(true, "固定摊位间距一致", "只有 " + fixed.size() + " 个，不检查");
		}
		List<Integer> xs = new ArrayList<>();
		int z = fixed.get(0).z();
		for (BazaarConfig.FixedStall stall : fixed) {
			if (stall.z() != z) {
				return mark(true, "固定摊位间距一致", "不在同一排，不检查");
			}
			xs.add(stall.x());
		}
		java.util.Collections.sort(xs);
		List<Integer> gaps = new ArrayList<>();
		for (int i = 1; i < xs.size(); i++) {
			gaps.add(xs.get(i) - xs.get(i - 1));
		}
		boolean even = gaps.stream().distinct().count() == 1;
		return mark(even, "固定摊位间距一致", even ? "间距 " + gaps.get(0) : "间距 " + gaps);
	}

	/** 自检用的临时摊主：三笔交易，覆盖售卖/拍卖/求购三种。 */
	private static BazaarTenants.Tenant syntheticTenant() {
		List<BazaarDeal> deals = List.of(
			new BazaarDeal(BazaarDeal.Kind.MARKET, 1L, "钻石", 16, "12.50 / 个", "自检", new ItemStack(Material.DIAMOND)),
			new BazaarDeal(BazaarDeal.Kind.AUCTION, 2L, "附魔金苹果", 1, "起拍 100.00", "自检", new ItemStack(Material.ENCHANTED_GOLDEN_APPLE)),
			new BazaarDeal(BazaarDeal.Kind.ORDER, 3L, "铁锭", 64, "单价 0.80", "自检", new ItemStack(Material.IRON_INGOT))
		);
		return new BazaarTenants.Tenant("BazaarSelfTest", deals);
	}

	/** 同一摊主追加一件挂单：用来验证「招牌随交易实时更新」。 */
	private static BazaarTenants.Tenant syntheticTenantUpdated() {
		List<BazaarDeal> deals = new ArrayList<>(syntheticTenant().deals());
		deals.add(new BazaarDeal(BazaarDeal.Kind.MARKET, 4L, "青金石", 32, "3.00 / 个", "自检", new ItemStack(Material.LAPIS_LAZULI)));
		return new BazaarTenants.Tenant("BazaarSelfTest", deals);
	}

	private BazaarStalls.Occupancy stateOf(int slotIndex) {
		return stalls.debugOccupancy(slotIndex);
	}

	private static String signText(BazaarStalls.Occupancy state) {
		if (state == null) {
			return null;
		}
		Entity entity = Bukkit.getEntity(state.textId());
		if (!(entity instanceof org.bukkit.entity.TextDisplay display)) {
			return null;
		}
		return BazaarText.plain(display.text());
	}

	private static String oneLine(String text) {
		return text.replace('\n', ' ');
	}

	/**
	 * 读摊位人偶身上那段"描述"文字。
	 *
	 * <p>人偶出厂自带一段描述（原版文本是 {@code NPC}），它会和自己的名字一起画在头上 ——
	 * 两段文字叠在一起就是用户看到的"银行NPC"和重影。{@code setDescription(null)} 会把它藏起来，
	 * 藏起来之后 {@link Mannequin#getDescription()} 返回的就是 {@code null}。</p>
	 *
	 * @return 描述文字的纯文本；已隐藏或没有人偶时返回 {@code null}
	 */
	private static String mannequinDescription(World world, int[] slot) {
		for (Entity entity : world.getEntities()) {
			if (entity instanceof Mannequin mannequin
				&& entity.getScoreboardTags().contains(BazaarStalls.TAG)
				&& inSlot(entity.getLocation(), slot)) {
				net.kyori.adventure.text.Component desc = mannequin.getDescription();
				return desc == null ? null : BazaarText.plain(desc);
			}
		}
		return null;
	}

	private static boolean hasEntity(World world, Class<? extends Entity> type, int[] slot) {
		return countEntities(world, type, slot) > 0;
	}

	/** 摊位人偶脚底的 y；没有人偶时返回 {@link Double#NaN}。 */
	private static double mannequinY(World world, int[] slot) {
		for (Entity entity : world.getEntities()) {
			if (entity instanceof Mannequin
				&& entity.getScoreboardTags().contains(BazaarStalls.TAG)
				&& inSlot(entity.getLocation(), slot)) {
				return entity.getLocation().getY();
			}
		}
		return Double.NaN;
	}

	/**
	 * 人偶脚下那格方块的真实顶面高度。
	 *
	 * <p>摊位地毯铺在 {@code floorY + 1}（铺装<b>上面</b>那一层），顶面是 {@code floorY + 1.0625}。
	 * 抬到上面一层而不是把铺装换成地毯，是因为 {@code floorY} 那一层就是玩家站立面 ——
	 * 地毯写在那层等于把地面压低 15/16 格，站在上面的人偶会只露个头。</p>
	 */
	private static double carpetTopY(World world, int[] slot, int floorY) {
		int x = slot[0] + BazaarStalls.WIDTH / 2;
		int z = slot[1] + 1;
		double top = world.getBlockAt(x, floorY + 1, z).getBoundingBox().getMaxY();
		return top > 0.0 ? top : floorY + 1.0;
	}

	/**
	 * 数这个摊位范围内的实体。
	 *
	 * <p>必须按坐标筛 —— 固定摊位（银行/官方商店）同样带 {@link BazaarStalls#TAG}，
	 * 只按标签数会把它们的实体算进自检结果，"解放后已撤除"就永远不通过。</p>
	 */
	private static int countEntities(World world, Class<? extends Entity> type, int[] slot) {
		int count = 0;
		for (Entity entity : world.getEntities()) {
			if (type.isInstance(entity)
				&& entity.getScoreboardTags().contains(BazaarStalls.TAG)
				&& inSlot(entity.getLocation(), slot)) {
				count++;
			}
		}
		return count;
	}

	private static boolean inSlot(org.bukkit.Location loc, int[] slot) {
		return Math.abs(loc.getBlockX() - slot[0]) <= BazaarStalls.WIDTH + 1
			&& Math.abs(loc.getBlockZ() - slot[1]) <= BazaarStalls.DEPTH + 1;
	}

	private static int countItemDisplays(World world, int[] slot, int floorY) {
		int count = 0;
		for (Entity entity : world.getEntities()) {
			if (entity instanceof org.bukkit.entity.ItemDisplay
				&& entity.getScoreboardTags().contains(BazaarStalls.TAG)
				&& inSlot(entity.getLocation(), slot)) {
				count++;
			}
		}
		return count;
	}

	/** 把摊位占地的方块状态记下来（与 {@link BazaarStalls} 的装饰范围一致）。 */
	private static Map<String, BlockData> snapshotArea(World world, int[] slot, int floorY) {
		Map<String, BlockData> out = new LinkedHashMap<>();
		for (int dx = -1; dx <= BazaarStalls.WIDTH; dx++) {
			for (int dz = -1; dz <= BazaarStalls.DEPTH; dz++) {
				for (int dy = -1; dy <= 5; dy++) {
					int x = slot[0] + dx;
					int y = floorY + dy;
					int z = slot[1] + dz;
					out.put(x + " " + y + " " + z, world.getBlockAt(x, y, z).getBlockData());
				}
			}
		}
		return out;
	}

	private static String mark(boolean ok, String name, String detail) {
		return (ok ? "&a✔ " : "&c✘ ") + name + " &8" + detail;
	}
}
