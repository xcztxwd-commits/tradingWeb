package com.fxplatform.trading.service;
import cn.hutool.core.date.DateUtil;

import static com.fxplatform.common.money.MoneyAmount.accountEquity;
import static com.fxplatform.common.money.MoneyAmount.orZero;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.exception.AuthorizationException;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.ledger.service.LedgerService;
import com.fxplatform.market.dto.QuoteResponse;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.repository.SymbolRepository;
import com.fxplatform.market.service.QuoteService;
import com.fxplatform.market.service.SymbolProductTypes;
import com.fxplatform.risk.model.InstrumentKind;
import com.fxplatform.risk.model.InstrumentProfile;
import com.fxplatform.risk.service.PerpMarginCalculator;
import com.fxplatform.risk.service.PnLCalculator;
import com.fxplatform.risk.service.TradingAlgorithmEngine;
import com.fxplatform.risk.service.TradingInstrumentClassifier;
import com.fxplatform.trading.dto.request.UpdatePositionProtectionRequest;
import com.fxplatform.trading.dto.response.PositionResponse;
import com.fxplatform.trading.entity.PositionEntity;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.PositionStatus;
import com.fxplatform.trading.repository.PositionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PositionService 是交易模块的业务服务。
 */
@Service
public class PositionService {

  private final PositionRepository positionRepository;
  private final TradingAccountRepository accountRepository;
  private final QuoteService quoteService;
  private final PnLCalculator pnlCalculator;
  private final LedgerService ledgerService;
  private final SymbolRepository symbolRepository;
  private final TradingInstrumentClassifier instrumentClassifier = new TradingInstrumentClassifier();
  private final TradingAlgorithmEngine tradingAlgorithmEngine = new TradingAlgorithmEngine();
  private final PerpMarginCalculator perpMarginCalculator = new PerpMarginCalculator();

  @Autowired
  public PositionService(
      PositionRepository positionRepository,
      TradingAccountRepository accountRepository,
      QuoteService quoteService,
      PnLCalculator pnlCalculator,
      LedgerService ledgerService,
      SymbolRepository symbolRepository
  ) {
    this.positionRepository = positionRepository;
    this.accountRepository = accountRepository;
    this.quoteService = quoteService;
    this.pnlCalculator = pnlCalculator;
    this.ledgerService = ledgerService;
    this.symbolRepository = symbolRepository;
  }

  public PositionService(
      PositionRepository positionRepository,
      TradingAccountRepository accountRepository,
      QuoteService quoteService,
      PnLCalculator pnlCalculator,
      LedgerService ledgerService
  ) {
    this(positionRepository, accountRepository, quoteService, pnlCalculator, ledgerService, null);
  }

  /**
   * 持仓列表读取前先校验账户归属，避免跨账户枚举 OPEN 持仓。
   */
  public List<PositionResponse> openPositions(UUID userId, UUID accountId) {
    TradingAccountEntity account = requireOwnedAccount(userId, accountId);
    return positionRepository.findByAccountIdAndStatusOrderByOpenedAtDesc(accountId, PositionStatus.OPEN)
        .stream()
        .map(position -> toRealtimeResponse(position, account))
        .toList();
  }

  public List<PositionResponse> positionHistory(UUID userId, UUID accountId) {
    TradingAccountEntity account = requireOwnedAccount(userId, accountId);
    return positionRepository.findByAccountIdAndStatusOrderByOpenedAtDesc(accountId, PositionStatus.CLOSED)
        .stream()
        .map(position -> toResponse(position, account))
        .toList();
  }

  /**
   * 用户主动平仓入口。真正的重复结算保护在 closeOwnedPosition 和 closeIfOpen 中完成，
   * 这里先验证账户归属，避免跨账户平仓。
   */
  @Transactional
  public PositionResponse closePosition(UUID userId, UUID accountId, UUID positionId) {
    TradingAccountEntity account = requireOwnedAccount(userId, accountId);
    return closeOwnedPosition(account, positionId);
  }

  @Transactional
  public PositionResponse updateProtection(
      UUID userId,
      UUID accountId,
      UUID positionId,
      UpdatePositionProtectionRequest request
  ) {
    TradingAccountEntity account = requireOwnedAccount(userId, accountId);
    PositionEntity position = positionRepository.findById(positionId)
        .orElseThrow(() -> new BusinessException("POSITION_NOT_FOUND", "Position not found"));
    if (!position.getAccountId().equals(accountId)) {
      throw new AuthorizationException("POSITION_ACCOUNT_MISMATCH", "Position does not belong to account");
    }
    if (position.getStatus() != PositionStatus.OPEN) {
      throw new BusinessException("POSITION_NOT_OPEN", "Only open positions can be modified");
    }

    validateProtection(position, request);
    position.setStopLoss(request.stopLoss());
    position.setTakeProfit(request.takeProfit());
    positionRepository.save(position);
    return toResponse(position, account);
  }

