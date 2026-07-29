package com.fxplatform.ledger.service;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.exception.AuthorizationException;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.exception.ErrorCode;
import com.fxplatform.ledger.dto.LedgerEntryResponse;
import com.fxplatform.ledger.entity.LedgerEntryEntity;
import com.fxplatform.ledger.enums.LedgerEntryType;
import com.fxplatform.ledger.repository.LedgerEntryRepository;
import com.fxplatform.wallet.repository.AssetLedgerEntryRepository;
import com.fxplatform.account.dto.AccountTransferRequest.Direction;
import java.time.Instant;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * LedgerService 是资金流水模块的业务服务。
 */
@Service
public class LedgerService {

  public record TransferRecord(Direction direction, BigDecimal amount, Instant createdAt) {
  }

  public record TransferHistoryRecord(
      UUID transferId,
      Direction direction,
      BigDecimal amount,
      BigDecimal spotBalanceAfter,
      BigDecimal perpBalanceAfter,
      Instant createdAt
  ) {
  }

  public List<TransferHistoryRecord> transferRecords(UUID accountId) {
    if (assetLedgerEntryRepository == null) {
      return List.of();
    }
    List<TransferHistoryRecord> records = new ArrayList<>();
    java.util.Set<UUID> seen = new java.util.HashSet<>();
    for (LedgerEntryEntity cashEntry : ledgerEntryRepository.findByAccountIdOrderByCreatedAtDesc(accountId)) {
      UUID transferId = cashEntry.getReferenceId();
      if (!"TRANSFER".equals(cashEntry.getReferenceType())
          || transferId == null
          || !seen.add(transferId)) {
        continue;
      }
      TransferRecord transfer = findTransfer(accountId, transferId).orElseThrow();
      com.fxplatform.wallet.entity.AssetLedgerEntryEntity assetEntry =
          assetLedgerEntryRepository.findByReference(accountId, "TRANSFER", transferId).getFirst();
      records.add(new TransferHistoryRecord(
          transferId,
          transfer.direction(),
          transfer.amount(),
          assetEntry.getBalanceAfter(),
          cashEntry.getBalanceAfter(),
          transfer.createdAt()));
    }
    return records;
  }

  public java.util.Optional<TransferRecord> findTransfer(UUID accountId, UUID requestId) {
    List<LedgerEntryEntity> cash = ledgerEntryRepository.findByReference(
        accountId, "TRANSFER", requestId);
    List<com.fxplatform.wallet.entity.AssetLedgerEntryEntity> asset =
        assetLedgerEntryRepository == null
            ? List.of()
            : assetLedgerEntryRepository.findByReference(accountId, "TRANSFER", requestId);
    if (cash.isEmpty() && asset.isEmpty()) {
      return java.util.Optional.empty();
    }
    if (cash.size() != 1 || asset.size() != 1) {
      throw transferConflict();
    }
    LedgerEntryEntity cashEntry = cash.getFirst();
    com.fxplatform.wallet.entity.AssetLedgerEntryEntity assetEntry = asset.getFirst();
    Direction direction;
    if (LedgerEntryType.TRANSFER_IN.name().equals(cashEntry.getOperationType())
        && LedgerEntryType.TRANSFER_OUT.name().equals(assetEntry.getOperationType())) {
      direction = Direction.SPOT_TO_PERP;
    } else if (LedgerEntryType.TRANSFER_OUT.name().equals(cashEntry.getOperationType())
        && LedgerEntryType.TRANSFER_IN.name().equals(assetEntry.getOperationType())) {
      direction = Direction.PERP_TO_SPOT;
    } else {
      throw transferConflict();
    }
    BigDecimal cashAmount = cashEntry.getAmount().abs();
    BigDecimal assetAmount = assetEntry.getAmount().abs();
    if (cashAmount.compareTo(assetAmount) != 0) {
      throw transferConflict();
    }
    Instant createdAt = cashEntry.getCreatedAt() == null
        ? assetEntry.getCreatedAt()
        : cashEntry.getCreatedAt();
    return java.util.Optional.of(new TransferRecord(direction, cashAmount, createdAt));
  }

  public LedgerEntryEntity recordTransfer(
      TradingAccountEntity account,
      LedgerEntryType type,
      BigDecimal amount,
      UUID requestId,
      String description
  ) {
    if (type != LedgerEntryType.TRANSFER_IN && type != LedgerEntryType.TRANSFER_OUT) {
      throw new IllegalArgumentException("Transfer ledger type required");
    }
    return saveIdempotent(
        account,
        type,
        amount,
        account.getBalance(),
        "TRANSFER",
        requestId,
        description);
  }

