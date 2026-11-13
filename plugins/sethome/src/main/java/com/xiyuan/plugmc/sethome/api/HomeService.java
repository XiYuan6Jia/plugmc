package com.xiyuan.plugmc.sethome.api;

import org.bukkit.Location;

import java.util.List;
import java.util.UUID;

/**
 * 家园数据的只读视图，供别的插件查询「某人的家在哪」。
 *
 * <p>只暴露查询，不暴露写入 —— 改家园是 {@code /sethome}、{@code /delhome} 的事，
 * 别让外部插件绕过数量上限与命名校验。</p>
 *
 * <p>通过 {@code Bukkit.getServicesManager().load(HomeService.class)} 取用。</p>
 */
public interface HomeService {

	/**
	 * 取某人的一个家园坐标。
	 *
	 * @param owner 家园所有者的 UUID
	 * @param name  家园名，会自动规范化（去空白、转小写）；传 null 或空串时按 {@code home} 处理
	 * @return 坐标；没有这个家园、或家园所在世界已卸载时返回 null
	 */
	Location home(UUID owner, String name);

	/** 某人所有家园的名字，按设置顺序；没有任何家园时返回空列表。 */
	List<String> names(UUID owner);

	/** 某人是否有这个家园。 */
	default boolean has(UUID owner, String name) {
		return home(owner, name) != null;
	}

	/** 某人一共有几个家园。 */
	default int count(UUID owner) {
		return names(owner).size();
	}

	/**
	 * 没指定家园名时的默认名字。
	 *
	 * <p>调用方拿它去 {@link #home} 里查，查不到就该让玩家自己挑一个，而不是猜。</p>
	 */
	String defaultName();
}
