package com.xiyuan.plugmc.lowhp;

/**
 * 内置的两段低血量提示音。声音事件为 {@code lowhp:alert.<id>}，
 * 资源包路径 {@code assets/lowhp/sounds/alert/<id>.ogg}。
 */
public enum Alert {
	A("a", "邦吧邦吧邦吧（雨爱·司凤版）", "alert_a.ogg"),
	B("b", "你的呼吸像雨滴（雨爱 DJ 1.03x）", "alert_b.ogg");

	private final String id;
	private final String display;
	private final String fileName;

	Alert(String id, String display, String fileName) {
		this.id = id;
		this.display = display;
		this.fileName = fileName;
	}

	public String id() {
		return id;
	}

	public String display() {
		return display;
	}

	public String fileName() {
		return fileName;
	}

	public String soundKey(String namespace) {
		return namespace + ":alert." + id;
	}

	public String soundPath() {
		return "alert/" + id;
	}

	public static Alert byId(String value) {
		if (value == null) {
			return null;
		}
		for (Alert alert : values()) {
			if (alert.id.equalsIgnoreCase(value) || alert.name().equalsIgnoreCase(value)) {
				return alert;
			}
		}
		return null;
	}
}
