package com.xiyuan.plugmc.economy.internal;

import com.xiyuan.plugmc.economy.api.EconomyService;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class EconomyServiceImpl implements EconomyService {
	private final File accountsFile;
	private final BigDecimal startingBalance;
	private final String currencyName;
	private final String currencySymbol;
	private final int decimalPlaces;
	private final Map<UUID, BigDecimal> balances = new HashMap<>();

	public EconomyServiceImpl(JavaPlugin plugin) {
		this.accountsFile = new File(plugin.getDataFolder(), "accounts.yml");
		this.currencyName = plugin.getConfig().getString("currency.name", "金币");
		this.currencySymbol = plugin.getConfig().getString("currency.symbol", "$");
		this.decimalPlaces = plugin.getConfig().getInt("currency.decimal-places", 2);
		if (decimalPlaces < 0 || decimalPlaces > 6) {
			throw new IllegalStateException("currency.decimal-places 必须在 0 到 6 之间");
		}
		this.startingBalance = parseConfiguredAmount(
			plugin.getConfig().getString("starting-balance", "100.00"),
			"starting-balance",
			true
		);
		loadAccounts();
	}

	@Override
	public synchronized BigDecimal getBalance(UUID playerId) {
		return balances.getOrDefault(requirePlayerId(playerId), startingBalance);
	}

	@Override
	public synchronized void createAccount(UUID playerId) {
		requirePlayerId(playerId);
		if (!balances.containsKey(playerId)) {
			balances.put(playerId, startingBalance);
			persistWithRollback(previousBalance(playerId, null));
		}
	}

	@Override
	public synchronized boolean deposit(UUID playerId, BigDecimal amount) {
		playerId = requirePlayerId(playerId);
		amount = requirePositiveAmount(amount);
		BigDecimal previous = balances.getOrDefault(playerId, startingBalance);
		BigDecimal previousStored = balances.get(playerId);
		balances.put(playerId, previous.add(amount));
		persistWithRollback(previousBalance(playerId, previousStored));
		return true;
	}

	@Override
	public synchronized boolean withdraw(UUID playerId, BigDecimal amount) {
		playerId = requirePlayerId(playerId);
		amount = requirePositiveAmount(amount);
		BigDecimal previous = balances.getOrDefault(playerId, startingBalance);
		if (previous.compareTo(amount) < 0) {
			return false;
		}
		BigDecimal previousStored = balances.get(playerId);
		balances.put(playerId, previous.subtract(amount));
		persistWithRollback(previousBalance(playerId, previousStored));
		return true;
	}

	@Override
	public synchronized boolean transfer(UUID fromPlayerId, UUID toPlayerId, BigDecimal amount) {
		fromPlayerId = requirePlayerId(fromPlayerId);
		toPlayerId = requirePlayerId(toPlayerId);
		amount = requirePositiveAmount(amount);
		if (fromPlayerId.equals(toPlayerId)) {
			return false;
		}

		BigDecimal previousStoredSenderBalance = balances.get(fromPlayerId);
		BigDecimal previousSenderBalance = getBalance(fromPlayerId);
		if (previousSenderBalance.compareTo(amount) < 0) {
			return false;
		}

		BigDecimal previousStoredRecipientBalance = balances.get(toPlayerId);
		BigDecimal previousRecipientBalance = getBalance(toPlayerId);
		balances.put(fromPlayerId, previousSenderBalance.subtract(amount));
		balances.put(toPlayerId, previousRecipientBalance.add(amount));
		Map<UUID, BigDecimal> previousBalances = new HashMap<>();
		previousBalances.put(fromPlayerId, previousStoredSenderBalance);
		previousBalances.put(toPlayerId, previousStoredRecipientBalance);
		persistWithRollback(previousBalances);
		return true;
	}

	@Override
	public synchronized void setBalance(UUID playerId, BigDecimal amount) {
		playerId = requirePlayerId(playerId);
		amount = requireNonNegativeAmount(amount);
		BigDecimal previous = balances.put(playerId, amount);
		persistWithRollback(previousBalance(playerId, previous));
	}

	@Override
	public String format(BigDecimal amount) {
		return currencySymbol + requireNonNegativeAmount(amount).toPlainString();
	}

	@Override
	public String getCurrencyName() {
		return currencyName;
	}

	private void loadAccounts() {
		if (!accountsFile.exists()) {
			return;
		}

		YamlConfiguration data = new YamlConfiguration();
		try {
			data.load(accountsFile);
		} catch (IOException | InvalidConfigurationException exception) {
			throw new IllegalStateException("无法读取经济账户文件 " + accountsFile, exception);
		}

		for (String key : data.getKeys(false)) {
			try {
				UUID playerId = UUID.fromString(key);
				String rawBalance = data.getString(key);
				balances.put(playerId, parseConfiguredAmount(rawBalance, "账户 " + key, true));
			} catch (IllegalArgumentException exception) {
				throw new IllegalStateException("经济账户文件包含无效账户或余额：" + key, exception);
			}
		}
	}

	private void persistWithRollback(Map<UUID, BigDecimal> previousBalances) {
		try {
			persist();
		} catch (UncheckedIOException exception) {
			for (Map.Entry<UUID, BigDecimal> entry : previousBalances.entrySet()) {
				if (entry.getValue() == null) {
					balances.remove(entry.getKey());
				} else {
					balances.put(entry.getKey(), entry.getValue());
				}
			}
			throw exception;
		}
	}

	private void persist() {
		Path target = accountsFile.toPath();
		Path temporary = target.resolveSibling(accountsFile.getName() + ".tmp");
		YamlConfiguration data = new YamlConfiguration();
		balances.entrySet().stream()
			.sorted(Map.Entry.comparingByKey())
			.forEach(entry -> data.set(entry.getKey().toString(), entry.getValue().toPlainString()));

		try {
			Files.createDirectories(target.getParent());
			data.save(temporary.toFile());
			try {
				Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException exception) {
				Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException exception) {
			try {
				Files.deleteIfExists(temporary);
			} catch (IOException cleanupException) {
				exception.addSuppressed(cleanupException);
			}
			throw new UncheckedIOException("无法保存经济账户文件 " + accountsFile, exception);
		}
	}

	private BigDecimal parseConfiguredAmount(String raw, String setting, boolean allowZero) {
		if (raw == null) {
			throw new IllegalStateException(setting + " 必须是有效金额");
		}
		try {
			return validateAmount(new BigDecimal(raw), setting, allowZero);
		} catch (NumberFormatException exception) {
			throw new IllegalStateException(setting + " 必须是有效金额", exception);
		}
	}

	private BigDecimal requirePositiveAmount(BigDecimal amount) {
		return validateAmount(amount, "amount", false);
	}

	private BigDecimal requireNonNegativeAmount(BigDecimal amount) {
		return validateAmount(amount, "amount", true);
	}

	private BigDecimal validateAmount(BigDecimal amount, String setting, boolean allowZero) {
		if (amount == null || amount.scale() > decimalPlaces
			|| (allowZero ? amount.signum() < 0 : amount.signum() <= 0)) {
			throw new IllegalArgumentException(setting + " 必须是"
				+ (allowZero ? "非负" : "正")
				+ "金额，且最多有 " + decimalPlaces + " 位小数");
		}
		return amount.setScale(decimalPlaces, RoundingMode.UNNECESSARY);
	}

	private UUID requirePlayerId(UUID playerId) {
		if (playerId == null) {
			throw new IllegalArgumentException("playerId 不能为空");
		}
		return playerId;
	}

	private Map<UUID, BigDecimal> previousBalance(UUID playerId, BigDecimal balance) {
		Map<UUID, BigDecimal> previousBalances = new HashMap<>();
		previousBalances.put(playerId, balance);
		return previousBalances;
	}

}
