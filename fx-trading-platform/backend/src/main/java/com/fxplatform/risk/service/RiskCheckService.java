package com.fxplatform.risk.service;

import static com.fxplatform.common.money.MoneyAmount.orZero;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.market.dto.QuoteResponse;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.repository.SymbolRepository;
import com.fxplatform.market.service.QuoteService;
import com.fxplatform.trading.dto.request.CreateOrderRequest;
import com.fxplatform.trading.enums.OrderSide;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RiskCheckService {

  private static final BigDecimal DEFAULT_CONTRACT_SIZE = new BigDecimal("100000");

  private final QuoteService quoteService;
  private final SymbolRepository symbolRepository;
  private final MarginCalculator marginCalculator;

  public BigDecimal checkOrder(TradingAccountEntity account, CreateOrderRequest request) {
    if (request.quantity().compareTo(BigDecimal.ZERO) <= 0) {
      throw new BusinessException("BAD_QUANTITY", "Quantity must be greater than zero");
    }

    SymbolEntity symbol = symbolRepository.findBySymbol(request.symbol())
        .orElseThrow(() -> new BusinessException("SYMBOL_NOT_FOUND", "Symbol not found"));
    QuoteResponse quote = quoteService.freshQuote(request.symbol());
    BigDecimal price = request.side() == OrderSide.BUY ? quote.ask() : quote.bid();
    int leverage = effectiveLeverage(account, symbol, request.leverage());
    BigDecimal requiredMargin = marginCalculator.requiredMargin(
        request.quantity(),
        price,
        leverage,
        contractSize(symbol));

    if (orZero(account.getFreeMargin()).compareTo(requiredMargin) < 0) {
      throw new BusinessException("INSUFFICIENT_MARGIN", "Free margin is not enough");
    }
    return requiredMargin;
  }

  public int resolveEffectiveLeverage(TradingAccountEntity account, CreateOrderRequest request) {
    SymbolEntity symbol = symbolRepository.findBySymbol(request.symbol())
        .orElseThrow(() -> new BusinessException("SYMBOL_NOT_FOUND", "Symbol not found"));
    return effectiveLeverage(account, symbol, request.leverage());
  }

  private int effectiveLeverage(TradingAccountEntity account, SymbolEntity symbol, Integer requestLeverage) {
    int accountLeverage = positiveOrDefault(account.getLeverage(), 1);
    int requestedOrAccountLeverage = positiveOrDefault(requestLeverage, accountLeverage);
    Integer symbolLeverage = symbol.getLeverage();
    if (symbolLeverage == null || symbolLeverage <= 0) {
      return requestedOrAccountLeverage;
    }
    return Math.min(requestedOrAccountLeverage, symbolLeverage);
  }

  private BigDecimal contractSize(SymbolEntity symbol) {
    BigDecimal lotSize = symbol.getLotSize();
    if (lotSize == null || lotSize.compareTo(BigDecimal.ZERO) <= 0) {
      return DEFAULT_CONTRACT_SIZE;
    }
    return lotSize;
  }

  private int positiveOrDefault(Integer value, int defaultValue) {
    return value == null || value <= 0 ? defaultValue : value;
  }
}
