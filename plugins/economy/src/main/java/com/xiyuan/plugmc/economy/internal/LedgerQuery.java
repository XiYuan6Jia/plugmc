package com.xiyuan.plugmc.economy.internal;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 只读查询月度审计账本，用于 {@code /eco audit <玩家>}。
 */
public final class LedgerQuery {
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

	private final Path ledgerDir;

	public LedgerQuery(Path ledgerDir) {
		this.ledgerDir = ledgerDir;
	}

	public List<Entry> forAccount(String account, int limit) {
		if (account == null || account.isBlank() || limit <= 0) {
			return List.of();
		}
		List<Path> files = ledgerFiles();
		List<Entry> result = new ArrayList<>();
		for (Path file : files) {
			List<String> lines;
			try {
				lines = Files.readAllLines(file, StandardCharsets.UTF_8);
			} catch (IOException exception) {
				throw new UncheckedIOException("无法读取审计账本 " + file, exception);
			}
			for (int index = lines.size() - 1; index >= 0 && result.size() < limit; index--) {
				JournalRecord record = parse(lines.get(index));
				if (record == null) {
					continue;
				}
				BigDecimal delta = null;
				for (JournalRecord.Entry entry : record.entries()) {
					if (entry != null && account.equals(entry.account())) {
						delta = entry.delta();
						break;
					}
				}
				if (delta != null) {
					result.add(new Entry(record.seq(), record.timestamp(), record.reason(), record.ref(), delta));
				}
			}
			if (result.size() >= limit) {
				break;
			}
		}
		return List.copyOf(result);
	}

	public int totalEntries(String account) {
		if (account == null || account.isBlank()) {
			return 0;
		}
		int count = 0;
		for (Path file : ledgerFiles()) {
			try {
				for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
					JournalRecord record = parse(line);
					if (record == null) {
						continue;
					}
					for (JournalRecord.Entry entry : record.entries()) {
						if (entry != null && account.equals(entry.account())) {
							count++;
							break;
						}
					}
				}
			} catch (IOException exception) {
				throw new UncheckedIOException("无法读取审计账本 " + file, exception);
			}
		}
		return count;
	}

	private List<Path> ledgerFiles() {
		if (!Files.isDirectory(ledgerDir)) {
			return List.of();
		}
		try (Stream<Path> stream = Files.list(ledgerDir)) {
			return stream
				.filter(Files::isRegularFile)
				.filter(path -> path.getFileName().toString().endsWith(".jsonl"))
				.sorted(Comparator.comparing((Path path) -> path.getFileName().toString()).reversed())
				.toList();
		} catch (IOException exception) {
			throw new UncheckedIOException("无法列出审计账本目录 " + ledgerDir, exception);
		}
	}

	private JournalRecord parse(String line) {
		if (line == null || line.isBlank()) {
			return null;
		}
		try {
			return GSON.fromJson(line, JournalRecord.class);
		} catch (RuntimeException exception) {
			return null;
		}
	}

	public record Entry(long seq, long timestamp, String reason, String ref, BigDecimal delta) {
	}
}
