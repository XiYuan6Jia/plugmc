package com.xiyuan.plugmc.economy.internal;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.stream.Stream;

/**
 * 经济状态存储：state.json 快照 + journal.jsonl 事务日志 + 月度审计账本。
 * <p>
 * 事务先写入并 flush journal，再应用到内存；启动时从快照之后回放 journal。
 */
public final class EconomyStore implements LedgerStore {
	private static final int SCHEMA_VERSION = 2;
	private static final String STATE_FILE = "state.json";
	private static final String JOURNAL_FILE = "journal.jsonl";
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
	private static final DateTimeFormatter BACKUP_TIME = DateTimeFormatter
		.ofPattern("yyyyMMdd-HHmmss")
		.withZone(ZoneId.systemDefault());
	private static final DateTimeFormatter LEDGER_MONTH = DateTimeFormatter
		.ofPattern("yyyy-MM")
		.withZone(ZoneId.systemDefault());

	private final JavaPlugin plugin;
	private final EconomyConfig config;
	private final Path dataDir;
	private final Path stateFile;
	private final Path journalFile;
	private final Path ledgerDir;
	private final Path backupDir;
	private final SnapshotData data;

	private FileChannel journalChannel;
	private long lastSeq;

	public EconomyStore(JavaPlugin plugin, EconomyConfig config) {
		this.plugin = plugin;
		this.config = config;
		this.dataDir = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
		this.stateFile = dataDir.resolve(STATE_FILE);
		this.journalFile = dataDir.resolve(JOURNAL_FILE);
		this.ledgerDir = dataDir.resolve("ledger");
		this.backupDir = dataDir.resolve("backup");
		try {
			Files.createDirectories(dataDir);
			Files.createDirectories(ledgerDir);
			Files.createDirectories(backupDir);
			this.data = loadOrMigrate();
			this.lastSeq = Math.max(0L, data.lastSeq);
			replayJournal();
			openJournal();
			verifyInvariant();
		} catch (IOException exception) {
			throw new UncheckedIOException("无法初始化经济数据目录 " + dataDir, exception);
		}
	}

	public Path dataDir() {
		return dataDir;
	}

	public Path ledgerDir() {
		return ledgerDir;
	}

	public synchronized long lastSeq() {
		return lastSeq;
	}

	public synchronized void touch(UUID playerId, String name) {
		if (playerId == null) {
			return;
		}
		PlayerMeta meta = data.players.computeIfAbsent(playerId.toString(), ignored -> new PlayerMeta());
		long now = System.currentTimeMillis();
		if (meta.createdAt == 0L) {
			meta.createdAt = now;
		}
		if (name != null && !name.isBlank()) {
			meta.name = name;
		}
		meta.lastSeen = now;
	}

	public synchronized String playerName(UUID playerId) {
		PlayerMeta meta = data.players.get(playerId.toString());
		return meta == null ? "" : meta.name;
	}

	public synchronized boolean isFrozen(UUID playerId) {
		PlayerMeta meta = data.players.get(playerId.toString());
		return meta != null && meta.frozen;
	}

	public synchronized int dailyStreak(UUID playerId) {
		PlayerMeta meta = data.players.get(playerId.toString());
		return meta == null ? 0 : meta.dailyStreak;
	}

	public synchronized String lastDailyDate(UUID playerId) {
		PlayerMeta meta = data.players.get(playerId.toString());
		return meta == null ? "" : meta.lastDailyDate;
	}

	@Override
	public synchronized boolean isAccountFrozen(String account) {
		if (account == null || !account.startsWith("player:")) {
			return false;
		}
		PlayerMeta meta = data.players.get(account.substring("player:".length()));
		return meta != null && meta.frozen;
	}

	@Override
	public synchronized boolean hasAccount(String account) {
		return data.accounts.containsKey(account);
	}

	@Override
	public synchronized BigDecimal balance(String account) {
		return parseStored(data.accounts.get(account), true);
	}

