package com.xiyuan.plugmc.hub;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.structure.Structure;
import org.bukkit.structure.StructureManager;
import org.bukkit.util.BlockVector;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Random;
import java.util.logging.Level;

/**
 * 主城大厅的「预设」：把世界里的现状存成一份结构文件，重建时按它摆放。
 *
 * <p>以前 {@link HubLobby#build} 是纯硬编码坐标，建筑师改出来的东西永远进不了预设 ——
 * 一执行 {@code /hub build} 就回到出厂样子。现在有了这份蓝本：
 * <ul>
 *   <li>{@code /hub save} —— 把当前世界里那片区域原样抓下来写成 {@code lobby.nbt}；</li>
 *   <li>{@code /hub build} —— 区域先清空，再按蓝本摆回去；没有蓝本才退回硬编码版本。</li>
 * </ul>
 *
 * <p><b>只存方块，不存实体。</b>大厅里那两块悬浮文字是 {@link HubLobby#spawnTexts} 按配置
 * 生成的，存进蓝本反而会在每次重建时叠一层；商业街的人偶与招牌也不归这里管。
 */
public final class HubBlueprint {
	/** 蓝本文件名，放在 {@code plugins/hub/} 下。 */
	static final String FILE_NAME = "lobby.nbt";
	private static final String BACKUP_DIR = "blueprint-backup";
	private static final int MAX_BACKUPS = 5;

	private final File dir;
	private final File file;

	public HubBlueprint(File dataFolder) {
		this.dir = dataFolder;
		this.file = new File(dataFolder, FILE_NAME);
	}

	public File file() {
		return file;
	}

	public boolean exists() {
		return file.isFile() && file.length() > 0;
	}

	/**
	 * 抓取区域的大小与方块数，用于 /hub save 的提示（不写盘）。
	 *
	 * @return 三元素：{@code [x, y, z]} 尺寸；区域配置不对时返回 null
	 */
	public int[] regionSize(HubConfig config) {
		Region region = region(config);
		if (region == null) {
			return null;
		}
		return new int[]{region.width(), region.height(), region.depth()};
	}

	/**
	 * 把世界里的当前状态存成蓝本。旧蓝本会先挪到 {@code blueprint-backup/} 留一手。
	 *
	 * @return 成功返回方块数（> 0），失败返回 -1
	 */
	public long save(World world, HubConfig config) {
		Region region;
		try {
			region = region(config);
		} catch (IllegalArgumentException e) {
			return -1;
		}
		StructureManager manager = Bukkit.getStructureManager();
		Structure structure;
		try {
			structure = manager.createStructure();
			// ★ fill 的第二个角是「开区间」：给 (24,75,24) 只会存到 23/74/23，
			// 区域最外一层的墙柱、屋顶、天窗全都会丢。必须 +1 才等于闭区间。
			structure.fill(
				new Location(world, region.minX(), region.minY(), region.minZ()),
				new Location(world, region.maxX() + 1, region.maxY() + 1, region.maxZ() + 1),
				false
			);
		} catch (RuntimeException e) {
			return -1;
		}
		BlockVector size = structure.getSize();
		long volume = (long) size.getBlockX() * size.getBlockY() * size.getBlockZ();
		if (volume <= 0) {
			return -1;
		}
		backupOld();
		if (dir.isDirectory() || dir.mkdirs()) {
			try {
				manager.saveStructure(file, structure);
			} catch (IOException e) {
				return -1;
			}
		} else {
			return -1;
		}
		return volume;
	}

