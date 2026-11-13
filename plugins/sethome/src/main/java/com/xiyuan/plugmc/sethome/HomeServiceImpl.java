package com.xiyuan.plugmc.sethome;

import com.xiyuan.plugmc.sethome.api.HomeService;
import org.bukkit.Location;

import java.util.List;
import java.util.UUID;

/**
 * {@link HomeService} 的实现：只把 {@link HomeStore} 的查询原样透出去。
 *
 * <p>刻意不做缓存 —— 家园数据量很小，现读现回，省掉「改完家之后读到旧坐标」这类问题。</p>
 */
final class HomeServiceImpl implements HomeService {
	private final HomeStore store;

	HomeServiceImpl(HomeStore store) {
		this.store = store;
	}

	@Override
	public Location home(UUID owner, String name) {
		if (owner == null) {
			return null;
		}
		Location location = store.home(owner, name);
		return location == null ? null : location.clone();
	}

	@Override
	public List<String> names(UUID owner) {
		return owner == null ? List.of() : store.names(owner);
	}

	@Override
	public String defaultName() {
		return HomeStore.normalizeName(null);
	}
}