	@Override
	public synchronized void createAccount(String account) {
		data.accounts.putIfAbsent(account, zeroString());
	}

	@Override
	public synchronized long nextSeq() {
		return lastSeq + 1L;
	}

	@Override
	public synchronized void commit(JournalRecord record) {
		Map<String, BigDecimal> updates = computeUpdates(record);
		appendJournal(record);
		for (Map.Entry<String, BigDecimal> entry : updates.entrySet()) {
			data.accounts.put(entry.getKey(), entry.getValue().toPlainString());
		}
		applyStateOps(record);
		lastSeq = record.seq();
		data.lastSeq = lastSeq;
		appendLedger(record);
	}

	@Override
	public synchronized Map<String, BigDecimal> balances() {
		Map<String, BigDecimal> copy = new LinkedHashMap<>();
		for (Map.Entry<String, String> entry : data.accounts.entrySet()) {
			copy.put(entry.getKey(), parseStored(entry.getValue(), false));
		}
		return copy;
	}

	public synchronized int dailyUsed(String key) {
		return data.dailyLimits.getOrDefault(key, 0);
	}

	public synchronized void addDailyUse(String key, int amount) {
		if (amount > 0) {
			data.dailyLimits.merge(key, amount, Integer::sum);
		}
	}

	public synchronized Map<String, Integer> dailyLimits() {
		return Map.copyOf(data.dailyLimits);
	}

	public synchronized long nextMarketId() {
		return data.marketNextId++;
	}

	public synchronized long nextMailId() {
		return data.mailNextId++;
	}

	public synchronized Map<String, MarketListingDto> marketListings() {
		return Map.copyOf(data.marketListings);
	}

	public synchronized MarketListingDto marketListing(String id) {
		return data.marketListings.get(id);
	}

	public synchronized long nextOrderId() {
		return data.orderNextId++;
	}

	public synchronized long nextAuctionId() {
		return data.auctionNextId++;
	}

	public synchronized Map<String, AuctionDto> auctions() {
		return Map.copyOf(data.auctions);
	}

	public synchronized AuctionDto auction(String id) {
		return data.auctions.get(id);
	}

	public synchronized Map<String, OrderDto> orders() {
		return Map.copyOf(data.orders);
	}

	public synchronized OrderDto order(String id) {
		return data.orders.get(id);
	}

	public synchronized Map<String, MailEntryDto> mailEntries() {
		return Map.copyOf(data.mail);
	}

	public synchronized MailEntryDto mailEntry(String id) {
		return data.mail.get(id);
	}

	public synchronized void snapshot() {
		data.lastSeq = lastSeq;
		data.savedAt = System.currentTimeMillis();
		pruneDailyLimits();
		backupCurrentState();
		writeState(data);
		truncateJournal();
	}

	public synchronized Path backupNow() {
		snapshot();
		Path backup = backupCurrentState();
		rotateBackups();
		return backup;
	}

	public synchronized void close() {
		try {
			snapshot();
		} catch (RuntimeException exception) {
			plugin.getLogger().log(Level.SEVERE, "关闭前保存经济数据失败", exception);
		} finally {
			closeJournal();
		}
	}

	private SnapshotData loadOrMigrate() throws IOException {
		if (Files.isRegularFile(stateFile)) {
			SnapshotData loaded;
			try {
				loaded = GSON.fromJson(Files.readString(stateFile, StandardCharsets.UTF_8), SnapshotData.class);
			} catch (RuntimeException exception) {
				throw new IllegalStateException("无法读取经济状态文件 " + stateFile, exception);
			}
			if (loaded == null) {
				throw new IllegalStateException("经济状态文件为空：" + stateFile);
			}
			normalize(loaded);
			plugin.getLogger().info("已加载经济状态：" + stateFile);
			return loaded;
		}

		SnapshotData created = migrateLegacy();
		writeState(created);
		plugin.getLogger().info("已创建新的经济状态：" + stateFile);
		return created;
	}