  public LedgerEntryEntity recordDemoInit(TradingAccountEntity account, BigDecimal amount) {
    return saveIdempotent(
        account,
        LedgerEntryType.DEMO_INIT,
        amount,
        account.getBalance(),
        "DEMO_ACCOUNT",
        account.getId(),
        "Initialize Demo perpetual balance");
  }

  public java.util.Optional<LedgerEntryEntity> findDemoReset(UUID accountId, UUID requestId) {
    return java.util.Optional.ofNullable(ledgerEntryRepository.findByBusinessOperation(
        accountId,
        "DEMO_RESET",
        requestId,
        LedgerEntryType.DEMO_RESET.name()));
  }

  public LedgerEntryEntity recordDemoReset(
      TradingAccountEntity account,
      BigDecimal amount,
      UUID requestId
  ) {
    return saveIdempotent(
        account,
        LedgerEntryType.DEMO_RESET,
        amount,
        account.getBalance(),
        "DEMO_RESET",
        requestId,
        "Reset Demo perpetual balance");
  }

  /** Records the exact delta applied by a validation-only perpetual balance seed. */
  @Transactional
  public LedgerEntryEntity recordValidationSeed(
      TradingAccountEntity account,
      BigDecimal delta,
      UUID seedId
  ) {
    if (account == null
        || account.getId() == null
        || seedId == null
        || !"USDT".equals(account.getBaseCurrency())) {
      throw new BusinessException(
          "VALIDATION_SEED_INVALID",
          "Validation seed ledger requires a USDT account and seed id");
    }
    BigDecimal normalizedDelta = exactValidationMoney(delta, true);
    BigDecimal balanceAfter = exactValidationMoney(account.getBalance(), false);
    LedgerEntryEntity entry = saveIdempotent(
        account,
        LedgerEntryType.VALIDATION_SEED,
        normalizedDelta,
        balanceAfter,
        "VALIDATION_SEED",
        seedId,
        "Set validation initial perpetual balance");
    if (entry == null
        || entry.getEntryType() != LedgerEntryType.VALIDATION_SEED
        || !LedgerEntryType.VALIDATION_SEED.name().equals(entry.getOperationType())
        || entry.getAmount() == null
        || entry.getBalanceAfter() == null
        || entry.getAmount().compareTo(normalizedDelta) != 0
        || entry.getBalanceAfter().compareTo(balanceAfter) != 0
        || !"USDT".equals(entry.getCurrency())
        || !"VALIDATION_SEED".equals(entry.getReferenceType())
        || !seedId.equals(entry.getReferenceId())) {
      throw new BusinessException(
          "VALIDATION_SEED_CONFLICT",
          "Existing validation seed cash operation does not match the requested target");
    }
    return entry;
  }

  private static BusinessException transferConflict() {
    return new BusinessException(
        ErrorCode.TRANSFER_REQUEST_CONFLICT,
        "Transfer ledger pair is incomplete or inconsistent");
  }

  /** 资金流水仓储，用于保存和查询账务流水。 */
  private final LedgerEntryRepository ledgerEntryRepository;
  /** 交易账户仓储，用于校验用户对账户的访问权。 */
  private final TradingAccountRepository accountRepository;
  private final AssetLedgerEntryRepository assetLedgerEntryRepository;

  @Autowired
  public LedgerService(
      LedgerEntryRepository ledgerEntryRepository,
      TradingAccountRepository accountRepository,
      AssetLedgerEntryRepository assetLedgerEntryRepository
  ) {
    this.ledgerEntryRepository = ledgerEntryRepository;
    this.accountRepository = accountRepository;
    this.assetLedgerEntryRepository = assetLedgerEntryRepository;
  }

  public LedgerService(
      LedgerEntryRepository ledgerEntryRepository,
      TradingAccountRepository accountRepository
  ) {
    this(ledgerEntryRepository, accountRepository, null);
  }

  public LedgerEntryEntity recordDemoDeposit(
      TradingAccountEntity account,
      BigDecimal amount,
      String description
  ) {
    return save(account, LedgerEntryType.DEMO_DEPOSIT, amount, account.getBalance(), null, null, description);
  }

  public LedgerEntryEntity recordMarginHold(
      TradingAccountEntity account,
      BigDecimal amount,
      UUID positionId,
      String description
  ) {
    return save(account, LedgerEntryType.MARGIN_HOLD, amount, account.getBalance(), "POSITION", positionId, description);
  }