  @Transactional
  public PositionResponse closeSystemPosition(UUID accountId, UUID positionId) {
    return closeSystemPosition(accountId, positionId, null);
  }

  @Transactional
  public PositionResponse closeSystemPosition(UUID accountId, UUID positionId, String forcedCloseReason) {
    TradingAccountEntity account = accountRepository.findById(accountId)
        .orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND", "Account not found"));
    return closeOwnedPosition(account, positionId, forcedCloseReason);
  }

  private PositionResponse closeOwnedPosition(TradingAccountEntity account, UUID positionId) {
    return closeOwnedPosition(account, positionId, null);
  }

  private PositionResponse closeOwnedPosition(
      TradingAccountEntity account,
      UUID positionId,
      String forcedCloseReason
  ) {
    UUID accountId = account.getId();
    PositionEntity position = positionRepository.findById(positionId)
        .orElseThrow(() -> new BusinessException("POSITION_NOT_FOUND", "Position not found"));
    if (!position.getAccountId().equals(accountId)) {
      throw new AuthorizationException("POSITION_ACCOUNT_MISMATCH", "Position does not belong to account");
    }
    if (position.getStatus() != PositionStatus.OPEN) {
      throw new BusinessException("POSITION_NOT_OPEN", "Only open positions can be closed");
    }

    QuoteResponse quote = quoteService.freshQuote(position.getSymbol());
    BigDecimal closePrice = position.getSide() == OrderSide.BUY ? quote.bid() : quote.ask();
    BigDecimal realizedPnl = displayPnl(position, account, closePrice);
    BigDecimal marginToRelease = orZero(position.getMarginHeld());

    position.setCurrentPrice(closePrice);
    position.setFloatingPnl(BigDecimal.ZERO);
    position.setRealizedPnl(realizedPnl);
    position.setMarginHeld(BigDecimal.ZERO);
    position.setStatus(PositionStatus.CLOSED);
    position.setClosedAt(DateUtil.date().toInstant());
    // 先用 OPEN 条件更新抢占平仓权，再写账户和流水，避免并发请求重复释放保证金。
    if (positionRepository.closeIfOpen(position) != 1) {
      throw new BusinessException("POSITION_NOT_OPEN", "Position is no longer open");
    }

    // 平仓只释放当前仓位占用的保证金，避免多仓账户被错误清零。
    BigDecimal usedMargin = orZero(account.getUsedMargin()).subtract(marginToRelease).max(BigDecimal.ZERO);
    BigDecimal balance = orZero(account.getBalance()).add(realizedPnl);
    account.setBalance(balance);
    account.setEquity(balance);
    account.setUsedMargin(usedMargin);
    account.setFreeMargin(accountEquity(account).subtract(usedMargin));
    accountRepository.save(account);
    ledgerService.recordMarginRelease(account, marginToRelease, position.getId(), "Position margin released");
    ledgerService.recordTradePnl(account, realizedPnl, position.getId(), "Position closed");
    if (forcedCloseReason != null && !forcedCloseReason.isBlank()) {
      ledgerService.recordForcedClose(account, position.getId(), forcedCloseReason);
    }

    return toResponse(position, account);
  }

  private TradingAccountEntity requireOwnedAccount(UUID userId, UUID accountId) {
    return accountRepository.findByIdAndUserId(accountId, userId)
        .orElseThrow(() -> new AuthorizationException("ACCOUNT_NOT_FOUND", "Account not found"));
  }

  private void validateProtection(PositionEntity position, UpdatePositionProtectionRequest request) {
    BigDecimal stopLoss = request.stopLoss();
    BigDecimal takeProfit = request.takeProfit();
    validateProtectionDirection(position.getSide(), stopLoss, takeProfit);
    if (request.allowsImmediateTrigger() || (stopLoss == null && takeProfit == null)) {
      return;
    }

    QuoteResponse quote = quoteService.freshQuote(position.getSymbol());
    BigDecimal closePrice = position.getSide() == OrderSide.BUY ? quote.bid() : quote.ask();
    if (wouldStopLossTrigger(position.getSide(), stopLoss, closePrice)) {
      throw new BusinessException("PROTECTION_IMMEDIATE_TRIGGER", "Stop loss would trigger immediately");
    }
    if (wouldTakeProfitTrigger(position.getSide(), takeProfit, closePrice)) {
      throw new BusinessException("PROTECTION_IMMEDIATE_TRIGGER", "Take profit would trigger immediately");
    }
  }