	/**
	 * 按蓝本摆回大厅。调用前应先把区域清空（{@link HubLobby#build} 里已经这么做了）。
	 *
	 * @return 成功返回 true；没有蓝本或读取失败返回 false
	 */
	public boolean place(World world, HubConfig config) {
		if (!exists()) {
			return false;
		}
		Region region;
		try {
			region = region(config);
		} catch (IllegalArgumentException e) {
			return false;
		}
		StructureManager manager = Bukkit.getStructureManager();
		Structure structure;
		try {
			structure = manager.loadStructure(file);
		} catch (IOException | RuntimeException e) {
			return false;
		}
		if (structure == null) {
			return false;
		}
		try {
			// includeEntities 传 false：蓝本只存方块，实体（大厅悬浮文字、商业街人偶）
			// 一律由各自的代码按配置现场生成，从蓝本里恢复会叠出两份。
			structure.place(
				new Location(world, region.minX(), region.minY(), region.minZ()),
				false,
				StructureRotation.NONE,
				Mirror.NONE,
				0,
				1.0f,
				new Random()
			);
		} catch (RuntimeException e) {
			return false;
		}
		return true;
	}

	public boolean delete() {
		if (!exists()) {
			return false;
		}
		backupOld();
		try {
			return Files.deleteIfExists(file.toPath());
		} catch (IOException e) {
			return false;
		}
	}

	/** 描述当前蓝本：存档时间、大小、区域尺寸。 */
	public String describe(HubConfig config) {
		if (!exists()) {
			return "&7还没有蓝本，重建时会用内置的默认大厅。";
		}
		String when = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
			.format(new Date(file.lastModified()));
		int[] size = regionSize(config);
		String dims = size == null ? "区域配置无效" : size[0] + "×" + size[1] + "×" + size[2];
		return "&7蓝本：&f" + FILE_NAME + " &7存于 &f" + when
			+ " &7（" + Math.max(1, file.length() / 1024) + " KB，区域 " + dims + "）";
	}

	private void backupOld() {
		if (!exists()) {
			return;
		}
		File backupDir = new File(dir, BACKUP_DIR);
		if (!backupDir.isDirectory() && !backupDir.mkdirs()) {
			return;
		}
		String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
		Path target = new File(backupDir, "lobby-" + stamp + ".nbt").toPath();
		try {
			Files.copy(file.toPath(), target, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			return;
		}
		// 只留最近几份，别让备份目录无限长大
		File[] backups = backupDir.listFiles((d, name) -> name.startsWith("lobby-") && name.endsWith(".nbt"));
		if (backups == null || backups.length <= MAX_BACKUPS) {
			return;
		}
		java.util.Arrays.sort(backups, java.util.Comparator.comparingLong(File::lastModified));
		for (int i = 0; i < backups.length - MAX_BACKUPS; i++) {
			if (!backups[i].delete()) {
				Bukkit.getLogger().log(Level.FINE, "清理旧蓝本备份失败：" + backups[i]);
			}
		}
	}

	/**
	 * 蓝本覆盖的区域：默认就是大厅的方形占地，y 从地板到屋顶上面一层（跟 {@link HubLobby#clearBlocks}
	 * 清的范围一致）。想连街区一起存，在 config.yml 里显式写 {@code blueprint.min-x} 等六项。
	 */
	private Region region(HubConfig config) {
		int floorY = config.floorY();
		int radius = config.radius();
		int minX = config.blueprintMinX() != null ? config.blueprintMinX() : -radius;
		int maxX = config.blueprintMaxX() != null ? config.blueprintMaxX() : radius;
		int minZ = config.blueprintMinZ() != null ? config.blueprintMinZ() : -radius;
		int maxZ = config.blueprintMaxZ() != null ? config.blueprintMaxZ() : radius;
		int minY = config.blueprintMinY() != null ? config.blueprintMinY() : floorY;
		int maxY = config.blueprintMaxY() != null ? config.blueprintMaxY() : floorY + HubLobby.ROOF_OFFSET;
		if (maxX < minX || maxY < minY || maxZ < minZ) {
			throw new IllegalArgumentException("蓝本区域的 max 必须大于等于 min");
		}
		return new Region(minX, minY, minZ, maxX, maxY, maxZ);
	}

	private record Region(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
		int width() {
			return maxX - minX + 1;
		}

		int height() {
			return maxY - minY + 1;
		}

		int depth() {
			return maxZ - minZ + 1;
		}
	}
}
