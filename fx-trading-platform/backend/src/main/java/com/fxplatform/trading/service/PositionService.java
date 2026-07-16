package com.fxplatform.trading.service;
import cn.hutool.core.date.DateUtil;

import static com.fxplatform.common.money.MoneyAmount.accountEquity;
import static com.fxplatform.common.money.MoneyAmount.orZero;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.exception.AuthorizationException;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.exception.ErrorCode;
import com.fxplatform.common.market.SymbolNormalizer;
import com.fxplatform.execution.DemoExecutionGuard;
import com.fxplatform.ledger.service.LedgerService;
import com.fxplatform.market.dto.QuoteResponse;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.model.MarketBundleProducts;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.market.repository.SymbolRepository;
import com.fxplatform.market.service.QuoteService;
import com.fxplatform.market.service.SymbolProductTypes;
import com.fxplatform.risk.model.InstrumentKind;
import com.fxplatform.risk.model.InstrumentProfile;
import com.fxplatform.risk.service.PerpMarginCalculator;
import com.fxplatform.risk.service.PerpetualRiskService;
import com.fxplatform.risk.service.PerpetualRiskService.CrossLiquidationLeg;
import com.fxplatform.risk.service.PerpetualRiskService.PositionRisk;
import com.fxplatform.risk.service.PnLCalculator;
import com.fxplatform.risk.service.TradingAlgorithmEngine;
import com.fxplatform.risk.service.TradingInstrumentClassifier;
import com.fxplatform.trading.dto.request.ClosePositionRequest;
import com.fxplatform.trading.dto.request.UpdatePositionProtectionRequest;
import com.fxplatform.trading.dto.response.PositionResponse;
import com.fxplatform.trading.entity.PositionEntity;
import com.fxplatform.trading.entity.SpotPositionEntity;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.OrderOrigin;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.PositionStatus;
import com.fxplatform.trading.repository.PositionRepository;
import com.fxplatform.trading.repository.SpotPositionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
  private final SpotPositionRepository spotPositionRepository;
  private final DemoExecutionGuard demoExecutionGuard;
  private final TradingInstrumentClassifier instrumentClassifier = new TradingInstrumentClassifier();
  private final TradingAlgorithmEngine tradingAlgorithmEngine = new TradingAlgorithmEngine();
  private final PerpMarginCalculator perpMarginCalculator = new PerpMarginCalculator();
  private final PerpetualRiskService perpetualRiskService = new PerpetualRiskService(perpMarginCalculator);
  private SystemCloseOrderService systemCloseOrderService;

  @Autowired
  public PositionService(
      PositionRepository positionRepository,
      TradingAccountRepository accountRepository,
      QuoteService quoteService,
      PnLCalculator pnlCalculator,
      LedgerService ledgerService,
      SymbolRepository symbolRepository,
      SpotPositionRepository spotPositionRepository,
      DemoExecutionGuard demoExecutionGuard
  ) {
    this.positionRepository = positionRepository;
    this.accountRepository = accountRepository;
    this.quoteService = quoteService;
    this.pnlCalculator = pnlCalculator;
    this.ledgerService = ledgerService;
    this.symbolRepository = symbolRepository;
    this.spotPositionRepository = spotPositionRepository;
    this.demoExecutionGuard = demoExecutionGuard;
  }

  public PositionService(
      PositionRepository positionRepository,
      TradingAccountRepository accountRepository,
      QuoteService quoteService,
      PnLCalculator pnlCalculator,
      LedgerService ledgerService,
      SymbolRepository symbolRepository,
      DemoExecutionGuard demoExecutionGuard
  ) {
    this(positionRepository, accountRepository, quoteService, pnlCalculator, ledgerService, symbolRepository, null,
        demoExecutionGuard);
  }

  public PositionService(
      PositionRepository positionRepository,
      TradingAccountRepository accountRepository,
      QuoteService quoteService,
      PnLCalculator pnlCalculator,
      LedgerService ledgerService,
      DemoExecutionGuard demoExecutionGuard
  ) {
    this(positionRepository, accountRepository, quoteService, pnlCalculator, ledgerService, null, null,
        demoExecutionGuard);
  }

  /**
   * 持仓列表读取前先校验账户归属，避免跨账户枚举 OPEN 持仓。
   */
  public List<PositionResponse> openPositions(UUID userId, UUID accountId) {
    TradingAccountEntity account = requireOwnedAccount(userId, accountId);
    List<PositionEntity> openPositions = positionRepository
        .findByAccountIdAndStatusOrderByOpenedAtDesc(accountId, PositionStatus.OPEN);
    Map<String, QuoteResponse> quoteCache = new HashMap<>();
    List<RealtimePositionContext> contexts = realtimePositionContexts(
        openPositions,
        account,
        quoteCache);
    Map<String, BigDecimal> crossLiquidationPrices = crossLiquidationPrices(account, contexts);
    List<PositionResponse> responses = new ArrayList<>(contexts.stream()
        .map(context -> toRealtimeResponse(context, account, crossLiquidationPrices))
        .toList());
    if (spotPositionRepository != null) {
      spotPositionRepository.findOpenByAccountId(accountId).stream()
          .map(this::toRealtimeSpotResponse)
          .forEach(responses::add);
    }
    return responses;
  }

  public List<PositionResponse> positionHistory(UUID userId, UUID accountId) {
    TradingAccountEntity account = requireOwnedAccount(userId, accountId);
    List<PositionResponse> responses = new ArrayList<>(positionRepository.findByAccountIdAndStatusOrderByOpenedAtDesc(accountId, PositionStatus.CLOSED)
        .stream()
        .map(position -> toResponse(position, account))
        .toList());
    if (spotPositionRepository != null) {
      spotPositionRepository.findClosedWithRealizedPnlByAccountId(accountId).stream()
          .map(this::toClosedSpotResponse)
          .forEach(responses::add);
    }
    return responses;
  }

  /**
   * 用户主动平仓入口。真正的重复结算保护在 closeOwnedPosition 和 closeIfOpen 中完成，
   * 这里先验证账户归属，避免跨账户平仓。
   */
  @Transactional
  public PositionResponse closePosition(UUID userId, UUID accountId, UUID positionId) {
    return closePosition(userId, accountId, positionId, null);
  }

  @Transactional
  public PositionResponse closePosition(
      UUID userId,
      UUID accountId,
      UUID positionId,
      ClosePositionRequest request
  ) {
    requireOwnedAccount(userId, accountId);
    PositionEntity positionSnapshot = requireOwnedPosition(accountId, positionId);
    SymbolEntity symbolSnapshot = symbolFor(positionSnapshot);
    ProductType productType = symbolSnapshot.getProductType();
    if (isP0LinearPerpetual(positionSnapshot, symbolSnapshot)) {
      requireSystemCloseService();
      SystemCloseOrderService.CloseResult result = request == null
          ? systemCloseOrderService.closeUserWhole(
              userId,
              accountId,
              positionId,
              "position-close-" + positionId)
          : systemCloseOrderService.closeUser(userId, accountId, positionId, request);
      return toResponse(result.position(), result.account());
    }
    if (request != null) {
      throw new BusinessException(
          "PRODUCT_NOT_ALLOWED",
          "Explicit partial close is available only for Linear Perpetual positions");
    }
    requireOpen(positionSnapshot, "Only open positions can be closed");
    QuoteResponse quote = quoteService.freshQuote(positionSnapshot.getSymbol());

    TradingAccountEntity account = requireOwnedAccountForUpdate(userId, accountId);
    demoExecutionGuard.requireDemo(account, productType, positionSnapshot.getSymbol());
    PositionEntity position = requireOwnedPositionForUpdate(accountId, positionId);
    return closeOwnedPosition(account, position, quote, null);
  }

  @Transactional
  public PositionResponse updateProtection(
      UUID userId,
      UUID accountId,
      UUID positionId,
      UpdatePositionProtectionRequest request
  ) {
    requireOwnedAccount(userId, accountId);
    PositionEntity positionSnapshot = requireOwnedPosition(accountId, positionId);
    requireOpen(positionSnapshot, "Only open positions can be modified");
    ProductType productType = symbolFor(positionSnapshot).getProductType();
    if (positionSnapshot.getProductType() == ProductType.LINEAR_PERP
        || productType == ProductType.LINEAR_PERP) {
      throw new BusinessException(
          ErrorCode.PRODUCT_NOT_ALLOWED,
          "Linear Perpetual protection must use canonical protection orders");
    }
    validateProtection(positionSnapshot, request);

    TradingAccountEntity account = requireOwnedAccountForUpdate(userId, accountId);
    demoExecutionGuard.requireDemo(account, productType, positionSnapshot.getSymbol());
    PositionEntity position = requireOwnedPositionForUpdate(accountId, positionId);
    requireOpen(position, "Only open positions can be modified");
    validateProtectionDirection(position.getSide(), request.stopLoss(), request.takeProfit());
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
    PositionEntity positionSnapshot = requireOwnedPosition(accountId, positionId);
    SymbolEntity symbolSnapshot = symbolFor(positionSnapshot);
    ProductType productType = symbolSnapshot.getProductType();
    if (isP0LinearPerpetual(positionSnapshot, symbolSnapshot)) {
      requireSystemCloseService();
      boolean liquidation = forcedCloseReason != null && !forcedCloseReason.isBlank();
      OrderOrigin origin = liquidation
          ? OrderOrigin.LIQUIDATION
          : OrderOrigin.ADMIN_FORCE_CLOSE;
      String reason = liquidation ? forcedCloseReason.trim() : "ADMIN_FORCE_CLOSE";
      SystemCloseOrderService.CloseResult result = systemCloseOrderService.closeWhole(
          accountId,
          positionId,
          origin,
          reason,
          "system-close-" + origin.name().toLowerCase() + "-" + positionId);
      return toResponse(result.position(), result.account());
    }
    requireOpen(positionSnapshot, "Only open positions can be closed");
    QuoteResponse quote = quoteService.freshQuote(positionSnapshot.getSymbol());

    TradingAccountEntity account = accountRepository.findByIdForUpdate(accountId)
        .orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND", "Account not found"));
    demoExecutionGuard.requireDemo(account, productType, positionSnapshot.getSymbol());
    PositionEntity position = requireOwnedPositionForUpdate(accountId, positionId);
    return closeOwnedPosition(account, position, quote, forcedCloseReason);
  }

  private void requireSystemCloseService() {
    if (systemCloseOrderService == null) {
      throw new BusinessException(
          "EXECUTION_UNAVAILABLE",
          "Canonical Perpetual close service is unavailable");
    }
  }

  @Autowired
  void setSystemCloseOrderService(SystemCloseOrderService systemCloseOrderService) {
    this.systemCloseOrderService = systemCloseOrderService;
  }

  private PositionResponse closeOwnedPosition(
      TradingAccountEntity account,
      PositionEntity position,
      QuoteResponse quote,
      String forcedCloseReason
  ) {
    requireOpen(position, "Only open positions can be closed");
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

  private TradingAccountEntity requireOwnedAccountForUpdate(UUID userId, UUID accountId) {
    return accountRepository.findByIdAndUserIdForUpdate(accountId, userId)
        .orElseThrow(() -> new AuthorizationException("ACCOUNT_NOT_FOUND", "Account not found"));
  }

  private PositionEntity requireOwnedPosition(UUID accountId, UUID positionId) {
    PositionEntity position = positionRepository.findById(positionId)
        .orElseThrow(() -> new BusinessException("POSITION_NOT_FOUND", "Position not found"));
    requirePositionAccount(position, accountId);
    return position;
  }

  private PositionEntity requireOwnedPositionForUpdate(UUID accountId, UUID positionId) {
    PositionEntity position = positionRepository.findByIdForUpdate(positionId)
        .orElseThrow(() -> new BusinessException("POSITION_NOT_FOUND", "Position not found"));
    requirePositionAccount(position, accountId);
    return position;
  }

  private void requirePositionAccount(PositionEntity position, UUID accountId) {
    if (!position.getAccountId().equals(accountId)) {
      throw new AuthorizationException("POSITION_ACCOUNT_MISMATCH", "Position does not belong to account");
    }
  }

  private void requireOpen(PositionEntity position, String message) {
    if (position.getStatus() != PositionStatus.OPEN) {
      throw new BusinessException("POSITION_NOT_OPEN", message);
    }
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
  List<PositionResponse> toStoredResponses(
      List<PositionEntity> positions,
      TradingAccountEntity account
  ) {
    if (positions.isEmpty()) {
      return List.of();
    }
    List<String> symbols = positions.stream()
        .map(PositionEntity::getSymbol)
        .map(this::normalize)
        .distinct()
        .toList();
    Map<String, SymbolEntity> metadata = new HashMap<>();
    symbolRepository.findBySymbols(symbols).forEach(symbol ->
        metadata.put(normalize(symbol.getSymbol()), requireProductType(symbol)));
    return positions.stream()
        .map(position -> {
          SymbolEntity symbol = metadata.get(normalize(position.getSymbol()));
          if (symbol == null) {
            throw new BusinessException("SYMBOL_METADATA_NOT_FOUND", "Symbol metadata not found");
          }
          return toResponse(position, account, instrumentClassifier.profile(symbol));
        })
        .toList();
  }

  List<PositionResponse> toClosedSpotResponses(List<SpotPositionEntity> positions) {
    return positions.stream().map(this::toClosedSpotResponse).toList();
  }

  private PositionResponse toResponse(PositionEntity position, TradingAccountEntity account) {
    return toResponse(position, account, instrumentProfile(position));
  }

  private PositionResponse toResponse(
      PositionEntity position,
      TradingAccountEntity account,
      InstrumentProfile profile
  ) {
    BigDecimal currentPrice = position.getCurrentPrice();
    BigDecimal floatingPnl = position.getFloatingPnl();
    BigDecimal markPrice = position.getMarkPrice() != null ? position.getMarkPrice() : currentPrice;
    return new PositionResponse(
        position.getId(),
        position.getSymbol(),
        position.getSide().name(),
        profile.instrumentType(),
        marginMode(position, profile),
        displayLeverage(position, account, profile),
        profile.positionUnit(),
        position.getLots(),
        position.getOpenPrice(),
        markPrice,
        currentPrice,
        position.getNotional(),
        liquidationPrice(position, profile),
        position.getOpenPrice(),
        position.getStopLoss(),
        position.getTakeProfit(),
        floatingPnl,
        floatingPnlRatio(floatingPnl, position.getMarginHeld()),
        position.getRealizedPnl(),
        position.getFundingPnl(),
        position.getMarginHeld(),
        maintenanceMargin(position, profile, markPrice, positionLeverage(position, account)),
        maintenanceMarginRate(profile),
        null,
        position.getStatus().name(),
        position.getOpenedAt(),
        position.getClosedAt(),
        position.getProductType(),
        position.getPositionMode(),
        position.getPositionSide(),
        position.getVersion());
  }

  private List<RealtimePositionContext> realtimePositionContexts(
      List<PositionEntity> positions,
      TradingAccountEntity account,
      Map<String, QuoteResponse> quoteCache
  ) {
    List<RealtimePositionContext> contexts = new ArrayList<>();
    for (PositionEntity position : positions) {
      String symbol = SymbolNormalizer.normalize(position.getSymbol());
      QuoteResponse quote = quoteCache.computeIfAbsent(
          symbol,
          ignored -> quoteService.freshQuote(position.getSymbol()));
      InstrumentProfile profile = instrumentProfile(position);
      PositionRisk linearRisk = null;
      if (profile.kind() == InstrumentKind.LINEAR_PERPETUAL) {
        BigDecimal markPrice = requireAuthorityMark(quote);
        linearRisk = perpetualRiskService.positionRisk(
            position.getSide(),
            position.getLots(),
            position.getOpenPrice(),
            markPrice,
            positionLeverage(position, account),
            position.getMarginHeld(),
            position.getFundingPnl(),
            profile.maintenanceMarginRate());
      }
      contexts.add(new RealtimePositionContext(position, quote, profile, linearRisk));
    }
    return List.copyOf(contexts);
  }

  private Map<String, BigDecimal> crossLiquidationPrices(
      TradingAccountEntity account,
      List<RealtimePositionContext> contexts
  ) {
    BigDecimal isolatedPrincipal = BigDecimal.ZERO;
    BigDecimal totalCrossUpl = BigDecimal.ZERO;
    BigDecimal totalCrossThreshold = BigDecimal.ZERO;
    Map<String, List<CrossLiquidationLeg>> legsBySymbol = new HashMap<>();
    Map<String, BigDecimal> uplBySymbol = new HashMap<>();
    Map<String, BigDecimal> thresholdBySymbol = new HashMap<>();

    for (RealtimePositionContext context : contexts) {
      if (context.profile().kind() != InstrumentKind.LINEAR_PERPETUAL) {
        continue;
      }
      PositionEntity position = context.position();
      PositionRisk risk = context.linearRisk();
      if (position.getMarginMode() == MarginMode.ISOLATED) {
        isolatedPrincipal = isolatedPrincipal.add(orZero(position.getMarginHeld()));
        continue;
      }

      String symbol = SymbolNormalizer.normalize(position.getSymbol());
      BigDecimal threshold = risk.maintenanceMargin().add(risk.estimatedCloseTakerFee());
      totalCrossUpl = totalCrossUpl.add(risk.unrealizedPnl());
      totalCrossThreshold = totalCrossThreshold.add(threshold);
      uplBySymbol.merge(symbol, risk.unrealizedPnl(), BigDecimal::add);
      thresholdBySymbol.merge(symbol, threshold, BigDecimal::add);
      legsBySymbol.computeIfAbsent(symbol, ignored -> new ArrayList<>()).add(
          new CrossLiquidationLeg(
              position.getSide(),
              position.getLots(),
              position.getOpenPrice(),
              context.profile().maintenanceMarginRate()));
    }

    Map<String, BigDecimal> prices = new HashMap<>();
    BigDecimal accountCrossBase = orZero(account.getBalance()).subtract(isolatedPrincipal);
    for (Map.Entry<String, List<CrossLiquidationLeg>> entry : legsBySymbol.entrySet()) {
      String symbol = entry.getKey();
      BigDecimal fixedCrossEquity = accountCrossBase
          .add(totalCrossUpl)
          .subtract(uplBySymbol.get(symbol));
      BigDecimal fixedThreshold = totalCrossThreshold.subtract(thresholdBySymbol.get(symbol));
      prices.put(
          symbol,
          perpetualRiskService.estimatedCrossLiquidationPrice(
              fixedCrossEquity,
              fixedThreshold,
              entry.getValue()));
    }
    return prices;
  }

  /**
   * OPEN 持仓响应按当前报价派生展示浮盈亏，但读取路径不回写数据库。
   */
  private PositionResponse toRealtimeResponse(
      RealtimePositionContext context,
      TradingAccountEntity account,
      Map<String, BigDecimal> crossLiquidationPrices
  ) {
    PositionEntity position = context.position();
    QuoteResponse quote = context.quote();
    BigDecimal currentPrice = position.getSide() == OrderSide.BUY ? quote.bid() : quote.ask();
    InstrumentProfile profile = context.profile();
    if (profile.kind() == InstrumentKind.LINEAR_PERPETUAL) {
      return toRealtimeLinearPerpetualResponse(
          position,
          account,
          quote,
          currentPrice,
          profile,
          context.linearRisk(),
          crossLiquidationPrices.get(SymbolNormalizer.normalize(position.getSymbol())));
    }
    BigDecimal markPrice = markPrice(quote, currentPrice);
    BigDecimal pnlPrice = isPerpetual(profile.kind()) ? markPrice : currentPrice;
    BigDecimal floatingPnl = displayPnl(position, account, profile, pnlPrice);

    // 持仓列表按最新报价派生浮盈亏，不在读取路径写库，避免高频刷新放大数据库压力。
    return new PositionResponse(
        position.getId(),
        position.getSymbol(),
        position.getSide().name(),
        profile.instrumentType(),
        marginMode(position, profile),
        displayLeverage(position, account, profile),
        profile.positionUnit(),
        position.getLots(),
        position.getOpenPrice(),
        markPrice,
        currentPrice,
        position.getNotional(),
        liquidationPrice(position, profile),
        position.getOpenPrice(),
        position.getStopLoss(),
        position.getTakeProfit(),
        floatingPnl,
        floatingPnlRatio(floatingPnl, position.getMarginHeld()),
        position.getRealizedPnl(),
        position.getFundingPnl(),
        position.getMarginHeld(),
        maintenanceMargin(position, profile, markPrice, positionLeverage(position, account)),
        maintenanceMarginRate(profile),
        null,
        position.getStatus().name(),
        position.getOpenedAt(),
        position.getClosedAt(),
        position.getProductType(),
        position.getPositionMode(),
        position.getPositionSide(),
        position.getVersion());
  }

  private PositionResponse toRealtimeLinearPerpetualResponse(
      PositionEntity position,
      TradingAccountEntity account,
      QuoteResponse quote,
      BigDecimal currentPrice,
      InstrumentProfile profile,
      PositionRisk risk,
      BigDecimal crossLiquidationPrice
  ) {
    BigDecimal markPrice = requireAuthorityMark(quote);
    return new PositionResponse(
        position.getId(),
        position.getSymbol(),
        position.getSide().name(),
        profile.instrumentType(),
        marginMode(position, profile),
        displayLeverage(position, account, profile),
        profile.positionUnit(),
        position.getLots(),
        position.getOpenPrice(),
        markPrice,
        currentPrice,
        risk.markNotional(),
        position.getMarginMode() == MarginMode.ISOLATED
            ? risk.estimatedLiquidationPrice()
            : crossLiquidationPrice,
        position.getOpenPrice(),
        position.getStopLoss(),
        position.getTakeProfit(),
        risk.unrealizedPnl(),
        risk.roiRatio(),
        position.getRealizedPnl(),
        position.getFundingPnl(),
        position.getMarginHeld(),
        risk.maintenanceMargin(),
        profile.maintenanceMarginRate(),
        null,
        position.getStatus().name(),
        position.getOpenedAt(),
        position.getClosedAt(),
        position.getProductType(),
        position.getPositionMode(),
        position.getPositionSide(),
        position.getVersion());
  }

  private PositionResponse toRealtimeSpotResponse(SpotPositionEntity position) {
    String symbol = spotSymbol(position);
    QuoteResponse quote = quoteService.freshQuote(symbol);
    BigDecimal currentPrice = quote.bid();
    BigDecimal markPrice = markPrice(quote, currentPrice);
    BigDecimal quantity = orZero(position.getQuantity());
    BigDecimal averageCost = orZero(position.getAverageCost());
    BigDecimal costBasis = spotCostBasis(position);
    BigDecimal floatingPnl = pnlCalculator.floatingPnl(
        InstrumentKind.SPOT,
        OrderSide.BUY,
        quantity,
        averageCost,
        currentPrice,
        BigDecimal.ONE);
    return spotResponse(
        position,
        symbol,
        quantity,
        averageCost,
        markPrice,
        currentPrice,
        floatingPnl,
        floatingPnlRatio(floatingPnl, costBasis),
        costBasis,
        PositionStatus.OPEN.name(),
        position.getUpdatedAt(),
        null);
  }

  private PositionResponse toClosedSpotResponse(SpotPositionEntity position) {
    BigDecimal averageCost = orZero(position.getAverageCost());
    return spotResponse(
        position,
        spotSymbol(position),
        orZero(position.getQuantity()),
        averageCost,
        averageCost,
        averageCost,
        BigDecimal.ZERO,
        null,
        spotCostBasis(position),
        PositionStatus.CLOSED.name(),
        null,
        position.getUpdatedAt());
  }

  private PositionResponse spotResponse(
      SpotPositionEntity position,
      String symbol,
      BigDecimal quantity,
      BigDecimal averageCost,
      BigDecimal markPrice,
      BigDecimal currentPrice,
      BigDecimal floatingPnl,
      BigDecimal floatingPnlRatio,
      BigDecimal marginHeld,
      String status,
      Instant openedAt,
      Instant closedAt
  ) {
    return new PositionResponse(
        position.getId(),
        symbol,
        OrderSide.BUY.name(),
        "SPOT",
        "CASH",
        null,
        normalize(position.getAsset()),
        quantity,
        averageCost,
        markPrice,
        currentPrice,
        null,
        null,
        averageCost,
        null,
        null,
        floatingPnl,
        floatingPnlRatio,
        orZero(position.getRealizedPnl()),
        BigDecimal.ZERO,
        marginHeld,
        null,
        null,
        null,
        status,
        openedAt,
        closedAt);
  }

  private BigDecimal spotCostBasis(SpotPositionEntity position) {
    return orZero(position.getQuantity())
        .multiply(orZero(position.getAverageCost()))
        .setScale(8, RoundingMode.HALF_UP);
  }

  private String spotSymbol(SpotPositionEntity position) {
    return normalize(position.getAsset()) + normalize(position.getCostAsset());
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
    return pnlCalculator.floatingPnl(
        profile.kind(),
        position.getSide(),
        position.getLots(),
        position.getOpenPrice(),
        currentPrice,
        canonicalUnitSize(profile));
  }

  private BigDecimal liquidationPrice(PositionEntity position, InstrumentProfile profile) {
    return tradingAlgorithmEngine.liquidationPrice(
        profile.kind(),
        position.getSide(),
        position.getLots(),
        position.getOpenPrice(),
        position.getMarginHeld(),
        canonicalUnitSize(profile));
  }

  private BigDecimal markPrice(QuoteResponse quote, BigDecimal closeoutPrice) {
    // No formal mark-price feed exists yet; quote.mid is the temporary mark-price fallback.
    return quote.mid() != null ? quote.mid() : closeoutPrice;
  }

  private BigDecimal requireAuthorityMark(QuoteResponse quote) {
    if (quote == null
        || quote.markPrice() == null
        || quote.markPrice().compareTo(BigDecimal.ZERO) <= 0) {
      throw new BusinessException(
          "MARKET_DATA_UNAVAILABLE",
          "Linear Perpetual position response requires a positive authority mark");
    }
    return quote.markPrice();
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
    if (profile.kind() == InstrumentKind.LINEAR_PERPETUAL) {
      return perpMarginCalculator.calculate(
          InstrumentKind.LINEAR_PERPETUAL,
          position.getLots(),
          BigDecimal.ONE,
          BigDecimal.ONE,
          markPrice,
          leverage,
          profile.maintenanceMarginRate()).maintenanceMargin();
    }
    return perpMarginCalculator.calculate(profile, position.getLots(), markPrice, leverage)
        .maintenanceMargin();
  }

  private BigDecimal maintenanceMarginRate(InstrumentProfile profile) {
    return isPerpetual(profile.kind()) ? profile.maintenanceMarginRate() : null;
  }

  private boolean isPerpetual(InstrumentKind kind) {
    return kind == InstrumentKind.LINEAR_PERPETUAL || kind == InstrumentKind.INVERSE_PERPETUAL;
  }

  private String marginMode(PositionEntity position, InstrumentProfile profile) {
    if (profile.kind() == InstrumentKind.SPOT) {
      return "CASH";
    }
    return profile.kind() == InstrumentKind.LINEAR_PERPETUAL && position.getMarginMode() != null
        ? position.getMarginMode().name()
        : "CROSS";
  }

  private BigDecimal canonicalUnitSize(InstrumentProfile profile) {
    return profile.kind() == InstrumentKind.LINEAR_PERPETUAL
        ? BigDecimal.ONE
        : profile.unitSize();
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

  private boolean isP0LinearPerpetual(
      PositionEntity position,
      SymbolEntity symbol
  ) {
    boolean storedPerpetual = position.getProductType() == ProductType.LINEAR_PERP;
    boolean configuredPerpetual = symbol.getProductType() == ProductType.LINEAR_PERP;
    boolean p0PerpetualSymbol = MarketBundleProducts.isPerpetual(position.getSymbol());
    if (storedPerpetual != configuredPerpetual
        || (p0PerpetualSymbol && (!storedPerpetual || !configuredPerpetual))) {
      throw new BusinessException(
          "INVALID_INSTRUMENT_RULES",
          "Position and symbol Perpetual product metadata do not match");
    }
    return storedPerpetual && p0PerpetualSymbol;
  }

  private record RealtimePositionContext(
      PositionEntity position,
      QuoteResponse quote,
      InstrumentProfile profile,
      PositionRisk linearRisk
  ) {
  }

  private String normalize(String value) {
    return value == null ? "" : value.trim().toUpperCase();
  }

}