  private void validateProtectionDirection(OrderSide side, BigDecimal stopLoss, BigDecimal takeProfit) {
    if (stopLoss == null || takeProfit == null) {
      return;
    }
    if (side == OrderSide.BUY && stopLoss.compareTo(takeProfit) >= 0) {
      throw new BusinessException("PROTECTION_DIRECTION_INVALID", "Buy stop loss must be below take profit");
    }
    if (side == OrderSide.SELL && stopLoss.compareTo(takeProfit) <= 0) {
      throw new BusinessException("PROTECTION_DIRECTION_INVALID", "Sell stop loss must be above take profit");
    }
  }

  private boolean wouldStopLossTrigger(OrderSide side, BigDecimal stopLoss, BigDecimal closePrice) {
    if (stopLoss == null) {
      return false;
    }
    return side == OrderSide.BUY
        ? stopLoss.compareTo(closePrice) >= 0
        : stopLoss.compareTo(closePrice) <= 0;
  }

  private boolean wouldTakeProfitTrigger(OrderSide side, BigDecimal takeProfit, BigDecimal closePrice) {
    if (takeProfit == null) {
      return false;
    }
    return side == OrderSide.BUY
        ? takeProfit.compareTo(closePrice) <= 0
        : takeProfit.compareTo(closePrice) >= 0;
  }

  /**
   * 已落库的持仓字段直接映射给历史和写操作响应，不重新拉取报价。
   */
  private PositionResponse toResponse(PositionEntity position, TradingAccountEntity account) {
    BigDecimal currentPrice = position.getCurrentPrice();
    BigDecimal floatingPnl = position.getFloatingPnl();
    InstrumentProfile profile = instrumentProfile(position);
    BigDecimal markPrice = position.getMarkPrice() != null ? position.getMarkPrice() : currentPrice;
    return new PositionResponse(
        position.getId(),
        position.getSymbol(),
        position.getSide().name(),
        profile.instrumentType(),
        marginMode(profile),
        displayLeverage(position, account, profile),
        profile.positionUnit(),
        position.getLots(),
        position.getOpenPrice(),
        markPrice,
        currentPrice,
        liquidationPrice(position, profile),
        position.getOpenPrice(),
        position.getStopLoss(),
        position.getTakeProfit(),
        floatingPnl,
        floatingPnlRatio(floatingPnl, position.getMarginHeld()),
        position.getRealizedPnl(),
        position.getMarginHeld(),
        maintenanceMargin(position, profile, markPrice, positionLeverage(position, account)),
        maintenanceMarginRate(profile),
        null,
        position.getStatus().name(),
        position.getOpenedAt(),
        position.getClosedAt());
  }

  /**
   * OPEN 持仓响应按当前报价派生展示浮盈亏，但读取路径不回写数据库。
   */
  private PositionResponse toRealtimeResponse(PositionEntity position, TradingAccountEntity account) {
    QuoteResponse quote = quoteService.freshQuote(position.getSymbol());
    BigDecimal currentPrice = position.getSide() == OrderSide.BUY ? quote.bid() : quote.ask();
    InstrumentProfile profile = instrumentProfile(position);
    BigDecimal markPrice = markPrice(quote, currentPrice);
    BigDecimal pnlPrice = isPerpetual(profile.kind()) ? markPrice : currentPrice;
    BigDecimal floatingPnl = displayPnl(position, account, profile, pnlPrice);

    // 持仓列表按最新报价派生浮盈亏，不在读取路径写库，避免高频刷新放大数据库压力。
    return new PositionResponse(
        position.getId(),
        position.getSymbol(),
        position.getSide().name(),
        profile.instrumentType(),
        marginMode(profile),
        displayLeverage(position, account, profile),
        profile.positionUnit(),
        position.getLots(),
        position.getOpenPrice(),
        markPrice,
        currentPrice,
        liquidationPrice(position, profile),
        position.getOpenPrice(),
        position.getStopLoss(),
        position.getTakeProfit(),
        floatingPnl,
        floatingPnlRatio(floatingPnl, position.getMarginHeld()),
        position.getRealizedPnl(),
        position.getMarginHeld(),
        maintenanceMargin(position, profile, markPrice, positionLeverage(position, account)),
        maintenanceMarginRate(profile),
        null,
        position.getStatus().name(),
        position.getOpenedAt(),
        position.getClosedAt());
  }

