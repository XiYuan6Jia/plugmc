package com.xiyuan.plugmc.economy.mail;

import org.bukkit.configuration.file.FileConfiguration;

public record MailConfig(int capacity, int expireDays) {
	public MailConfig {
		capacity = Math.max(1, Math.min(500, capacity));
		expireDays = Math.max(1, Math.min(3650, expireDays));
	}

	public static MailConfig load(FileConfiguration config) {
		return new MailConfig(
			config.getInt("mail.capacity", 50),
			config.getInt("mail.expire-days", 30)
		);
	}

	public long expiresAt(long now) {
		return now + expireDays * 24L * 60L * 60L * 1000L;
	}
}
