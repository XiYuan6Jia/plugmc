package com.xiyuan.plugmc.hub;

import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.logging.Level;

/**
 * 「这个维度的大厅已经生成过了」的持久标记。
 *
 * <p>以前是拿喷泉顶端那颗海晶灯当标记方块（{@code (0, floorY+4, 0)}）。建筑师只要动了
 * 那颗灯 —— 拆掉、换成别的水晶、或者铺地时顺手覆盖 —— 下次开服 {@link HubLobby#isBuilt}
 * 就返回 false，接着 {@link HubLobby#build} 先把半径 25 格内整片方块清成空气再重建，
 * 建筑师这一整天改的东西全部消失，而且日志里只有一行「主城大厅已生成」，看着像正常启动。
 *
 * <p>标记改成插件数据目录下的一个文件之后，改建筑不再影响「建没建过」这个判断；
 * 想重建只能显式执行 {@code /hub build}。
 */
public final class HubMarker {
	private final JavaPlugin plugin;
	private final File file;
	private boolean built;
	private boolean parkourBuilt;
	private int builtFloorY = Integer.MIN_VALUE;
	private int builtRadius = Integer.MIN_VALUE;

	public HubMarker(JavaPlugin plugin) {
		this.plugin = plugin;
		this.file = new File(plugin.getDataFolder(), "lobby-built.yml");
		load();
	}

	private void load() {
		if (!file.isFile()) {
			built = false;
			parkourBuilt = false;
			return;
		}
		YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
		built = yaml.getBoolean("built", false);
		parkourBuilt = yaml.getBoolean("parkour-built", false);
		builtFloorY = yaml.getInt("floor-y", Integer.MIN_VALUE);
		builtRadius = yaml.getInt("radius", Integer.MIN_VALUE);
	}

	/** 大厅是否已经生成过。只看文件，不看世界里的方块。 */
	public boolean isBuilt() {
		return built;
	}

	/** 跑酷赛道是否已经生成过。 */
	public boolean isParkourBuilt() {
		return parkourBuilt;
	}

	public void markBuilt(HubConfig config) {
		built = true;
		builtFloorY = config.floorY();
		builtRadius = config.radius();
		save();
	}

	public void markParkourBuilt() {
		parkourBuilt = true;
		save();
	}

	private void save() {
		YamlConfiguration yaml = new YamlConfiguration();
		yaml.set("built", built);
		yaml.set("parkour-built", parkourBuilt);
		yaml.set("floor-y", builtFloorY);
		yaml.set("radius", builtRadius);
		yaml.set("note", "删掉本文件（或把 built 改成 false）会在下次开服重建大厅，"
			+ "重建会清空半径 radius 格内、floor-y 以上的全部方块");
		File parent = file.getParentFile();
		if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
			plugin.getLogger().log(Level.WARNING, "无法创建数据目录：" + parent);
			return;
		}
		try {
			yaml.save(file);
		} catch (IOException e) {
			plugin.getLogger().log(Level.WARNING, "保存 " + file.getName() + " 失败", e);
		}
	}

	/**
	 * 老版本升级迁移：文件还没有、但世界里的老标记方块还在，说明这座大厅是以前建的。
	 * 直接补写文件，不重建。
	 *
	 * @return true 表示这次补写了标记
	 */
	public boolean migrateFromLegacyBlock(World world, HubConfig config) {
		boolean changed = false;
		if (!built && HubLobby.isLegacyBuilt(world, config)) {
			markBuilt(config);
			plugin.getLogger().info("已把「大厅已生成」标记写入 " + file.getName()
				+ "（沿用世界里的老标记方块，没有重建）");
			changed = true;
		}
		if (!parkourBuilt && ParkourCourse.isLegacyBuilt(world, config)) {
			markParkourBuilt();
			plugin.getLogger().info("已把「跑酷赛道已生成」标记写入 " + file.getName()
				+ "（沿用世界里的老标记方块，没有重建）");
			changed = true;
		}
		return changed;
	}

	/**
	 * 配置里的 floor-y / radius 跟当初生成时不一致时的提示。
	 *
	 * <p>刻意不自动重建：改两个数字就把建筑师的作品清掉，正是这个功能最不该有的行为。
	 */
	public void warnIfGeometryChanged(HubConfig config) {
		if (!built || builtFloorY == Integer.MIN_VALUE) {
			return;
		}
		if (builtFloorY == config.floorY() && builtRadius == config.radius()) {
			return;
		}
		plugin.getLogger().warning("配置里的 floor-y/radius（" + config.floorY() + "/" + config.radius()
			+ "）与生成时（" + builtFloorY + "/" + builtRadius + "）不一致。"
			+ "不会自动重建；确实要按新尺寸重来请执行 /hub build（会清空大厅范围内的方块）");
	}
}
