package com.fxplatform.account.service;

import static com.fxplatform.common.money.MoneyAmount.orZero;

import com.fxplatform.account.dto.AccountSnapshot;
import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.market.dto.QuoteResponse;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.repository.SymbolRepository;
import com.fxplatform.market.service.QuoteService;
import com.fxplatform.market.service.SymbolProductTypes;
import com.fxplatform.risk.model.InstrumentKind;
import com.fxplatform.risk.model.InstrumentProfile;
import com.fxplatform.risk.service.PerpMarginCalculator;
import com.fxplatform.risk.service.PnLCalculator;
import com.fxplatform.risk.service.TradingInstrumentClassifier;
import com.fxplatform.trading.entity.PositionEntity;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.PositionStatus;
import com.fxplatform.trading.repository.PositionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AccountSnapshotService {

  private static final int MONEY_SCALE = 8;
  private static final int RATIO_SCALE = 8;

  private final TradingAccountRepository accountRepository;
  private final PositionRepository positionRepository;
  private final QuoteService quoteService;
  private final PnLCalculator pnlCalculator;
  private final SymbolRepository symbolRepository;
  private final TradingInstrumentClassifier instrumentClassifier = new TradingInstrumentClassifier();
  private final PerpMarginCalculator perpMarginCalculator = new PerpMarginCalculator();

  public AccountSnapshot snapshot(UUID accountId) {
    TradingAccountEntity account = accountRepository.findById(accountId)
        .orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND", "Account not found"));
    return snapshot(account);
  }

  public AccountSnapshot snapshot(TradingAccountEntity account) {
    List<PositionEntity> openPositions = positionRepository.findByAccountIdAndStatusOrderByOpenedAtDesc(
        account.getId(),
        PositionStatus.OPEN);
    List<PositionValuation> valuations = openPositions.stream()
        .map(position -> valuation(position, account))
        .toList();
    BigDecimal openFloatingPnl = valuations.stream()
        .map(PositionValuation::floatingPnl)
        .reduce(BigDecimal.ZERO, BigDecimal::add)
        .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    BigDecimal openPositionMargin = openPositions.stream()
        .map(this::positionMargin)
        .reduce(BigDecimal.ZERO, BigDecimal::add)
        .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    BigDecimal positionValue = valuations.stream()
        .map(PositionValuation::positionValue)
        .reduce(BigDecimal.ZERO, BigDecimal::add)
        .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    BigDecimal maintenanceMargin = valuations.stream()
        .map(PositionValuation::maintenanceMargin)
        .reduce(BigDecimal.ZERO, BigDecimal::add)
        .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    BigDecimal balance = orZero(account.getBalance());
    BigDecimal equity = balance.add(openFloatingPnl).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    BigDecimal usedMargin = usedMargin(account, openPositions, openPositionMargin);
    BigDecimal freeMargin = equity.subtract(usedMargin).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    String warning = usedMarginWarning(account, openPositions, openPositionMargin);

    return new AccountSnapshot(
        account.getId(),
        balance,
        openFloatingPnl,
        equity,
        usedMargin,
        maintenanceMargin,
        freeMargin,
        marginLevel(equity, usedMargin),
        account.getBaseCurrency(),
        positionValue,
        freeMargin,
        Instant.now(),
        warning);
  }

  private PositionValuation valuation(PositionEntity position, TradingAccountEntity account) {
    QuoteResponse quote = quoteService.freshQuote(position.getSymbol());
    BigDecimal closeoutPrice = closeoutPrice(position, quote);
    SymbolEntity symbol = symbolFor(position);
    InstrumentProfile profile = instrumentClassifier.profile(symbol);
    BigDecimal valuationPrice = isPerpetual(profile.kind()) ? markPrice(quote, closeoutPrice) : closeoutPrice;
    BigDecimal floatingPnl = floatingPnl(position, account.getBaseCurrency(), symbol, profile, valuationPrice);
    if (isPerpetual(profile.kind())) {
      PerpMarginCalculator.MarginResult margin = perpMarginCalculator.calculate(
          profile,
          position.getLots(),
          valuationPrice,
          positionLeverage(position, account));
      return new PositionValuation(floatingPnl, margin.notional(), margin.maintenanceMargin());
    }
    return new PositionValuation(
        floatingPnl,
        orZero(position.getNotional()),
        orZero(position.getMaintenanceMargin()));
  }

  private BigDecimal floatingPnl(
      PositionEntity position,
      String accountCurrency,
      SymbolEntity symbol,
      InstrumentProfile profile,
      BigDecimal valuationPrice
  ) {
    if (profile.kind() == InstrumentKind.FOREX) {
      return pnlCalculator.floatingPnl(
          symbol.getSymbol(),
          accountCurrency,
          position.getSide(),
          position.getLots(),
          position.getOpenPrice(),
          valuationPrice);
    }
    return pnlCalculator.floatingPnl(
        profile.kind(),
        position.getSide(),
        position.getLots(),
        position.getOpenPrice(),
        valuationPrice,
        profile.unitSize());
  }

  private BigDecimal closeoutPrice(PositionEntity position, QuoteResponse quote) {
    return position.getSide() == OrderSide.BUY ? quote.bid() : quote.ask();
  }

  private BigDecimal markPrice(QuoteResponse quote, BigDecimal closeoutPrice) {
    if (quote.markPrice() != null) {
      return quote.markPrice();
    }
    if (quote.mid() != null) {
      return quote.mid();
    }
    return closeoutPrice;
  }

  private int positionLeverage(PositionEntity position, TradingAccountEntity account) {
    if (position.getLeverage() != null && position.getLeverage() > 0) {
      return position.getLeverage();
    }
    return account.getLeverage() != null && account.getLeverage() > 0 ? account.getLeverage() : 1;
  }

  private boolean isPerpetual(InstrumentKind kind) {
    return kind == InstrumentKind.LINEAR_PERPETUAL || kind == InstrumentKind.INVERSE_PERPETUAL;
  }

  private BigDecimal positionMargin(PositionEntity position) {
    BigDecimal marginHeld = orZero(position.getMarginHeld());
    return marginHeld.compareTo(BigDecimal.ZERO) > 0 ? marginHeld : orZero(position.getInitialMargin());
  }

  private BigDecimal usedMargin(
      TradingAccountEntity account,
      List<PositionEntity> openPositions,
      BigDecimal openPositionMargin
  ) {
    if (!openPositions.isEmpty()) {
      return openPositionMargin;
    }
    BigDecimal ledgerUsedMargin = orZero(account.getUsedMargin());
    return ledgerUsedMargin.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  private String usedMarginWarning(
      TradingAccountEntity account,
      List<PositionEntity> openPositions,
      BigDecimal openPositionMargin
  ) {
    BigDecimal ledgerUsedMargin = orZero(account.getUsedMargin());
    if (openPositions.isEmpty()) {
      return null;
    }
    if (ledgerUsedMargin.compareTo(openPositionMargin) == 0) {
      return null;
    }
    return "Persisted account.usedMargin " + ledgerUsedMargin
        + " does not match open position margin " + openPositionMargin;
  }

  private BigDecimal marginLevel(BigDecimal equity, BigDecimal usedMargin) {
    if (usedMargin.compareTo(BigDecimal.ZERO) <= 0) {
      return null;
    }
    return equity
        .multiply(new BigDecimal("100"))
        .divide(usedMargin, RATIO_SCALE, RoundingMode.HALF_UP);
  }

  private SymbolEntity symbolFor(PositionEntity position) {
    String normalized = normalize(position.getSymbol());
    if (normalized.isBlank()) {
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

  private record PositionValuation(
      BigDecimal floatingPnl,
      BigDecimal positionValue,
      BigDecimal maintenanceMargin
  ) {
  }
}
