package com.fxplatform.risk.service;

import static com.fxplatform.common.money.MoneyAmount.orZero;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.service.AccountSnapshotService;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.market.dto.QuoteResponse;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.model.SymbolAssets;
import com.fxplatform.market.repository.SymbolRepository;
import com.fxplatform.market.service.QuoteService;
import com.fxplatform.market.service.SymbolAssetResolver;
import com.fxplatform.risk.model.InstrumentKind;
import com.fxplatform.risk.model.InstrumentProfile;
import com.fxplatform.trading.dto.request.CreateOrderRequest;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.wallet.service.WalletService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class RiskCheckService {

  private static final BigDecimal DEFAULT_CONTRACT_SIZE = new BigDecimal("100000");

  private final QuoteService quoteService;
  private final SymbolRepository symbolRepository;
  private final MarginCalculator marginCalculator;
  private final AccountSnapshotService accountSnapshotService;
  private final WalletService walletService;
  private final TradingInstrumentClassifier instrumentClassifier = new TradingInstrumentClassifier();

  @Autowired
  public RiskCheckService(
      QuoteService quoteService,
      SymbolRepository symbolRepository,
      MarginCalculator marginCalculator,
      AccountSnapshotService accountSnapshotService,
      WalletService walletService
  ) {
    this.quoteService = quoteService;
    this.symbolRepository = symbolRepository;
    this.marginCalculator = marginCalculator;
    this.accountSnapshotService = accountSnapshotService;
    this.walletService = walletService;
  }

  public RiskCheckService(
      QuoteService quoteService,
      SymbolRepository symbolRepository,
      MarginCalculator marginCalculator,
      AccountSnapshotService accountSnapshotService
  ) {
    this(quoteService, symbolRepository, marginCalculator, accountSnapshotService, null);
  }

  public RiskCheckService(
      QuoteService quoteService,
      SymbolRepository symbolRepository,
      MarginCalculator marginCalculator
  ) {
    this(quoteService, symbolRepository, marginCalculator, null, null);
  }

  public BigDecimal checkOrder(TradingAccountEntity account, CreateOrderRequest request) {
    if (request.quantity().compareTo(BigDecimal.ZERO) <= 0) {
      throw new BusinessException("BAD_QUANTITY", "Quantity must be greater than zero");
    }

    SymbolEntity symbol = symbolRepository.findBySymbol(request.symbol())
        .orElseThrow(() -> new BusinessException("SYMBOL_NOT_FOUND", "Symbol not found"));
    QuoteResponse quote = quoteService.freshQuote(request.symbol());
    BigDecimal price = request.side() == OrderSide.BUY ? quote.ask() : quote.bid();
    InstrumentProfile profile = instrumentClassifier.profile(symbol);
    if (profile.kind() == InstrumentKind.SPOT) {
      return checkSpotOrder(account, request, symbol, price, profile.unitSize());
    }
    int leverage = effectiveLeverage(account, symbol, request.leverage());
    BigDecimal requiredMargin = marginCalculator.requiredMargin(
        profile.kind(),
        request.quantity(),
        price,
        leverage,
        profile.unitSize());

    if (availableFreeMargin(account).compareTo(requiredMargin) < 0) {
      throw new BusinessException("INSUFFICIENT_MARGIN", "Free margin is not enough");
    }
    return requiredMargin;
  }

  public int resolveEffectiveLeverage(TradingAccountEntity account, CreateOrderRequest request) {
    SymbolEntity symbol = symbolRepository.findBySymbol(request.symbol())
        .orElseThrow(() -> new BusinessException("SYMBOL_NOT_FOUND", "Symbol not found"));
    return effectiveLeverage(account, symbol, request.leverage());
  }

  public boolean isSpotSymbol(String symbolCode) {
    return symbolRepository.findBySymbol(symbolCode)
        .map(symbol -> instrumentClassifier.profile(symbol).kind() == InstrumentKind.SPOT)
        .orElse(false);
  }

  public String resolveHoldCurrency(TradingAccountEntity account, CreateOrderRequest request) {
    SymbolEntity symbol = symbolRepository.findBySymbol(request.symbol())
        .orElseThrow(() -> new BusinessException("SYMBOL_NOT_FOUND", "Symbol not found"));
    if (instrumentClassifier.profile(symbol).kind() == InstrumentKind.SPOT) {
      SymbolAssets assets = SymbolAssetResolver.resolve(symbol);
      return request.side() == OrderSide.BUY ? assets.quoteAsset() : assets.baseAsset();
    }
    return account.getBaseCurrency();
  }

  private int effectiveLeverage(TradingAccountEntity account, SymbolEntity symbol, Integer requestLeverage) {
    if (instrumentClassifier.profile(symbol).kind() == com.fxplatform.risk.model.InstrumentKind.SPOT) {
      return 1;
    }
    int accountLeverage = positiveOrDefault(account.getLeverage(), 1);
    int requestedOrAccountLeverage = positiveOrDefault(requestLeverage, accountLeverage);
    Integer symbolLeverage = symbol.getLeverage();
    if (symbolLeverage == null || symbolLeverage <= 0) {
      return requestedOrAccountLeverage;
    }
    return Math.min(requestedOrAccountLeverage, symbolLeverage);
  }

  private BigDecimal availableFreeMargin(TradingAccountEntity account) {
    if (accountSnapshotService == null) {
      return orZero(account.getFreeMargin());
    }
    return orZero(accountSnapshotService.snapshot(account).freeMargin());
  }

  private BigDecimal checkSpotOrder(
      TradingAccountEntity account,
      CreateOrderRequest request,
      SymbolEntity symbol,
      BigDecimal price,
      BigDecimal unitSize
  ) {
    if (walletService == null) {
      throw new BusinessException("SPOT_WALLET_REQUIRED", "Spot wallet is required");
    }

    SymbolAssets assets = SymbolAssetResolver.resolve(symbol);
    BigDecimal baseQuantity = request.quantity().multiply(unitSize).setScale(8, RoundingMode.HALF_UP);
    BigDecimal referencePrice = request.orderType() == OrderType.MARKET ? price : request.price();
    if (referencePrice == null || referencePrice.compareTo(BigDecimal.ZERO) <= 0) {
      throw new BusinessException("ORDER_PRICE_REQUIRED", "Limit and stop orders require requested price");
    }
    if (request.side() == OrderSide.BUY) {
      BigDecimal quoteRequired = baseQuantity.multiply(referencePrice).setScale(8, RoundingMode.HALF_UP);
      BigDecimal quoteAvailable = walletAvailable(account, assets.quoteAsset());
      if (quoteAvailable.compareTo(quoteRequired) < 0) {
        throw new BusinessException("INSUFFICIENT_WALLET_BALANCE", "Quote wallet available is not enough");
      }
      return quoteRequired;
    }

    BigDecimal baseAvailable = walletAvailable(account, assets.baseAsset());
    if (baseAvailable.compareTo(baseQuantity) < 0) {
      throw new BusinessException("INSUFFICIENT_WALLET_BALANCE", "Base wallet available is not enough");
    }
    return baseQuantity;
  }

  private BigDecimal walletAvailable(TradingAccountEntity account, String asset) {
    return walletService.getBalance(account.getId(), asset)
        .map(balance -> orZero(balance.getAvailable()))
        .orElse(BigDecimal.ZERO);
  }

  private int positiveOrDefault(Integer value, int defaultValue) {
    return value == null || value <= 0 ? defaultValue : value;
  }
}
