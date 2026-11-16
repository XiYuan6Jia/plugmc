package com.xiyuan.plugmc.train.api;

import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

/**
 * 别的插件（现在是服务器菜单）操作列车用的入口。
 *
 * <p>只暴露「查本人列车」和「动本人列车」这几件事，不把 {@code TrainLines}、
 * {@code TrainDriver} 那些内部结构漏出去 —— 菜单不该知道车厢 UUID、编组表长什么样。</p>
 *
 * <p>所有带 {@code owner} 的方法都会自己再核一次归属：菜单只列本人的车，
 * 但菜单只负责显示，真正能不能改由这里说了算，免得以后多一个入口就多一个越权的口子。</p>
 */
public interface TrainService {

	/** 一条列车在菜单里需要的全部信息。 */
	record LineView(String name, String routeName, double spacing, int cars, boolean running) {
	}

	/** 一次操作的结果。{@code message} 成功时是给玩家的提示，失败时是原因。 */
	record Result(boolean ok, String message) {
		public static Result ok(String message) {
			return new Result(true, message);
		}

		public static Result fail(String message) {
			return new Result(false, message);
		}
	}

	/** 某个玩家名下的列车，按名字排序。 */
	List<LineView> linesOf(UUID owner);

	/** 一条列车的详情；不属于这个玩家或不存在时返回 null。 */
	LineView lineOf(UUID owner, String lineName);

	/** 全部路线名，菜单里选路线用。 */
	List<String> routeNames();

	/** 让本人的列车开始跑。 */
	Result start(Player actor, String lineName);

	/** 让本人的列车停下。 */
	Result stop(Player actor, String lineName);

	/** 给本人的列车绑路线；{@code route} 传空串就是解绑。 */
	Result setRoute(Player actor, String lineName, String route);

	/** 调本人的列车车厢间距（0.5~1.0 格）。 */
	Result setSpacing(Player actor, String lineName, double spacing);

	/** 删掉本人的列车登记（矿车本身不动）。 */
	Result delete(Player actor, String lineName);
}
