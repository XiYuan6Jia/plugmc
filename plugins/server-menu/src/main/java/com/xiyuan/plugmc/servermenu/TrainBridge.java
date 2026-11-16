package com.xiyuan.plugmc.servermenu;

import com.xiyuan.plugmc.train.api.TrainService;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

/**
 * 把 {@link TrainService} 包一层，纯粹为了让菜单里的调用读起来短一点。
 *
 * <p>为什么不直接在菜单里用 {@code TrainService}：{@code TrainService.LineView} 与
 * {@code TrainService.Result} 都是嵌套类型，菜单里每处都要写全限定名，
 * 一行下来半屏都是包路径。包一层之后只剩 {@code bridge.start(...)} 这种短句。</p>
 *
 * <p>没有多一层缓存，也没做任何转换 —— 所有方法都是直接转给底下的服务。</p>
 */
final class TrainBridge {
	private final TrainService service;

	TrainBridge(TrainService service) {
		this.service = service;
	}

	List<TrainService.LineView> linesOf(UUID owner) {
		return service.linesOf(owner);
	}

	TrainService.LineView lineOf(UUID owner, String lineName) {
		return service.lineOf(owner, lineName);
	}

	List<String> routeNames() {
		return service.routeNames();
	}

	TrainService.Result start(Player actor, String lineName) {
		return service.start(actor, lineName);
	}

	TrainService.Result stop(Player actor, String lineName) {
		return service.stop(actor, lineName);
	}

	TrainService.Result setRoute(Player actor, String lineName, String route) {
		return service.setRoute(actor, lineName, route);
	}

	TrainService.Result setSpacing(Player actor, String lineName, double spacing) {
		return service.setSpacing(actor, lineName, spacing);
	}

	TrainService.Result delete(Player actor, String lineName) {
		return service.delete(actor, lineName);
	}
}
