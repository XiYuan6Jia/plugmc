package com.xiyuan.plugmc.musicdiscs;

/**
 * 一首自定义唱片。{@code id} 同时是文件名、声音事件名与数据包注册表键。
 */
public record Song(
	String id,
	String title,
	String author,
	float seconds,
	int comparator,
	int customModelData
) {
	public Song {
		title = title == null || title.isBlank() ? id : title.trim();
		author = author == null ? "" : author.trim();
		seconds = Math.max(0f, seconds);
		comparator = Math.max(0, Math.min(15, comparator));
	}

	public String soundKey(String namespace) {
		return namespace + ":disc." + id;
	}

	public String soundPath() {
		return "disc/" + id;
	}

	public String durationText() {
		return MusicText.duration(seconds);
	}

	public String displayName() {
		return author.isEmpty() ? title : title + " - " + author;
	}
}
