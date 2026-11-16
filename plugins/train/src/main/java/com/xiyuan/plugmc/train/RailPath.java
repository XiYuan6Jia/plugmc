package com.xiyuan.plugmc.train;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Rail;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 铁轨寻路：在铁轨方块之间做一次广度优先搜索，得到一串「依次经过的铁轨方块」。
 *
 * <p>之所以自己走一遍而不是用 {@code Location#add} 挨个推，是因为弯道和坡道会让
 * 方向不断变化 —— 只有按方块形状算相邻关系，才不会在道岔处走错方向。</p>
 *
 * <p>坐标一律用方块整数坐标，最后再换算成铁轨方块中心的 {@link Vector}。</p>
 */
public final class RailPath {
	/** 铁轨方块中心相对方块坐标的偏移（y 抬半格，正好是矿车轨面）。 */
	private static final double SURFACE_Y = 0.0625;

	private final World world;
	private final List<Vector> points = new ArrayList<>();
	private final List<BlockKey> blocks = new ArrayList<>();

	private RailPath(World world) {
		this.world = world;
	}

	public static boolean isRail(Material type) {
		return type == Material.RAIL
			|| type == Material.POWERED_RAIL
			|| type == Material.DETECTOR_RAIL
			|| type == Material.ACTIVATOR_RAIL;
	}

	/**
	 * 从 start 走到 goal，返回路径（含起点与终点）。找不到路时返回 null。
	 */
	public static RailPath find(World world, BlockKey start, BlockKey goal, int maxBlocks) {
		if (!isRail(world.getBlockAt(start.x(), start.y(), start.z()).getType())) {
			return null;
		}
		if (!isRail(world.getBlockAt(goal.x(), goal.y(), goal.z()).getType())) {
			return null;
		}
		Map<BlockKey, BlockKey> cameFrom = new HashMap<>();
		Deque<BlockKey> queue = new ArrayDeque<>();
		queue.add(start);
		cameFrom.put(start, start);
		int visited = 0;
		while (!queue.isEmpty()) {
			BlockKey current = queue.poll();
			if (current.equals(goal)) {
				return build(world, cameFrom, start, goal);
			}
			if (++visited > maxBlocks) {
				break;
			}
			for (BlockKey next : neighbors(world, current)) {
				if (cameFrom.containsKey(next)) {
					continue;
				}
				cameFrom.put(next, current);
				queue.add(next);
			}
		}
		return null;
	}

	/** 从 start 沿铁轨一直走，直到尽头或超过 maxBlocks，用来给「没有站点」的列车兜底。 */
	public static RailPath walk(World world, BlockKey start, int maxBlocks) {
		if (!isRail(world.getBlockAt(start.x(), start.y(), start.z()).getType())) {
			return null;
		}
		RailPath path = new RailPath(world);
		path.blocks.add(start);
		path.points.add(center(start));
		BlockKey previous = null;
		BlockKey current = start;
		for (int i = 0; i < maxBlocks; i++) {
			BlockKey next = null;
			for (BlockKey candidate : neighbors(world, current)) {
				if (candidate.equals(previous)) {
					continue;
				}
				next = candidate;
				break;
			}
			if (next == null) {
				break;
			}
			path.blocks.add(next);
			path.points.add(center(next));
			previous = current;
			current = next;
		}
		return path;
	}

	/**
	 * 从 start 沿铁轨往「后面」走一段，返回沿途方块中心的折线（第一个点就是 start 的中心）。
	 *
	 * <p>车厢要排成一列跟在车头后面，位置必须落在铁轨上 —— 只要有一个点偏离轨道，
	 * 那节矿车就会脱轨掉下去。所以这里不用车头的浮点坐标做减法，而是老老实实按铁轨
	 * 方块逐个往回走：每一步都从当前格的相邻格里挑一个不是 {@code avoid}（前车要去的方向）
	 * 也不是刚来过的格子，走到 count 格或者走到尽头为止。</p>
	 */
	public static List<Vector> behindChain(World world, BlockKey start, BlockKey avoid, int count) {
		List<Vector> chain = new ArrayList<>();
		if (!isRail(world.getBlockAt(start.x(), start.y(), start.z()).getType())) {
			return chain;
		}
		chain.add(center(start));
		BlockKey previous = avoid;
		BlockKey current = start;
		Set<BlockKey> seen = new HashSet<>();
		seen.add(start);
		for (int i = 0; i < count; i++) {
			BlockKey next = null;
			for (BlockKey candidate : neighbors(world, current)) {
				if (candidate.equals(previous) || seen.contains(candidate)) {
					continue;
				}
				next = candidate;
				break;
			}
			if (next == null) {
				break;
			}
			chain.add(center(next));
			seen.add(next);
			previous = current;
			current = next;
		}
		return chain;
	}

	private static RailPath build(World world, Map<BlockKey, BlockKey> cameFrom, BlockKey start, BlockKey goal) {
		List<BlockKey> reversed = new ArrayList<>();
		BlockKey cursor = goal;
		while (!cursor.equals(start)) {
			reversed.add(cursor);
			BlockKey parent = cameFrom.get(cursor);
			if (parent == null) {
				return null;
			}
			cursor = parent;
		}
		reversed.add(start);
		Collections.reverse(reversed);
		RailPath path = new RailPath(world);
		for (BlockKey key : reversed) {
			path.blocks.add(key);
			path.points.add(center(key));
		}
		return path;
	}

	/**
	 * 计算某个铁轨方块能连到哪几个铁轨方块。
	 *
	 * <p>坡道按原版规则处理：上坡铁轨的低端接水平邻居，高端接斜上方那格。
	 * 比如 {@code ASCENDING_EAST} 从西侧低、东侧高，所以邻居是 (x-1, y) 与 (x+1, y+1)。</p>
	 */
	private static List<BlockKey> neighbors(World world, BlockKey key) {
		Block block = world.getBlockAt(key.x(), key.y(), key.z());
		if (!(block.getBlockData() instanceof Rail rail)) {
			return List.of();
		}
		List<BlockKey> result = new ArrayList<>(2);
		for (int[] delta : deltas(rail.getShape())) {
			BlockKey candidate = new BlockKey(key.x() + delta[0], key.y() + delta[1], key.z() + delta[2]);
			if (isRail(world.getBlockAt(candidate.x(), candidate.y(), candidate.z()).getType())) {
				result.add(candidate);
			}
		}
		return result;
	}

	/**
	 * 每种形状的两个相邻方向，元素是 {dx, dy, dz}。
	 */
	private static int[][] deltas(Rail.Shape shape) {
		return switch (shape) {
			case NORTH_SOUTH -> new int[][]{{0, 0, -1}, {0, 0, 1}};
			case EAST_WEST -> new int[][]{{-1, 0, 0}, {1, 0, 0}};
			case ASCENDING_EAST -> new int[][]{{-1, 0, 0}, {1, 1, 0}};
			case ASCENDING_WEST -> new int[][]{{1, 0, 0}, {-1, 1, 0}};
			case ASCENDING_NORTH -> new int[][]{{0, 0, 1}, {0, 1, -1}};
			case ASCENDING_SOUTH -> new int[][]{{0, 0, -1}, {0, 1, 1}};
			case SOUTH_EAST -> new int[][]{{0, 0, 1}, {1, 0, 0}};
			case SOUTH_WEST -> new int[][]{{0, 0, 1}, {-1, 0, 0}};
			case NORTH_WEST -> new int[][]{{0, 0, -1}, {-1, 0, 0}};
			case NORTH_EAST -> new int[][]{{0, 0, -1}, {1, 0, 0}};
		};
	}

	private static Vector center(BlockKey key) {
		return new Vector(key.x() + 0.5, key.y() + SURFACE_Y, key.z() + 0.5);
	}

	public List<Vector> points() {
		return List.copyOf(points);
	}

	public List<BlockKey> blocks() {
		return List.copyOf(blocks);
	}

	public World world() {
		return world;
	}

	public boolean isEmpty() {
		return points.isEmpty();
	}

	/** 方块整数坐标，用作图搜索的节点。 */
	public record BlockKey(int x, int y, int z) {
		public static BlockKey of(org.bukkit.Location location) {
			return new BlockKey(location.getBlockX(), location.getBlockY(), location.getBlockZ());
		}

		public org.bukkit.Location toLocation(World world) {
			return new org.bukkit.Location(world, x + 0.5, y + SURFACE_Y, z + 0.5);
		}
	}
}