	private SnapshotData migrateLegacy() {
		SnapshotData snapshot = new SnapshotData();
		snapshot.schemaVersion = SCHEMA_VERSION;
		snapshot.decimalPlaces = config.decimalPlaces();
		snapshot.startingBalance = config.startingBalance().toPlainString();
		snapshot.savedAt = System.currentTimeMillis();
		snapshot.lastSeq = 0L;

		BigDecimal playerSum = Money.zero(config.decimalPlaces());
		File legacyFile = new File(plugin.getDataFolder(), "accounts.yml");
		int migrated = 0;
		if (legacyFile.isFile()) {
			YamlConfiguration legacy = new YamlConfiguration();
			try {
				legacy.load(legacyFile);
			} catch (IOException | InvalidConfigurationException exception) {
				throw new IllegalStateException("无法读取旧经济账户文件 " + legacyFile, exception);
			}

			for (String key : legacy.getKeys(false)) {
				UUID playerId;
				try {
					playerId = UUID.fromString(key);
				} catch (IllegalArgumentException exception) {
					throw new IllegalStateException("旧经济账户文件包含无效 UUID：" + key, exception);
				}
				BigDecimal balance;
				try {
					balance = Money.parse(legacy.getString(key), config.decimalPlaces(), true);
				} catch (IllegalArgumentException exception) {
					throw new IllegalStateException("旧经济账户余额无效：" + key, exception);
				}

				snapshot.accounts.put(LedgerService.playerKey(playerId), balance.toPlainString());
				PlayerMeta meta = new PlayerMeta();
				meta.createdAt = System.currentTimeMillis();
				meta.lastSeen = meta.createdAt;
				snapshot.players.put(playerId.toString(), meta);
				playerSum = playerSum.add(balance);
				migrated++;
			}
			backupLegacy(legacyFile);
		}

		snapshot.accounts.put(LedgerService.MINT, playerSum.negate().toPlainString());
		snapshot.accounts.put(LedgerService.TAX, zeroString());
		snapshot.accounts.put(LedgerService.TREASURY, zeroString());
		snapshot.accounts.put(LedgerService.ESCROW_MARKET, zeroString());
		snapshot.accounts.put(LedgerService.ESCROW_AUCTION, zeroString());
		snapshot.accounts.put(LedgerService.ESCROW_ORDER, zeroString());
		snapshot.accounts.put(LedgerService.ESCROW_TRADE, zeroString());

		if (migrated > 0) {
			plugin.getLogger().info("已从 accounts.yml 迁移 " + migrated + " 个账户，合计 " + playerSum.toPlainString());
		}
		return snapshot;
	}

