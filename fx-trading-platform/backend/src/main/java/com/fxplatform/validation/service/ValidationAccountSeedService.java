package com.fxplatform.validation.service;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.validation.service.ValidationAccountSeedGateway.AccountView;
import com.fxplatform.validation.service.ValidationAccountSeedGateway.AssetLedgerView;
import com.fxplatform.validation.service.ValidationAccountSeedGateway.CashLedgerView;
import com.fxplatform.validation.service.ValidationAccountSeedGateway.PristineSnapshot;
import com.fxplatform.validation.service.ValidationAccountSeedGateway.WalletView;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("validation")
public class ValidationAccountSeedService {

  private static final String INVALID = "VALIDATION_SEED_INVALID";
  private static final String CONFLICT = "VALIDATION_SEED_CONFLICT";
  private static final String NOT_PRISTINE = "VALIDATION_ACCOUNT_NOT_PRISTINE";
  private static final String USDT = "USDT";
  private static final int MONEY_SCALE = 8;
  private static final int MONEY_PRECISION = 24;
  private static final BigDecimal ZERO = money("0");
  private static final BigDecimal INITIAL = money("50000");
  private static final Pattern ASSET = Pattern.compile("[A-Z0-9]{2,32}");

  private final ValidationAccountSeedGateway gateway;

  public ValidationAccountSeedService(ValidationAccountSeedGateway gateway) {
    this.gateway = gateway;
  }

  public ValidationAccountSeedReceipt seed(
      UUID accountId,
      ValidationAccountSeedRequest request
  ) {
    CanonicalSeed canonical = canonicalize(accountId, request);
    requireSupported(canonical.spotBalances().keySet(), gateway.supportedSpotAssets());

    return gateway.withLockedAccount(accountId, locked -> {
      var existing = locked.findReceipt(canonical.seedId());
      if (existing.isPresent()) {
        return replayOrConflict(existing.orElseThrow(), canonical);
      }

      requireSupported(canonical.spotBalances().keySet(), locked.supportedSpotAssets());
      PristineSnapshot snapshot = locked.snapshot();
      requirePristine(accountId, snapshot);

      BigDecimal perpetualDelta = money(
          canonical.perpetualUsdtBalance().subtract(snapshot.account().balance()));
      locked.writePerpetualUsdtTarget(
          canonical.seedId(),
          canonical.perpetualUsdtBalance(),
          perpetualDelta);

      Map<String, BigDecimal> currentSpot = currentSpotBalances(snapshot.wallets());
      canonical.spotBalances().forEach((asset, target) -> {
        BigDecimal current = currentSpot.getOrDefault(asset, ZERO);
        locked.writeSpotTarget(
            canonical.seedId(),
            asset,
            target,
            money(target.subtract(current)));
      });

      ValidationAccountSeedReceipt receipt = new ValidationAccountSeedReceipt(
          canonical.seedId(),
          accountId,
          canonical.fingerprint(),
          canonical.perpetualUsdtBalance(),
          canonical.spotBalances());
      locked.saveReceipt(receipt);
      return receipt;
    });
  }

  private static CanonicalSeed canonicalize(
      UUID accountId,
      ValidationAccountSeedRequest request
  ) {
    if (accountId == null || request == null || request.seedId() == null
        || request.initialBalances() == null || request.initialBalances().isEmpty()) {
      throw invalid("Seed id, account id and initial balances are required");
    }

    TreeMap<String, BigDecimal> sorted = new TreeMap<>();
    for (Map.Entry<String, BigDecimal> entry : request.initialBalances().entrySet()) {
      String asset = canonicalAsset(entry.getKey());
      BigDecimal amount = canonicalAmount(entry.getValue());
      if (sorted.putIfAbsent(asset, amount) != null) {
        throw invalid("Asset keys must be unique after normalization");
      }
    }
    if (!sorted.containsKey(USDT)) {
      throw invalid("USDT initial balance is required");
    }

    Map<String, BigDecimal> spotBalances = new LinkedHashMap<>(sorted);
    BigDecimal perpetualUsdt = spotBalances.get(USDT);
    String fingerprint = fingerprint(accountId, perpetualUsdt, spotBalances);
    return new CanonicalSeed(
        request.seedId(),
        accountId,
        fingerprint,
        perpetualUsdt,
        spotBalances);
  }

  private static String canonicalAsset(String rawAsset) {
    if (rawAsset == null) {
      throw invalid("Asset is required");
    }
    String asset = rawAsset.trim().toUpperCase(Locale.ROOT);
    if (!ASSET.matcher(asset).matches()) {
      throw invalid("Asset is invalid");
    }
    return asset;
  }

  private static BigDecimal canonicalAmount(BigDecimal rawAmount) {
    if (rawAmount == null || rawAmount.signum() < 0 || rawAmount.scale() > MONEY_SCALE) {
      throw invalid("Balance must be non-negative with at most eight decimal places");
    }
    BigDecimal amount;
    try {
      amount = rawAmount.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
    } catch (ArithmeticException ex) {
      throw new BusinessException(INVALID, "Balance precision is invalid", ex);
    }
    if (amount.precision() > MONEY_PRECISION) {
      throw invalid("Balance exceeds NUMERIC(24,8)");
    }
    return amount;
  }

  private static void requireSupported(Set<String> requested, Set<String> supported) {
    if (supported == null || !supported.containsAll(requested)) {
      throw invalid("Seed contains an unsupported Spot asset");
    }
  }

