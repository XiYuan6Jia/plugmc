package com.xiyuan.plugmc.antixray;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 读取 / 修改 Paper 的 config/paper-world-defaults.yml 中 anti-xray 段。
 * 只改 enabled 与 engine-mode 两行，保留注释和其它配置；写入前生成 .bak-时间戳 备份。
 */
public final class PaperAntiXray {
	private static final DateTimeFormatter BACKUP_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

	private PaperAntiXray() {
	}

	public record Status(boolean known, boolean enabled, int engineMode) {
	}

	public static Status status(Path file) {
		if (!Files.isRegularFile(file)) {
			return new Status(false, false, -1);
		}
		try {
			return parse(Files.readAllLines(file, StandardCharsets.UTF_8));
		} catch (IOException exception) {
			return new Status(false, false, -1);
		}
	}

	static Status parse(List<String> lines) {
		int start = findSection(lines);
		if (start < 0) {
			return new Status(false, false, -1);
		}
		int baseIndent = indentOf(lines.get(start));
		boolean enabled = false;
		int engineMode = -1;
		for (int index = start + 1; index < lines.size(); index++) {
			String line = lines.get(index);
			if (line.isBlank()) {
				continue;
			}
			if (indentOf(line) <= baseIndent) {
				break;
			}
			String trimmed = line.trim();
			if (trimmed.startsWith("enabled:")) {
				enabled = trimmed.substring("enabled:".length()).trim().startsWith("true");
			} else if (trimmed.startsWith("engine-mode:")) {
				engineMode = parseEngineMode(trimmed.substring("engine-mode:".length()).trim());
			}
		}
		return new Status(true, enabled, engineMode);
	}

	static void apply(Path file, int engineMode) throws IOException {
		if (!Files.isRegularFile(file)) {
			throw new IOException("未找到 " + file);
		}
		List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
		int start = findSection(lines);
		if (start < 0) {
			throw new IOException("未找到 anti-xray 配置段");
		}
		int baseIndent = indentOf(lines.get(start));
		boolean enabledSet = false;
		boolean engineSet = false;
		for (int index = start + 1; index < lines.size(); index++) {
			String line = lines.get(index);
			if (line.isBlank()) {
				continue;
			}
			if (indentOf(line) <= baseIndent) {
				break;
			}
			String trimmed = line.trim();
			if (!enabledSet && trimmed.startsWith("enabled:")) {
				lines.set(index, replaceValue(line, "enabled:", "true"));
				enabledSet = true;
			} else if (!engineSet && trimmed.startsWith("engine-mode:")) {
				lines.set(index, replaceValue(line, "engine-mode:", Integer.toString(engineMode)));
				engineSet = true;
			}
		}
		if (!enabledSet) {
			throw new IOException("anti-xray 段缺少 enabled 配置");
		}
		Path backup = file.resolveSibling(file.getFileName() + ".bak-"
			+ BACKUP_STAMP.format(LocalDateTime.now()));
		Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
		Files.write(file, lines, StandardCharsets.UTF_8);
	}

	private static int parseEngineMode(String raw) {
		if (raw.isEmpty()) {
			return -1;
		}
		String value = raw.split("\\s+", 2)[0];
		try {
			return Integer.parseInt(value);
		} catch (NumberFormatException exception) {
			return -1;
		}
	}

	private static int findSection(List<String> lines) {
		for (int index = 0; index < lines.size(); index++) {
			if (lines.get(index).trim().equals("anti-xray:")) {
				return index;
			}
		}
		return -1;
	}

	private static int indentOf(String line) {
		int count = 0;
		while (count < line.length() && line.charAt(count) == ' ') {
			count++;
		}
		return count;
	}

	private static String replaceValue(String line, String key, String value) {
		int keyIndex = line.indexOf(key);
		if (keyIndex < 0) {
			return line;
		}
		int valueStart = keyIndex + key.length();
		int comment = line.indexOf('#', valueStart);
		String suffix = comment >= 0 ? " " + line.substring(comment) : "";
		return line.substring(0, valueStart) + " " + value + suffix;
	}
}