  private BigDecimal floatingPnlRatio(BigDecimal floatingPnl, BigDecimal marginHeld) {
    if (floatingPnl == null || marginHeld == null || marginHeld.compareTo(BigDecimal.ZERO) <= 0) {
      return null;
    }
    return floatingPnl.divide(marginHeld, 8, RoundingMode.HALF_UP);
  }

  private Integer positionLeverage(PositionEntity position, TradingAccountEntity account) {
    if (position.getLeverage() != null && position.getLeverage() > 0) {
      return position.getLeverage();
    }
    return account.getLeverage();
  }

  private BigDecimal displayPnl(PositionEntity position, TradingAccountEntity account, BigDecimal currentPrice) {
    InstrumentProfile profile = instrumentProfile(position);
    return displayPnl(position, account, profile, currentPrice);
  }

  private BigDecimal displayPnl(
      PositionEntity position,
      TradingAccountEntity account,
      InstrumentProfile profile,
      BigDecimal currentPrice
  ) {
    if (profile.kind() == InstrumentKind.FOREX) {
      return pnlCalculator.floatingPnl(
          position.getSymbol(),
          account.getBaseCurrency(),
          position.getSide(),
          position.getLots(),
          position.getOpenPrice(),
          currentPrice);
    }
    return pnlCalculator.floatingPnl(profile.kind(), position.getSide(), position.getLots(), position.getOpenPrice(), currentPrice, profile.unitSize());
  }

  private BigDecimal liquidationPrice(PositionEntity position, InstrumentProfile profile) {
    return tradingAlgorithmEngine.liquidationPrice(
        profile.kind(),
        position.getSide(),
        position.getLots(),
        position.getOpenPrice(),
        position.getMarginHeld(),
        profile.unitSize());
  }

  private BigDecimal markPrice(QuoteResponse quote, BigDecimal closeoutPrice) {
    // No formal mark-price feed exists yet; quote.mid is the temporary mark-price fallback.
    return quote.mid() != null ? quote.mid() : closeoutPrice;
  }

  private BigDecimal maintenanceMargin(
      PositionEntity position,
      InstrumentProfile profile,
      BigDecimal markPrice,
      int leverage
  ) {
    if (!isPerpetual(profile.kind())) {
      return null;
    }
    BigDecimal stored = orZero(position.getMaintenanceMargin());
    if (stored.compareTo(BigDecimal.ZERO) > 0) {
      return stored;
    }
    return perpMarginCalculator.calculate(profile, position.getLots(), markPrice, leverage).maintenanceMargin();
  }

  private BigDecimal maintenanceMarginRate(InstrumentProfile profile) {
    return isPerpetual(profile.kind()) ? profile.maintenanceMarginRate() : null;
  }

  private boolean isPerpetual(InstrumentKind kind) {
    return kind == InstrumentKind.LINEAR_PERPETUAL || kind == InstrumentKind.INVERSE_PERPETUAL;
  }

  private String marginMode(InstrumentProfile profile) {
    return profile.kind() == InstrumentKind.SPOT ? "CASH" : "CROSS";
  }

  private Integer displayLeverage(PositionEntity position, TradingAccountEntity account, InstrumentProfile profile) {
    return profile.kind() == InstrumentKind.SPOT ? null : positionLeverage(position, account);
  }

  private InstrumentProfile instrumentProfile(PositionEntity position) {
    return instrumentClassifier.profile(symbolFor(position));
  }

  private SymbolEntity symbolFor(PositionEntity position) {
    String normalized = normalize(position.getSymbol());
    if (symbolRepository == null || normalized.isBlank()) {
      throw new BusinessException("SYMBOL_METADATA_NOT_FOUND", "Symbol metadata not found");
    }
    return symbolRepository.findBySymbol(normalized)
        .map(this::requireProductType)
        .orElseThrow(() -> new BusinessException("SYMBOL_METADATA_NOT_FOUND", "Symbol metadata not found"));
  }

  private SymbolEntity requireProductType(SymbolEntity symbol) {
    return SymbolProductTypes.requireExplicit(symbol);
  }

  private String normalize(String value) {
    return value == null ? "" : value.trim().toUpperCase();
  }

}
