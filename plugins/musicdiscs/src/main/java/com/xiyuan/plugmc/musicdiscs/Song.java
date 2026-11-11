package com.xiyuan.plugmc.musicdiscs;

/**
 * 一首自定义唱片。{@code id} 同时是文件名、声音事件名与数据包注册表键。
 *
 * <p>{@code owner} / {@code ownerName} 为空表示服务器曲目（管理员导入），
 * 非空表示玩家自己上传的音频（每个账号限一首）。</p>
 */
public record Song(
	String id,
	String title,
	String author,
	float seconds,
	int comparator,
	String owner,
	String ownerName
) {
	public Song {
		title = title == null || title.isBlank() ? id : title.trim();
		author = author == null ? "" : author.trim();
		owner = owner == null ? "" : owner.trim();
		ownerName = ownerName == null ? "" : ownerName.trim();
		seconds = Math.max(0f, seconds);
		comparator = Math.max(0, Math.min(15, comparator));
	}

	public boolean playerSong() {
		return !owner.isEmpty();
	}

	public String soundKey(String namespace) {
		return namespace + ":disc." + id;
	}

	public String soundPath() {
		return "disc/" + id;
	}

	/**
	 * 物品模型名，资源包里的 items/models/textures 都用这个名字。
	 */
	public String itemModel() {
		return "disc_" + id;
	}

	public String durationText() {
		return MusicText.duration(seconds);
	}

	public String displayName() {
		return author.isEmpty() ? title : title + " - " + author;
	}
}