	private void normalize(SnapshotData snapshot) {
		if (snapshot.schemaVersion > SCHEMA_VERSION) {
			throw new IllegalStateException(
				"经济状态版本 " + snapshot.schemaVersion + " 高于插件支持的 " + SCHEMA_VERSION
			);
		}
		if (snapshot.decimalPlaces != config.decimalPlaces()) {
			throw new IllegalStateException(
				"currency.decimal-places 与已有经济数据不一致：配置 " + config.decimalPlaces()
					+ "，数据 " + snapshot.decimalPlaces
			);
		}
		if (snapshot.accounts == null) {
			snapshot.accounts = new LinkedHashMap<>();
		}
		if (snapshot.players == null) {
			snapshot.players = new LinkedHashMap<>();
		}
		if (snapshot.dailyLimits == null) {
			snapshot.dailyLimits = new LinkedHashMap<>();
		}
		if (snapshot.marketListings == null) {
			snapshot.marketListings = new LinkedHashMap<>();
		}
		if (snapshot.orders == null) {
			snapshot.orders = new LinkedHashMap<>();
		}
		if (snapshot.auctions == null) {
			snapshot.auctions = new LinkedHashMap<>();
		}
		if (snapshot.mail == null) {
			snapshot.mail = new LinkedHashMap<>();
		}
		snapshot.players.entrySet().removeIf(entry -> entry.getValue() == null);
		snapshot.marketListings.entrySet().removeIf(entry -> entry.getValue() == null);
		snapshot.orders.entrySet().removeIf(entry -> entry.getValue() == null);
		snapshot.auctions.entrySet().removeIf(entry -> entry.getValue() == null);
		snapshot.mail.entrySet().removeIf(entry -> entry.getValue() == null);
		snapshot.marketNextId = Math.max(1L, snapshot.marketNextId);
		snapshot.orderNextId = Math.max(1L, snapshot.orderNextId);
		snapshot.auctionNextId = Math.max(1L, snapshot.auctionNextId);
		snapshot.mailNextId = Math.max(1L, snapshot.mailNextId);
		for (String id : snapshot.marketListings.keySet()) {
			try {
				snapshot.marketNextId = Math.max(snapshot.marketNextId, Long.parseLong(id) + 1L);
			} catch (NumberFormatException ignored) {
			}
		}
		for (String id : snapshot.orders.keySet()) {
			try {
				snapshot.orderNextId = Math.max(snapshot.orderNextId, Long.parseLong(id) + 1L);
			} catch (NumberFormatException ignored) {
			}
		}
		for (String id : snapshot.auctions.keySet()) {
			try {
				snapshot.auctionNextId = Math.max(snapshot.auctionNextId, Long.parseLong(id) + 1L);
			} catch (NumberFormatException ignored) {
			}
		}
		for (String id : snapshot.mail.keySet()) {
			try {
				snapshot.mailNextId = Math.max(snapshot.mailNextId, Long.parseLong(id) + 1L);
			} catch (NumberFormatException ignored) {
			}
		}
		if (snapshot.startingBalance == null || snapshot.startingBalance.isBlank()) {
			snapshot.startingBalance = config.startingBalance().toPlainString();
		}
		for (String account : List.of(
			LedgerService.MINT,
			LedgerService.TAX,
			LedgerService.TREASURY,
			LedgerService.ESCROW_MARKET,
			LedgerService.ESCROW_AUCTION,
			LedgerService.ESCROW_ORDER,
			LedgerService.ESCROW_TRADE
		)) {
			snapshot.accounts.putIfAbsent(account, zeroString());
		}
		for (Map.Entry<String, String> entry : new ArrayList<>(snapshot.accounts.entrySet())) {
			BigDecimal value = parseStored(entry.getValue(), false);
			snapshot.accounts.put(entry.getKey(), value.toPlainString());
		}
		snapshot.schemaVersion = SCHEMA_VERSION;
	}

	private void replayJournal() throws IOException {
		if (!Files.isRegularFile(journalFile)) {
			return;
		}

		List<String> lines = Files.readAllLines(journalFile, StandardCharsets.UTF_8);
		int replayed = 0;
		for (int index = 0; index < lines.size(); index++) {
			String line = lines.get(index).trim();
			if (line.isEmpty()) {
				continue;
			}
			JournalRecord record;
			try {
				record = GSON.fromJson(line, JournalRecord.class);
			} catch (RuntimeException exception) {
				if (isLastNonEmptyLine(lines, index)) {
					plugin.getLogger().warning("忽略 journal 末尾不完整事务：" + exception.getMessage());
					break;
				}
				throw new IllegalStateException("journal 第 " + (index + 1) + " 行无法解析", exception);
			}
			if (record == null || record.entries() == null) {
				plugin.getLogger().warning("忽略 journal 第 " + (index + 1) + " 行空事务");
				continue;
			}
			if (record.seq() <= lastSeq) {
				continue;
			}
			applyEntries(record);
			applyStateOps(record);
			lastSeq = record.seq();
			replayed++;
		}
		data.lastSeq = lastSeq;
		if (replayed > 0) {
			plugin.getLogger().info("已回放 " + replayed + " 笔经济事务");
		}
	}

