package com.fxplatform.validation.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.execution.DemoExecutionGuard;
import com.fxplatform.ledger.entity.LedgerEntryEntity;
import com.fxplatform.ledger.repository.LedgerEntryRepository;
import com.fxplatform.ledger.service.LedgerService;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.market.repository.SymbolRepository;
import com.fxplatform.trading.repository.OrderRepository;
import com.fxplatform.trading.repository.PositionRepository;
import com.fxplatform.trading.repository.SpotPositionRepository;
import com.fxplatform.trading.repository.TradeRepository;
import com.fxplatform.wallet.entity.AssetLedgerEntryEntity;
import com.fxplatform.wallet.entity.WalletBalanceEntity;
import com.fxplatform.wallet.enums.WalletType;
import com.fxplatform.wallet.repository.AssetLedgerEntryRepository;
import com.fxplatform.wallet.service.WalletService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL/MyBatis implementation of the validation-only account seed transaction. */
@Component
@Profile("validation")
public class JdbcValidationAccountSeedGateway implements ValidationAccountSeedGateway {

  private static final String INVALID = "VALIDATION_SEED_INVALID";
  private static final String CONFLICT = "VALIDATION_SEED_CONFLICT";
  private static final String RESET_NOT_READY = "VALIDATION_RESET_NOT_READY";
  private static final String MODEL = "validation-account-seed-v1";
  private static final String USDT = "USDT";
  private static final int MONEY_SCALE = 8;
  private static final int MONEY_PRECISION = 24;
  private static final Pattern ASSET = Pattern.compile("[A-Z0-9]{2,32}");
  private static final Pattern FINGERPRINT = Pattern.compile("[0-9a-f]{64}");

  private static final String LOCK_RESET_FENCE_SQL = """
      SELECT generation, state
      FROM validation_control.reset_state
      WHERE singleton_key = 1
      FOR SHARE
      """;

  private static final String FIND_RECEIPT_SQL = """
      SELECT seed_id,
             generation,
             account_id,
             request_fingerprint,
             canonical_request_json::text AS canonical_request_json,
             perpetual_usdt_balance,
             spot_balances_json::text AS spot_balances_json
      FROM validation_runtime.seed_receipts
      WHERE seed_id = ?
      """;

  private static final String INSERT_RECEIPT_SQL = """
      INSERT INTO validation_runtime.seed_receipts (
        seed_id,
        reset_key,
        generation,
        account_id,
        request_fingerprint,
        canonical_request_json,
        perpetual_usdt_balance,
        spot_balances_json
      ) VALUES (?, 1, ?, ?, ?, CAST(? AS JSONB), ?, CAST(? AS JSONB))
      """;

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;
  private final TradingAccountRepository accountRepository;
  private final WalletService walletService;
  private final LedgerService ledgerService;
  private final LedgerEntryRepository ledgerEntryRepository;
  private final AssetLedgerEntryRepository assetLedgerEntryRepository;
  private final OrderRepository orderRepository;
  private final TradeRepository tradeRepository;
  private final PositionRepository positionRepository;
  private final SpotPositionRepository spotPositionRepository;
  private final SymbolRepository symbolRepository;
  private final DemoExecutionGuard demoExecutionGuard;