  public LedgerEntryEntity recordOrderHold(
      TradingAccountEntity account,
      BigDecimal amount,
      UUID orderId,
      String description
  ) {
    return save(account, LedgerEntryType.ORDER_HOLD, amount, account.getBalance(), "ORDER", orderId, description);
  }

  public LedgerEntryEntity recordOrderRelease(
      TradingAccountEntity account,
      BigDecimal amount,
      UUID orderId,
      String description
  ) {
    return save(account, LedgerEntryType.ORDER_RELEASE, amount, account.getBalance(), "ORDER", orderId, description);
  }

  public LedgerEntryEntity recordMarginRelease(
      TradingAccountEntity account,
      BigDecimal amount,
      UUID positionId,
      String description
  ) {
    return save(account, LedgerEntryType.MARGIN_RELEASE, amount, account.getBalance(), "POSITION", positionId, description);
  }

  public LedgerEntryEntity recordTradePnl(
      TradingAccountEntity account,
      BigDecimal amount,
      UUID tradeOrPositionId,
      String description
  ) {
    return save(account, LedgerEntryType.TRADE_PNL, amount, account.getBalance(), "POSITION", tradeOrPositionId, description);
  }

  public LedgerEntryEntity recordTradeFee(
      TradingAccountEntity account,
      BigDecimal amount,
      UUID tradeOrPositionId,
      String description
  ) {
    return save(account, LedgerEntryType.TRADE_FEE, amount.negate(), account.getBalance(), "POSITION", tradeOrPositionId, description);
  }

  public LedgerEntryEntity recordTradeFeeForTrade(
      TradingAccountEntity account,
      BigDecimal amount,
      UUID tradeId,
      String description
  ) {
    return saveIdempotent(account, LedgerEntryType.TRADE_FEE, amount.negate(), account.getBalance(), "TRADE", tradeId, description);
  }

  public LedgerEntryEntity recordFundingFee(
      TradingAccountEntity account,
      BigDecimal amount,
      UUID positionId,
      String description
  ) {
    return save(account, LedgerEntryType.FUNDING_FEE, amount, account.getBalance(), "POSITION", positionId, description);
  }

  public LedgerEntryEntity recordFundingFeeSettlement(
      TradingAccountEntity account,
      BigDecimal amount,
      UUID settlementId,
      String description
  ) {
    return saveIdempotent(account, LedgerEntryType.FUNDING_FEE, amount, account.getBalance(), "FUNDING_SETTLEMENT", settlementId, description);
  }

  public LedgerEntryEntity recordFundingBankruptcyShortfall(
      TradingAccountEntity account,
      BigDecimal amount,
      UUID settlementId,
      String description
  ) {
    return saveIdempotent(
        account,
        LedgerEntryType.BANKRUPTCY_SHORTFALL,
        amount,
        account.getBalance(),
        "FUNDING_SETTLEMENT",
        settlementId,
        description);
  }

  public LedgerEntryEntity recordLiquidationFee(
      TradingAccountEntity account,
      BigDecimal amount,
      UUID positionId,
      String description
  ) {
    return save(account, LedgerEntryType.LIQUIDATION_FEE, amount.negate(), account.getBalance(), "POSITION", positionId, description);
  }

  public LedgerEntryEntity recordBankruptcyShortfall(
      TradingAccountEntity account,
      BigDecimal amount,
      UUID liquidationOrderId,
      String description
  ) {
    return saveIdempotent(
        account,
        LedgerEntryType.BANKRUPTCY_SHORTFALL,
        amount,
        account.getBalance(),
        "LIQUIDATION_ORDER",
        liquidationOrderId,
        description);
  }

  public LedgerEntryEntity recordCrossLiquidationShortfall(
      TradingAccountEntity account,
      BigDecimal amount,
      UUID settlementId,
      String description
  ) {
    return saveIdempotent(
        account,
        LedgerEntryType.BANKRUPTCY_SHORTFALL,
        amount,
        account.getBalance(),
        "CROSS_LIQUIDATION_SETTLEMENT",
        settlementId,
        description);
  }

  public LedgerEntryEntity recordFinancing(
      TradingAccountEntity account,
      BigDecimal amount,
      UUID positionId,
      String description
  ) {
    return save(account, LedgerEntryType.FINANCING, amount, account.getBalance(), "POSITION", positionId, description);
  }

  public LedgerEntryEntity recordFinancingSettlement(
      TradingAccountEntity account,
      BigDecimal amount,
      UUID settlementId,
      String description
  ) {
    return saveIdempotent(account, LedgerEntryType.FINANCING, amount, account.getBalance(), "FX_FINANCING_SETTLEMENT", settlementId, description);
  }