	private void applyEntries(JournalRecord record) {
		Map<String, BigDecimal> updates = computeUpdates(record);
		for (Map.Entry<String, BigDecimal> entry : updates.entrySet()) {
			data.accounts.put(entry.getKey(), entry.getValue().toPlainString());
		}
		data.lastSeq = Math.max(data.lastSeq, record.seq());
	}

	private void applyStateOps(JournalRecord record) {
		for (JournalRecord.StateOp op : record.stateOps()) {
			applyStateOp(op);
		}
	}

	private void applyStateOp(JournalRecord.StateOp op) {
		if (op == null || op.type() == null || op.type().isBlank()) {
			throw new IllegalStateException("事务包含无效状态操作");
		}
		switch (op.type()) {
			case "MARKET_ADD", "MARKET_UPDATE" -> {
				if (op.payload() == null || op.payload().isBlank()) {
					throw new IllegalStateException("市场状态操作缺少 payload");
				}
				MarketListingDto listing = GSON.fromJson(op.payload(), MarketListingDto.class);
				if (listing == null || listing.id == null) {
					throw new IllegalStateException("市场状态操作数据无效");
				}
				data.marketListings.put(listing.id, listing);
				bumpMarketId(listing.id);
			}
			case "MARKET_REMOVE" -> data.marketListings.remove(op.id());
			case "ORDER_ADD", "ORDER_UPDATE" -> {
				if (op.payload() == null || op.payload().isBlank()) {
					throw new IllegalStateException("收购订单状态操作缺少 payload");
				}
				OrderDto order = GSON.fromJson(op.payload(), OrderDto.class);
				if (order == null || order.id == null) {
					throw new IllegalStateException("收购订单状态操作数据无效");
				}
				data.orders.put(order.id, order);
				bumpOrderId(order.id);
			}
			case "ORDER_REMOVE" -> data.orders.remove(op.id());
			case "AUCTION_ADD", "AUCTION_UPDATE" -> {
				if (op.payload() == null || op.payload().isBlank()) {
					throw new IllegalStateException("拍卖状态操作缺少 payload");
				}
				AuctionDto auction = GSON.fromJson(op.payload(), AuctionDto.class);
				if (auction == null || auction.id == null) {
					throw new IllegalStateException("拍卖状态操作数据无效");
				}
				data.auctions.put(auction.id, auction);
				bumpAuctionId(auction.id);
			}
			case "AUCTION_REMOVE" -> data.auctions.remove(op.id());
			case "MAIL_ADD" -> {
				if (op.payload() == null || op.payload().isBlank()) {
					throw new IllegalStateException("邮箱状态操作缺少 payload");
				}
				MailEntryDto entry = GSON.fromJson(op.payload(), MailEntryDto.class);
				if (entry == null || entry.id == null || entry.owner == null) {
					throw new IllegalStateException("邮箱状态操作数据无效");
				}
				data.mail.put(entry.id, entry);
				bumpMailId(entry.id);
			}
			case "MAIL_REMOVE" -> data.mail.remove(op.id());
			case "PLAYER_FROZEN" -> {
				if (op.id() == null || op.id().isBlank()) {
					throw new IllegalStateException("冻结状态操作缺少玩家 UUID");
				}
				PlayerMeta meta = data.players.computeIfAbsent(op.id(), ignored -> new PlayerMeta());
				meta.frozen = "1".equals(op.payload());
			}
			case "PLAYER_DAILY" -> {
				if (op.id() == null || op.id().isBlank()) {
					throw new IllegalStateException("签到状态操作缺少玩家 UUID");
				}
				PlayerMeta meta = data.players.computeIfAbsent(op.id(), ignored -> new PlayerMeta());
				String payload = op.payload() == null ? "" : op.payload();
				int split = payload.indexOf('|');
				if (split <= 0) {
					throw new IllegalStateException("签到状态数据无效");
				}
				try {
					meta.dailyStreak = Integer.parseInt(payload.substring(0, split));
				} catch (NumberFormatException exception) {
					throw new IllegalStateException("签到连签天数无效", exception);
				}
				meta.lastDailyDate = payload.substring(split + 1);
			}
			default -> throw new IllegalStateException("未知状态操作：" + op.type());
		}
	}