  private static ValidationAccountSeedReceipt replayOrConflict(
      ValidationAccountSeedReceipt existing,
      CanonicalSeed canonical
  ) {
    boolean exact = existing != null
        && canonical.seedId().equals(existing.seedId())
        && canonical.accountId().equals(existing.accountId())
        && canonical.fingerprint().equals(existing.requestFingerprint())
        && sameMoney(canonical.perpetualUsdtBalance(), existing.perpetualUsdtBalance())
        && sameBalances(canonical.spotBalances(), existing.spotBalances());
    if (!exact) {
      throw new BusinessException(
          CONFLICT,
          "Seed id is already bound to a different canonical request");
    }
    return existing;
  }

  private static void requirePristine(UUID accountId, PristineSnapshot snapshot) {
    if (!isPristine(accountId, snapshot)) {
      throw new BusinessException(
          NOT_PRISTINE,
          "Validation seed requires a newly registered pristine Demo account");
    }
  }

  private static boolean isPristine(UUID accountId, PristineSnapshot snapshot) {
    if (snapshot == null || !canonicalAccount(accountId, snapshot.account())) {
      return false;
    }
    if (snapshot.orderHistoryCount() != 0
        || snapshot.tradeHistoryCount() != 0
        || snapshot.perpetualPositionHistoryCount() != 0
        || snapshot.spotPositionHistoryCount() != 0) {
      return false;
    }
    return canonicalWallets(snapshot.wallets())
        && canonicalCashLedger(accountId, snapshot.cashLedger())
        && canonicalAssetLedger(accountId, snapshot.assetLedger());
  }

  private static boolean canonicalAccount(UUID accountId, AccountView account) {
    return account != null
        && accountId.equals(account.id())
        && "DEMO".equals(account.accountType())
        && "ACTIVE".equals(account.status())
        && USDT.equals(account.baseCurrency())
        && sameMoney(INITIAL, account.balance())
        && sameMoney(INITIAL, account.equity())
        && sameMoney(ZERO, account.usedMargin())
        && sameMoney(INITIAL, account.freeMargin())
        && account.marginLevel() == null
        && account.leverage() == 10
        && "ONE_WAY".equals(account.positionMode())
        && account.demoGeneration() == 1L
        && account.resetAt() == null;
  }

  private static boolean canonicalWallets(List<WalletView> wallets) {
    if (wallets == null || wallets.size() != 1) {
      return false;
    }
    WalletView wallet = wallets.getFirst();
    return wallet != null
        && "SPOT".equals(wallet.walletType())
        && USDT.equals(wallet.asset())
        && sameMoney(INITIAL, wallet.total())
        && sameMoney(INITIAL, wallet.available())
        && sameMoney(ZERO, wallet.locked());
  }

  private static boolean canonicalCashLedger(UUID accountId, List<CashLedgerView> entries) {
    if (entries == null || entries.size() != 1) {
      return false;
    }
    CashLedgerView entry = entries.getFirst();
    return entry != null
        && "DEMO_INIT".equals(entry.entryType())
        && "DEMO_INIT".equals(entry.operationType())
        && sameMoney(INITIAL, entry.amount())
        && sameMoney(INITIAL, entry.balanceAfter())
        && USDT.equals(entry.currency())
        && "DEMO_ACCOUNT".equals(entry.referenceType())
        && accountId.equals(entry.referenceId());
  }

  private static boolean canonicalAssetLedger(UUID accountId, List<AssetLedgerView> entries) {
    if (entries == null || entries.size() != 1) {
      return false;
    }
    AssetLedgerView entry = entries.getFirst();
    return entry != null
        && "SPOT".equals(entry.walletType())
        && USDT.equals(entry.asset())
        && "DEMO_INIT".equals(entry.entryType())
        && "DEMO_INIT".equals(entry.operationType())
        && sameMoney(INITIAL, entry.amount())
        && sameMoney(INITIAL, entry.balanceAfter())
        && "DEMO_ACCOUNT".equals(entry.referenceType())
        && accountId.equals(entry.referenceId());
  }

  private static Map<String, BigDecimal> currentSpotBalances(List<WalletView> wallets) {
    Map<String, BigDecimal> balances = new LinkedHashMap<>();
    for (WalletView wallet : wallets) {
      if (wallet != null && "SPOT".equals(wallet.walletType()) && wallet.asset() != null) {
        balances.put(wallet.asset(), wallet.total());
      }
    }
    return balances;
  }

  private static boolean sameBalances(
      Map<String, BigDecimal> expected,
      Map<String, BigDecimal> actual
  ) {
    if (expected == null || actual == null || !expected.keySet().equals(actual.keySet())) {
      return false;
    }
    return expected.entrySet().stream()
        .allMatch(entry -> sameMoney(entry.getValue(), actual.get(entry.getKey())));
  }

  private static boolean sameMoney(BigDecimal expected, BigDecimal actual) {
    return expected != null && actual != null && expected.compareTo(actual) == 0;
  }

  private static String fingerprint(
      UUID accountId,
      BigDecimal perpetualUsdt,
      Map<String, BigDecimal> spotBalances
  ) {
    List<String> lines = new ArrayList<>();
    lines.add("model=validation-account-seed-v1");
    lines.add("accountId=" + accountId);
    lines.add("perpetualUsdtBalance=" + perpetualUsdt.toPlainString());
    spotBalances.forEach((asset, amount) ->
        lines.add("spotBalances." + asset + "=" + amount.toPlainString()));
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is unavailable", ex);
    }
  }

  private static BigDecimal money(String value) {
    return new BigDecimal(value).setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
  }

  private static BigDecimal money(BigDecimal value) {
    return value.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
  }

  private static BusinessException invalid(String message) {
    return new BusinessException(INVALID, message);
  }

  private record CanonicalSeed(
      UUID seedId,
      UUID accountId,
      String fingerprint,
      BigDecimal perpetualUsdtBalance,
      Map<String, BigDecimal> spotBalances
  ) {
  }
}