  public JdbcValidationAccountSeedGateway(
      JdbcTemplate jdbcTemplate,
      ObjectMapper objectMapper,
      TradingAccountRepository accountRepository,
      WalletService walletService,
      LedgerService ledgerService,
      LedgerEntryRepository ledgerEntryRepository,
      AssetLedgerEntryRepository assetLedgerEntryRepository,
      OrderRepository orderRepository,
      TradeRepository tradeRepository,
      PositionRepository positionRepository,
      SpotPositionRepository spotPositionRepository,
      SymbolRepository symbolRepository,
      DemoExecutionGuard demoExecutionGuard
  ) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
    this.accountRepository = accountRepository;
    this.walletService = walletService;
    this.ledgerService = ledgerService;
    this.ledgerEntryRepository = ledgerEntryRepository;
    this.assetLedgerEntryRepository = assetLedgerEntryRepository;
    this.orderRepository = orderRepository;
    this.tradeRepository = tradeRepository;
    this.positionRepository = positionRepository;
    this.spotPositionRepository = spotPositionRepository;
    this.symbolRepository = symbolRepository;
    this.demoExecutionGuard = demoExecutionGuard;
  }

  @Override
  public Set<String> supportedSpotAssets() {
    return supportedAssets(symbolRepository.findByEnabledTrueOrderBySymbolAsc());
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public <T> T withLockedAccount(
      UUID accountId,
      LockedAccountCallback<T> callback
  ) {
    if (accountId == null || callback == null) {
      throw invalid("Account id and locked callback are required");
    }

    ResetFence fence = lockReadyResetFence();
    TradingAccountEntity account = accountRepository.findByIdForUpdate(accountId)
        .orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND", "Account not found"));
    demoExecutionGuard.requireDemoAccount(account);

    List<WalletBalanceEntity> wallets = walletService.lockAllBalances(accountId);
    Set<String> lockedSupportedAssets = supportedAssets(
        symbolRepository.findTradableCryptoSpotForShareOrderBySymbolAsc());

    return callback.apply(new LockedAccountImpl(
        fence.generation(),
        account,
        wallets,
        lockedSupportedAssets));
  }

  private ResetFence lockReadyResetFence() {
    List<ResetFence> rows = jdbcTemplate.query(
        LOCK_RESET_FENCE_SQL,
        (resultSet, rowNumber) -> new ResetFence(
            resultSet.getLong("generation"),
            resultSet.getString("state")));
    if (rows.size() != 1) {
      throw new BusinessException(
          RESET_NOT_READY,
          "Validation reset generation fence is unavailable");
    }
    ResetFence fence = rows.getFirst();
    if (fence.generation() <= 0 || !"READY".equals(fence.state())) {
      throw new BusinessException(
          RESET_NOT_READY,
          "Validation reset must be READY with a positive generation");
    }
    return fence;
  }

  private final class LockedAccountImpl implements LockedAccount {

    private final long generation;
    private final TradingAccountEntity account;
    private final List<WalletBalanceEntity> lockedWallets;
    private final Set<String> supportedAssets;
    private final Map<String, BigDecimal> writtenSpotTargets = new LinkedHashMap<>();
    private UUID activeSeedId;
    private BigDecimal writtenPerpetualTarget;
    private String previousSpotAsset;

    private LockedAccountImpl(
        long generation,
        TradingAccountEntity account,
        List<WalletBalanceEntity> lockedWallets,
        Set<String> supportedAssets
    ) {
      this.generation = generation;
      this.account = account;
      this.lockedWallets = List.copyOf(lockedWallets);
      this.supportedAssets = supportedAssets;
    }

    @Override
    public Optional<ValidationAccountSeedReceipt> findReceipt(UUID seedId) {
      if (seedId == null) {
        throw invalid("Seed id is required");
      }
      List<ValidationAccountSeedReceipt> rows = jdbcTemplate.query(
          FIND_RECEIPT_SQL,
          (resultSet, rowNumber) -> mapReceipt(resultSet, generation),
          seedId);
      if (rows.size() > 1) {
        throw conflict("Seed receipt identity is not unique");
      }
      return rows.stream().findFirst();
    }

    @Override
    public Set<String> supportedSpotAssets() {
      return supportedAssets;
    }

    @Override
    public PristineSnapshot snapshot() {
      AccountView accountView = new AccountView(
          account.getId(),
          enumName(account.getAccountType()),
          enumName(account.getStatus()),
          account.getBaseCurrency(),
          account.getBalance(),
          account.getEquity(),
          account.getUsedMargin(),
          account.getFreeMargin(),
          account.getMarginLevel(),
          account.getLeverage() == null ? 0 : account.getLeverage(),
          enumName(account.getPositionMode()),
          account.getDemoGeneration() == null ? 0L : account.getDemoGeneration(),
          account.getResetAt());

      List<WalletView> walletViews = lockedWallets.stream()
          .map(wallet -> new WalletView(
              wallet.getWalletType(),
              wallet.getAsset(),
              wallet.getTotal(),
              wallet.getAvailable(),
              wallet.getLocked()))
          .toList();

      List<CashLedgerView> cashLedger = ledgerEntryRepository
          .findByAccountIdOrderByCreatedAtDesc(account.getId()).stream()
          .map(JdbcValidationAccountSeedGateway::cashLedgerView)
          .toList();
      List<AssetLedgerView> assetLedger = assetLedgerEntryRepository
          .findByAccountIdOrderByCreatedAtDesc(account.getId()).stream()
          .map(JdbcValidationAccountSeedGateway::assetLedgerView)
          .toList();

      return new PristineSnapshot(
          accountView,
          walletViews,
          cashLedger,
          assetLedger,
          orderRepository.countByAccountId(account.getId()),
          tradeRepository.countByAccountId(account.getId()),
          positionRepository.countByAccountId(account.getId()),
          spotPositionRepository.countByAccountId(account.getId()));
    }

    @Override
    public void writePerpetualUsdtTarget(
        UUID seedId,
        BigDecimal target,
        BigDecimal delta
    ) {
      bindSeed(seedId);
      if (writtenPerpetualTarget != null) {
        throw conflict("Perpetual validation target was written more than once");
      }
      BigDecimal normalizedTarget = exactMoney(target, false);
      BigDecimal normalizedDelta = exactMoney(delta, true);
      BigDecimal current = exactMoney(account.getBalance(), false);
      if (normalizedTarget.subtract(current).compareTo(normalizedDelta) != 0) {
        throw conflict("Perpetual balance changed after the pristine snapshot");
      }

      account.setBalance(normalizedTarget);
      account.setEquity(normalizedTarget);
      account.setUsedMargin(zeroMoney());
      account.setFreeMargin(normalizedTarget);
      account.setMarginLevel(null);
      accountRepository.save(account);
      ledgerService.recordValidationSeed(account, normalizedDelta, seedId);
      writtenPerpetualTarget = normalizedTarget;
    }

    @Override
    public void writeSpotTarget(
        UUID seedId,
        String canonicalAsset,
        BigDecimal target,
        BigDecimal delta
    ) {
      bindSeed(seedId);
      if (writtenPerpetualTarget == null) {
        throw conflict("Perpetual target must be written before Spot targets");
      }
      String asset = exactCanonicalAsset(canonicalAsset);
      if (!supportedAssets.contains(asset)) {
        throw invalid("Seed contains an unsupported Spot asset");
      }
      if (previousSpotAsset != null && previousSpotAsset.compareTo(asset) >= 0) {
        throw conflict("Spot targets must be written once in canonical order");
      }
      if (writtenSpotTargets.containsKey(asset)) {
        throw conflict("Spot validation target was written more than once");
      }
      BigDecimal normalizedTarget = exactMoney(target, false);
      BigDecimal normalizedDelta = exactMoney(delta, true);
      if (USDT.equals(asset)
          && normalizedTarget.compareTo(writtenPerpetualTarget) != 0) {
        throw conflict("USDT Spot and perpetual targets must match");
      }

      walletService.setValidationSeedTarget(
          account.getId(),
          WalletType.SPOT,
          asset,
          normalizedTarget,
          normalizedDelta,
          seedId);
      writtenSpotTargets.put(asset, normalizedTarget);
      previousSpotAsset = asset;
    }

    @Override
    public void saveReceipt(ValidationAccountSeedReceipt receipt) {
      requireCompleteReceipt(receipt);
      String canonicalRequestJson = canonicalRequestJson(receipt);
      String spotBalancesJson = spotBalancesJson(receipt.spotBalances());
      try {
        int inserted = jdbcTemplate.update(
            INSERT_RECEIPT_SQL,
            receipt.seedId(),
            generation,
            receipt.accountId(),
            receipt.requestFingerprint(),
            canonicalRequestJson,
            receipt.perpetualUsdtBalance(),
            spotBalancesJson);
        if (inserted != 1) {
          throw conflict("Validation seed receipt was not persisted exactly once");
        }
      } catch (DataIntegrityViolationException ex) {
        throw new BusinessException(
            CONFLICT,
            "Validation seed receipt conflicts with durable generation evidence",
            ex);
      }
    }

    private void bindSeed(UUID seedId) {
      if (seedId == null) {
        throw invalid("Seed id is required");
      }
      if (activeSeedId == null) {
        activeSeedId = seedId;
      } else if (!activeSeedId.equals(seedId)) {
        throw conflict("A locked seed transaction cannot mix seed ids");
      }
    }

    private void requireCompleteReceipt(ValidationAccountSeedReceipt receipt) {
      if (receipt == null
          || receipt.seedId() == null
          || receipt.accountId() == null
          || !account.getId().equals(receipt.accountId())
          || activeSeedId == null
          || !activeSeedId.equals(receipt.seedId())
          || writtenPerpetualTarget == null
          || !sameMoney(writtenPerpetualTarget, receipt.perpetualUsdtBalance())
          || receipt.requestFingerprint() == null
          || !FINGERPRINT.matcher(receipt.requestFingerprint()).matches()
          || !sameBalances(writtenSpotTargets, receipt.spotBalances())
          || !receipt.spotBalances().containsKey(USDT)) {
        throw conflict("Validation seed receipt does not match committed target writes");
      }
      String expectedFingerprint = fingerprint(
          receipt.accountId(),
          receipt.perpetualUsdtBalance(),
          receipt.spotBalances());
      if (!expectedFingerprint.equals(receipt.requestFingerprint())) {
        throw conflict("Validation seed receipt fingerprint is inconsistent");
      }
    }
  }

  private ValidationAccountSeedReceipt mapReceipt(ResultSet resultSet, long generation)
      throws SQLException {
    long receiptGeneration = resultSet.getLong("generation");
    if (receiptGeneration != generation) {
      throw conflict("Seed receipt belongs to a stale reset generation");
    }

    UUID seedId = resultSet.getObject("seed_id", UUID.class);
    UUID accountId = resultSet.getObject("account_id", UUID.class);
    String fingerprint = resultSet.getString("request_fingerprint");
    BigDecimal perpetualTarget = exactMoney(
        resultSet.getBigDecimal("perpetual_usdt_balance"),
        false);
    Map<String, BigDecimal> spotBalances = parseSpotBalances(
        resultSet.getString("spot_balances_json"));
    if (seedId == null
        || accountId == null
        || fingerprint == null
        || !FINGERPRINT.matcher(fingerprint).matches()
        || !spotBalances.containsKey(USDT)
        || !sameMoney(perpetualTarget, spotBalances.get(USDT))) {
      throw conflict("Seed receipt durable fields are invalid");
    }

    requireCanonicalRequestEvidence(
        resultSet.getString("canonical_request_json"),
        accountId,
        perpetualTarget,
        spotBalances);
    if (!fingerprint(accountId, perpetualTarget, spotBalances).equals(fingerprint)) {
      throw conflict("Seed receipt fingerprint does not match durable canonical evidence");
    }
    return new ValidationAccountSeedReceipt(
        seedId,
        accountId,
        fingerprint,
        perpetualTarget,
        spotBalances);
  }

  private void requireCanonicalRequestEvidence(
      String json,
      UUID accountId,
      BigDecimal perpetualTarget,
      Map<String, BigDecimal> spotBalances
  ) {
    JsonNode node = parseJsonObject(json, "canonical request");
    if (node.size() != 4
        || !MODEL.equals(textField(node, "model"))
        || !accountId.toString().equals(textField(node, "accountId"))
        || !sameMoney(perpetualTarget, moneyField(node, "perpetualUsdtBalance"))
        || !sameBalances(spotBalances, parseSpotBalancesNode(node.get("spotBalances")))) {
      throw conflict("Seed receipt canonical request evidence is inconsistent");
    }
  }

  private Map<String, BigDecimal> parseSpotBalances(String json) {
    return parseSpotBalancesNode(parseJsonObject(json, "Spot balances"));
  }

  private Map<String, BigDecimal> parseSpotBalancesNode(JsonNode node) {
    if (node == null || !node.isObject()) {
      throw conflict("Seed receipt Spot balances must be a JSON object");
    }
    TreeMap<String, BigDecimal> sorted = new TreeMap<>();
    node.properties().forEach(entry -> {
      String asset = exactCanonicalAsset(entry.getKey());
      if (!entry.getValue().isTextual()) {
        throw conflict("Seed receipt balance values must be canonical decimal strings");
      }
      BigDecimal amount;
      try {
        amount = exactMoney(new BigDecimal(entry.getValue().textValue()), false);
      } catch (NumberFormatException ex) {
        throw new BusinessException(
            CONFLICT,
            "Seed receipt contains an invalid decimal balance",
            ex);
      }
      if (sorted.putIfAbsent(asset, amount) != null) {
        throw conflict("Seed receipt contains duplicate canonical assets");
      }
    });
    return Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
  }

  private JsonNode parseJsonObject(String json, String label) {
    try {
      JsonNode node = objectMapper.readTree(json);
      if (node == null || !node.isObject()) {
        throw conflict("Seed receipt " + label + " must be a JSON object");
      }
      return node;
    } catch (JsonProcessingException ex) {
      throw new BusinessException(
          CONFLICT,
          "Seed receipt " + label + " is invalid JSON",
          ex);
    }
  }

  private static String textField(JsonNode node, String fieldName) {
    JsonNode value = node.get(fieldName);
    return value != null && value.isTextual() ? value.textValue() : null;
  }

  private static BigDecimal moneyField(JsonNode node, String fieldName) {
    String value = textField(node, fieldName);
    if (value == null) {
      throw conflict("Seed receipt canonical money field is missing");
    }
    try {
      return exactMoney(new BigDecimal(value), false);
    } catch (NumberFormatException ex) {
      throw new BusinessException(
          CONFLICT,
          "Seed receipt canonical money field is invalid",
          ex);
    }
  }

  private String canonicalRequestJson(ValidationAccountSeedReceipt receipt) {
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("model", MODEL);
    json.put("accountId", receipt.accountId().toString());
    json.put(
        "perpetualUsdtBalance",
        exactMoney(receipt.perpetualUsdtBalance(), false).toPlainString());
    json.put("spotBalances", decimalStrings(receipt.spotBalances()));
    return writeJson(json);
  }

  private String spotBalancesJson(Map<String, BigDecimal> spotBalances) {
    return writeJson(decimalStrings(spotBalances));
  }

  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("Unable to serialize validation seed evidence", ex);
    }
  }

  private static Map<String, String> decimalStrings(Map<String, BigDecimal> balances) {
    TreeMap<String, String> sorted = new TreeMap<>();
    if (balances == null) {
      throw conflict("Validation seed balances are required");
    }
    balances.forEach((asset, amount) -> sorted.put(
        exactCanonicalAsset(asset),
        exactMoney(amount, false).toPlainString()));
    return Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
  }

  private static Set<String> supportedAssets(List<SymbolEntity> symbols) {
    TreeSet<String> assets = new TreeSet<>();
    assets.add(USDT);
    if (symbols != null) {
      for (SymbolEntity symbol : symbols) {
        if (symbol == null
            || !Boolean.TRUE.equals(symbol.getEnabled())
            || !Boolean.TRUE.equals(symbol.getTradable())
            || symbol.getProductType() != ProductType.CRYPTO_SPOT) {
          continue;
        }
        String asset = canonicalAssetOrNull(symbol.getBaseCurrency());
        if (asset != null) {
          assets.add(asset);
        }
      }
    }
    return Collections.unmodifiableSet(assets);
  }

  private static String canonicalAssetOrNull(String rawAsset) {
    if (rawAsset == null) {
      return null;
    }
    String asset = rawAsset.trim().toUpperCase(Locale.ROOT);
    return ASSET.matcher(asset).matches() ? asset : null;
  }

  private static String exactCanonicalAsset(String rawAsset) {
    String canonical = canonicalAssetOrNull(rawAsset);
    if (canonical == null || !canonical.equals(rawAsset)) {
      throw conflict("Validation seed asset is not canonical");
    }
    return canonical;
  }

  private static BigDecimal exactMoney(BigDecimal value, boolean signed) {
    if (value == null || (!signed && value.signum() < 0) || value.scale() > MONEY_SCALE) {
      throw conflict("Validation seed money value is invalid");
    }
    try {
      BigDecimal normalized = value.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
      if (normalized.precision() > MONEY_PRECISION) {
        throw conflict("Validation seed money value exceeds NUMERIC(24,8)");
      }
      return normalized;
    } catch (ArithmeticException ex) {
      throw new BusinessException(
          CONFLICT,
          "Validation seed money precision is invalid",
          ex);
    }
  }

  private static BigDecimal zeroMoney() {
    return BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
  }

  private static CashLedgerView cashLedgerView(LedgerEntryEntity entry) {
    return new CashLedgerView(
        enumName(entry.getEntryType()),
        entry.getOperationType(),
        entry.getAmount(),
        entry.getBalanceAfter(),
        entry.getCurrency(),
        entry.getReferenceType(),
        entry.getReferenceId());
  }

  private static AssetLedgerView assetLedgerView(AssetLedgerEntryEntity entry) {
    return new AssetLedgerView(
        entry.getWalletType(),
        entry.getAsset(),
        entry.getEntryType(),
        entry.getOperationType(),
        entry.getAmount(),
        entry.getBalanceAfter(),
        entry.getReferenceType(),
        entry.getReferenceId());
  }

  private static String enumName(Enum<?> value) {
    return value == null ? null : value.name();
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
    lines.add("model=" + MODEL);
    lines.add("accountId=" + accountId);
    lines.add("perpetualUsdtBalance=" + exactMoney(perpetualUsdt, false).toPlainString());
    new TreeMap<>(spotBalances).forEach((asset, amount) -> lines.add(
        "spotBalances." + exactCanonicalAsset(asset) + "="
            + exactMoney(amount, false).toPlainString()));
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is unavailable", ex);
    }
  }

  private static BusinessException invalid(String message) {
    return new BusinessException(INVALID, message);
  }

  private static BusinessException conflict(String message) {
    return new BusinessException(CONFLICT, message);
  }

  private record ResetFence(long generation, String state) {
  }
}