	private void bumpMarketId(String rawId) {
		try {
			data.marketNextId = Math.max(data.marketNextId, Long.parseLong(rawId) + 1L);
		} catch (NumberFormatException ignored) {
		}
	}

	private void bumpMailId(String rawId) {
		try {
			data.mailNextId = Math.max(data.mailNextId, Long.parseLong(rawId) + 1L);
		} catch (NumberFormatException ignored) {
		}
	}

	private void bumpOrderId(String rawId) {
		try {
			data.orderNextId = Math.max(data.orderNextId, Long.parseLong(rawId) + 1L);
		} catch (NumberFormatException ignored) {
		}
	}

	private void bumpAuctionId(String rawId) {
		try {
			data.auctionNextId = Math.max(data.auctionNextId, Long.parseLong(rawId) + 1L);
		} catch (NumberFormatException ignored) {
		}
	}

	private Map<String, BigDecimal> computeUpdates(JournalRecord record) {
		Map<String, BigDecimal> updates = new LinkedHashMap<>();
		for (JournalRecord.Entry entry : record.entries()) {
			if (entry == null || entry.account() == null || entry.account().isBlank() || entry.delta() == null) {
				throw new IllegalStateException("账本事务包含无效分录：" + record.txId());
			}
			BigDecimal current = updates.get(entry.account());
			if (current == null) {
				current = parseStored(data.accounts.get(entry.account()), true);
				if (current == null) {
					current = Money.zero(config.decimalPlaces());
				}
			}
			BigDecimal next = Money.scale(current.add(entry.delta()), config.decimalPlaces());
			if (next.signum() < 0 && !LedgerService.MINT.equals(entry.account())) {
				throw new IllegalStateException(
					"账户余额不能为负：" + entry.account() + " -> " + next.toPlainString()
				);
			}
			updates.put(entry.account(), next);
		}
		return updates;
	}

	private void appendJournal(JournalRecord record) {
		if (journalChannel == null) {
			throw new IllegalStateException("journal 尚未打开");
		}
		byte[] bytes = (GSON.toJson(record) + "\n").getBytes(StandardCharsets.UTF_8);
		ByteBuffer buffer = ByteBuffer.wrap(bytes);
		try {
			while (buffer.hasRemaining()) {
				journalChannel.write(buffer);
			}
			journalChannel.force(false);
		} catch (IOException exception) {
			throw new UncheckedIOException("无法写入经济事务日志", exception);
		}
	}

	private void appendLedger(JournalRecord record) {
		Path ledgerFile = ledgerDir.resolve(LEDGER_MONTH.format(Instant.ofEpochMilli(record.timestamp())) + ".jsonl");
		try {
			Files.createDirectories(ledgerDir);
			Files.writeString(
				ledgerFile,
				GSON.toJson(record) + System.lineSeparator(),
				StandardCharsets.UTF_8,
				StandardOpenOption.CREATE,
				StandardOpenOption.APPEND
			);
		} catch (IOException exception) {
			plugin.getLogger().warning("无法写入审计账本 " + ledgerFile + "：" + exception.getMessage());
		}
	}

	private void openJournal() throws IOException {
		journalChannel = FileChannel.open(
			journalFile,
			StandardOpenOption.CREATE,
			StandardOpenOption.WRITE,
			StandardOpenOption.APPEND
		);
	}

	private void closeJournal() {
		if (journalChannel == null) {
			return;
		}
		try {
			journalChannel.close();
		} catch (IOException exception) {
			plugin.getLogger().warning("关闭经济事务日志失败：" + exception.getMessage());
		} finally {
			journalChannel = null;
		}
	}

