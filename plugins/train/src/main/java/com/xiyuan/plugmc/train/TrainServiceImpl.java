package com.xiyuan.plugmc.train;

import com.xiyuan.plugmc.train.api.TrainService;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * {@link TrainService} 的实现：把菜单的请求翻译成对内部结构的调用。
 *
 * <p>这里集中做两件事：把内部对象摊平成 {@code LineView}，以及<b>每次动手前再核一遍归属</b>。
 * 菜单那边也会过滤，但那是显示层的事 —— 权限判断只认这一处。</p>
 */
public final class TrainServiceImpl implements TrainService {
	private final TrainPlugin plugin;

	public TrainServiceImpl(TrainPlugin plugin) {
		this.plugin = plugin;
	}

	@Override
	public List<LineView> linesOf(UUID owner) {
		List<LineView> views = new ArrayList<>();
		if (owner == null) {
			return views;
		}
		for (TrainLines.TrainLine line : plugin.lines().of(owner)) {
			views.add(view(line));
		}
		return views;
	}

	@Override
	public LineView lineOf(UUID owner, String lineName) {
		TrainLines.TrainLine line = owned(owner, lineName);
		return line == null ? null : view(line);
	}

	@Override
	public List<String> routeNames() {
		return plugin.routes().names();
	}

	@Override
	public Result start(Player actor, String lineName) {
		TrainLines.TrainLine line = owned(actor.getUniqueId(), lineName);
		if (line == null) {
			return Result.fail("这不是你的列车。");
		}
		if (line.routeName().isBlank()) {
			return Result.fail("这条列车还没绑路线，先选一条。");
		}
		if (plugin.driver().isRunning(line.name())) {
			return Result.fail("这条列车已经在跑了。");
		}
		String error = plugin.driver().start(line.cart(), line.routeName(), actor, line);
		return error == null ? Result.ok("列车已出发。") : Result.fail(error);
	}

	@Override
	public Result stop(Player actor, String lineName) {
		TrainLines.TrainLine line = owned(actor.getUniqueId(), lineName);
		if (line == null) {
			return Result.fail("这不是你的列车。");
		}
		return plugin.driver().stopLine(line.name())
			? Result.ok("列车已停止。")
			: Result.fail("这条列车本来就没在跑。");
	}

	@Override
	public Result setRoute(Player actor, String lineName, String route) {
		TrainLines.TrainLine line = owned(actor.getUniqueId(), lineName);
		if (line == null) {
			return Result.fail("这不是你的列车。");
		}
		String wanted = route == null ? "" : route.trim();
		if (!wanted.isEmpty() && plugin.routes().get(wanted) == null) {
			return Result.fail("没有叫「" + wanted + "」的路线。");
		}
		plugin.lines().put(line.withRoute(wanted));
		plugin.lines().save();
		return Result.ok(wanted.isEmpty() ? "已解除路线绑定。" : "已绑定路线「" + wanted + "」。");
	}

	@Override
	public Result setSpacing(Player actor, String lineName, double spacing) {
		TrainLines.TrainLine line = owned(actor.getUniqueId(), lineName);
		if (line == null) {
			return Result.fail("这不是你的列车。");
		}
		if (spacing < 0.5 || spacing > 1.0) {
			return Result.fail("间距只能在 0.5 到 1.0 格之间。");
		}
		plugin.lines().put(line.withSpacing(spacing));
		plugin.lines().save();
		return Result.ok("车厢间距已设为 " + String.format(Locale.ROOT, "%.2f", spacing) + " 格。");
	}

	@Override
	public Result delete(Player actor, String lineName) {
		TrainLines.TrainLine line = owned(actor.getUniqueId(), lineName);
		if (line == null) {
			return Result.fail("这不是你的列车。");
		}
		plugin.driver().stopLine(line.name());
		plugin.lines().remove(line.name());
		plugin.lines().save();
		return Result.ok("已删除这条列车的登记，矿车本身没动。");
	}

	private LineView view(TrainLines.TrainLine line) {
		int cars = plugin.trains().consistOf(line.cart()).size();
		return new LineView(line.name(), line.routeName(), line.spacing(), cars,
			plugin.driver().isRunning(line.name()));
	}

	/** 取出本人名下的列车；不存在或不属于这个人时返回 null。 */
	private TrainLines.TrainLine owned(UUID owner, String lineName) {
		TrainLines.TrainLine line = plugin.lines().get(lineName);
		if (line == null || owner == null || !line.ownerId().equals(owner)) {
			return null;
		}
		return line;
	}
}