  public LedgerEntryEntity recordForcedClose(
      TradingAccountEntity account,
      UUID positionId,
      String description
  ) {
    return save(account, LedgerEntryType.FORCED_CLOSE, BigDecimal.ZERO, account.getBalance(), "POSITION", positionId, description);
  }

  /**
   * 记录后台人工资金调整流水。
   */
  public LedgerEntryEntity recordAdminAdjustment(
      TradingAccountEntity account,
      BigDecimal amount,
      UUID operationId,
      String description
  ) {
    return saveIdempotent(
        account,
        LedgerEntryType.ADMIN_ADJUSTMENT,
        amount,
        account.getBalance(),
        "ADMIN_FUND_OPERATION",
        operationId,
        description);
  }

  private LedgerEntryEntity saveIdempotent(
      TradingAccountEntity account,
      LedgerEntryType type,
      BigDecimal amount,
      BigDecimal balanceAfter,
      String referenceType,
      UUID referenceId,
      String description
  ) {
    String operationType = type.name();
    LedgerEntryEntity existing = ledgerEntryRepository.findByBusinessOperation(
        account.getId(),
        referenceType,
        referenceId,
        operationType);
    if (existing != null) {
      return existing;
    }
    return save(
        account,
        type,
        amount,
        balanceAfter,
        referenceType,
        referenceId,
        description);
  }

  public List<LedgerEntryEntity> entries(UUID userId, UUID accountId) {
    accountRepository.findByIdAndUserId(accountId, userId)
        .orElseThrow(() -> new AuthorizationException("ACCOUNT_NOT_FOUND", "Account not found"));
    return ledgerEntryRepository.findByAccountIdOrderByCreatedAtDesc(accountId);
  }

  public List<LedgerEntryResponse> visibleEntries(UUID userId, UUID accountId) {
    accountRepository.findByIdAndUserId(accountId, userId)
        .orElseThrow(() -> new AuthorizationException("ACCOUNT_NOT_FOUND", "Account not found"));
    List<SequencedLedgerEntry> combined = new ArrayList<>();
    ledgerEntryRepository.findByAccountIdOrderByCreatedAtDesc(accountId).stream()
        .map(entry -> new SequencedLedgerEntry(
            entry.getSequenceNo(), LedgerEntryResponse.fromCashLedger(entry)))
        .forEach(combined::add);
    if (assetLedgerEntryRepository != null) {
      assetLedgerEntryRepository.findByAccountIdOrderByCreatedAtDesc(accountId).stream()
          .map(entry -> new SequencedLedgerEntry(
              entry.getSequenceNo(), LedgerEntryResponse.fromAssetLedger(entry)))
          .forEach(combined::add);
    }
    combined.sort(Comparator
        .comparing(SequencedLedgerEntry::sequenceNo, Comparator.nullsFirst(Comparator.naturalOrder()))
        .reversed());
    return combined.stream().map(SequencedLedgerEntry::response).toList();
  }

  private record SequencedLedgerEntry(Long sequenceNo, LedgerEntryResponse response) {
  }

  private static BigDecimal exactValidationMoney(BigDecimal value, boolean signed) {
    if (value == null || (!signed && value.signum() < 0) || value.scale() > 8) {
      throw new BusinessException(
          "VALIDATION_SEED_INVALID",
          "Validation seed amount is invalid");
    }
    try {
      BigDecimal normalized = value.setScale(8, RoundingMode.UNNECESSARY);
      if (normalized.precision() > 24) {
        throw new BusinessException(
            "VALIDATION_SEED_INVALID",
            "Validation seed amount exceeds NUMERIC(24,8)");
      }
      return normalized;
    } catch (ArithmeticException ex) {
      throw new BusinessException(
          "VALIDATION_SEED_INVALID",
          "Validation seed amount precision is invalid",
          ex);
    }
  }

  private LedgerEntryEntity save(
      TradingAccountEntity account,
      LedgerEntryType type,
      BigDecimal amount,
      BigDecimal balanceAfter,
      String referenceType,
      UUID referenceId,
      String description
  ) {
    String operationType = type.name();
    LedgerEntryEntity entry = new LedgerEntryEntity();
    entry.setAccountId(account.getId());
    entry.setEntryType(type);
    entry.setOperationType(operationType);
    entry.setAmount(amount);
    entry.setBalanceAfter(balanceAfter);
    entry.setCurrency(account.getBaseCurrency());
    entry.setReferenceType(referenceType);
    entry.setReferenceId(referenceId);
    entry.setDescription(description);
    return ledgerEntryRepository.save(entry);
  }
}