	private void truncateJournal() {
		closeJournal();
		try {
			List<String> remaining = new ArrayList<>();
			if (Files.isRegularFile(journalFile)) {
				for (String line : Files.readAllLines(journalFile, StandardCharsets.UTF_8)) {
					if (line.isBlank()) {
						continue;
					}
					try {
						JournalRecord record = GSON.fromJson(line, JournalRecord.class);
						if (record != null && record.seq() > lastSeq) {
							remaining.add(line);
						}
					} catch (RuntimeException exception) {
						plugin.getLogger().warning("快照时丢弃无法解析的 journal 行：" + exception.getMessage());
					}
				}
			}
			Path temporary = journalFile.resolveSibling(journalFile.getFileName() + ".tmp");
			Files.write(
				temporary,
				remaining,
				StandardCharsets.UTF_8,
				StandardOpenOption.CREATE,
				StandardOpenOption.TRUNCATE_EXISTING,
				StandardOpenOption.WRITE
			);
			moveReplace(temporary, journalFile);
		} catch (IOException exception) {
			plugin.getLogger().warning("截断经济事务日志失败：" + exception.getMessage());
		} finally {
			try {
				openJournal();
			} catch (IOException exception) {
				throw new UncheckedIOException("无法重新打开经济事务日志", exception);
			}
		}
	}

	private void writeState(SnapshotData snapshot) {
		try {
			Path temporary = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
			Files.writeString(
				temporary,
				GSON.toJson(snapshot),
				StandardCharsets.UTF_8,
				StandardOpenOption.CREATE,
				StandardOpenOption.TRUNCATE_EXISTING,
				StandardOpenOption.WRITE
			);
			try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
				channel.force(true);
			}
			moveReplace(temporary, stateFile);
		} catch (IOException exception) {
			throw new UncheckedIOException("无法保存经济状态文件 " + stateFile, exception);
		}
	}

	private Path backupCurrentState() {
		if (!Files.isRegularFile(stateFile)) {
			return null;
		}
		String name = "state-" + BACKUP_TIME.format(Instant.now()) + ".json";
		Path target = backupDir.resolve(name);
		try {
			Files.createDirectories(backupDir);
			Files.copy(stateFile, target, StandardCopyOption.REPLACE_EXISTING);
			rotateBackups();
			return target;
		} catch (IOException exception) {
			plugin.getLogger().warning("备份经济状态失败：" + exception.getMessage());
			return null;
		}
	}

	private void backupLegacy(File legacyFile) {
		try {
			Files.createDirectories(backupDir);
			String name = "accounts-" + BACKUP_TIME.format(Instant.now()) + ".yml";
			Files.copy(legacyFile.toPath(), backupDir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException exception) {
			plugin.getLogger().warning("备份旧账户文件失败：" + exception.getMessage());
		}
	}

	private void rotateBackups() {
		try (Stream<Path> stream = Files.list(backupDir)) {
			List<Path> files = stream
				.filter(Files::isRegularFile)
				.filter(path -> path.getFileName().toString().startsWith("state-"))
				.filter(path -> path.getFileName().toString().endsWith(".json"))
				.sorted(Comparator.comparing(path -> path.getFileName().toString()))
				.toList();
			int excess = files.size() - config.backupCount();
			for (int index = 0; index < excess; index++) {
				Files.deleteIfExists(files.get(index));
			}
		} catch (IOException exception) {
			plugin.getLogger().warning("清理经济备份失败：" + exception.getMessage());
		}
	}

	private void verifyInvariant() {
		BigDecimal total = Money.zero(config.decimalPlaces());
		for (Map.Entry<String, String> entry : data.accounts.entrySet()) {
			BigDecimal value = parseStored(entry.getValue(), false);
			total = total.add(value);
			boolean player = entry.getKey().startsWith("player:");
			if (value.signum() < 0 && !LedgerService.MINT.equals(entry.getKey())) {
				throw new IllegalStateException("经济账户出现负余额：" + entry.getKey() + " = " + value.toPlainString());
			}
			if (player && value.signum() < 0) {
				throw new IllegalStateException("玩家账户出现负余额：" + entry.getKey());
			}
		}
		if (total.signum() != 0) {
			throw new IllegalStateException(
				"经济账本不平衡：所有账户合计 " + total.toPlainString() + "，应为 0"
			);
		}
		plugin.getLogger().info(
			"经济账本校验通过：账户 " + data.accounts.size() + " 个，合计 " + total.toPlainString()
		);
	}

	private void pruneDailyLimits() {
		String today = LocalDate.now().toString();
		data.dailyLimits.keySet().removeIf(key -> {
			String[] parts = key.split("\\|", 4);
			return parts.length >= 2
				&& parts[1].matches("\\d{4}-\\d{2}-\\d{2}")
				&& !parts[1].equals(today);
		});
	}

	private BigDecimal parseStored(String raw, boolean nullable) {
		if (raw == null || raw.isBlank()) {
			if (nullable) {
				return null;
			}
			return Money.zero(config.decimalPlaces());
		}
		try {
			return Money.scale(new BigDecimal(raw.trim()), config.decimalPlaces());
		} catch (RuntimeException exception) {
			throw new IllegalStateException("经济余额格式无效：" + raw, exception);
		}
	}

	private String zeroString() {
		return Money.zero(config.decimalPlaces()).toPlainString();
	}

	private static boolean isLastNonEmptyLine(List<String> lines, int index) {
		for (int next = index + 1; next < lines.size(); next++) {
			if (!lines.get(next).isBlank()) {
				return false;
			}
		}
		return true;
	}

	private static void moveReplace(Path source, Path target) throws IOException {
		try {
			Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException exception) {
			Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	static final class SnapshotData {
		int schemaVersion = SCHEMA_VERSION;
		long lastSeq;
		long savedAt;
		int decimalPlaces;
		String startingBalance;
		Map<String, String> accounts = new LinkedHashMap<>();
		Map<String, PlayerMeta> players = new LinkedHashMap<>();
		Map<String, Integer> dailyLimits = new LinkedHashMap<>();
		Map<String, MarketListingDto> marketListings = new LinkedHashMap<>();
		Map<String, OrderDto> orders = new LinkedHashMap<>();
		Map<String, AuctionDto> auctions = new LinkedHashMap<>();
		Map<String, MailEntryDto> mail = new LinkedHashMap<>();
		long marketNextId = 1L;
		long orderNextId = 1L;
		long auctionNextId = 1L;
		long mailNextId = 1L;
	}

	public static final class MarketListingDto {
		public String id;
		public String seller;
		public String sellerName = "";
		public String item;
		public String unitPrice = "0.00";
		public long createdAt;
		public long expiresAt;
		public String status = "ACTIVE";
		public boolean escrowPending;
	}

	public static final class OrderDto {
		public String id;
		public String buyer;
		public String buyerName = "";
		public String item;
		public String unitPrice = "0.00";
		public int remaining;
		public long createdAt;
		public long expiresAt;
		public String status = "ACTIVE";
	}

	public static final class AuctionDto {
		public String id;
		public String seller;
		public String sellerName = "";
		public String item;
		public String startingPrice = "0.00";
		public String buyoutPrice = "0.00";
		public String highestBidder = "";
		public String highestBidderName = "";
		public String highestBid = "0.00";
		public long createdAt;
		public long expiresAt;
		public String status = "ACTIVE";
		public int extensions;
		public int bidCount;
	}

	public static final class MailEntryDto {
		public String id;
		public String owner;
		public String item;
		public String money = "0.00";
		public String source = "";
		public long createdAt;
		public long expiresAt;
		public boolean claimed;
	}

	static final class PlayerMeta {
		String name = "";
		long createdAt;
		long lastSeen;
		boolean frozen;
		int dailyStreak;
		String lastDailyDate = "";
	}
}
