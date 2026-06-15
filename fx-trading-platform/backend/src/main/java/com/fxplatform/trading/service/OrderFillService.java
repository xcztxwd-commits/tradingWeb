package com.fxplatform.trading.service;

import static com.fxplatform.common.money.MoneyAmount.accountEquity;
import static com.fxplatform.common.money.MoneyAmount.orZero;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.execution.ExecutionResult;
import com.fxplatform.ledger.service.LedgerService;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.entity.PositionEntity;
import com.fxplatform.trading.entity.TradeEntity;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.repository.OrderRepository;
import com.fxplatform.trading.repository.PositionRepository;
import com.fxplatform.trading.repository.TradeRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OrderFillService {

  private final OrderRepository orderRepository;
  private final TradeRepository tradeRepository;
  private final PositionRepository positionRepository;
  private final TradingAccountRepository accountRepository;
  private final LedgerService ledgerService;

  public OrderEntity fill(
      OrderEntity order,
      TradingAccountEntity account,
      BigDecimal executionPrice,
      Instant filledAt,
      BigDecimal requiredMargin,
      String marginDescription
  ) {
    return fill(order, account, new ExecutionResult(executionPrice, filledAt), requiredMargin, marginDescription);
  }

  public OrderEntity fill(
      OrderEntity order,
      TradingAccountEntity account,
      ExecutionResult execution,
      BigDecimal requiredMargin,
      String marginDescription
  ) {
    BigDecimal orderQuantity = order.getQuantity() != null ? order.getQuantity() : order.getLots();
    BigDecimal filledQuantity = execution.filledQuantity() != null ? execution.filledQuantity() : orderQuantity;
    BigDecimal remainingQuantity = execution.remainingQuantity() != null
        ? execution.remainingQuantity()
        : orderQuantity.subtract(filledQuantity).max(BigDecimal.ZERO);
    BigDecimal fee = orZero(execution.fee());
    BigDecimal slippage = orZero(execution.slippage());
    BigDecimal existingOrderHold = orZero(order.getHoldAmount());
    BigDecimal fullMargin = requiredMargin != null ? requiredMargin : existingOrderHold;
    BigDecimal marginToHold = proportionalMargin(fullMargin, filledQuantity, orderQuantity);

    order.setStatus(remainingQuantity.compareTo(BigDecimal.ZERO) > 0 ? OrderStatus.PARTIALLY_FILLED : OrderStatus.FILLED);
    order.setExecutionPrice(execution.filledPrice());
    order.setAvgFillPrice(execution.filledPrice());
    order.setFilledQuantity(filledQuantity);
    order.setRemainingQuantity(remainingQuantity);
    order.setFee(fee);
    order.setSlippage(slippage);
    order.setFilledAt(execution.filledAt());
    orderRepository.save(order);

    TradeEntity trade = new TradeEntity();
    trade.setOrderId(order.getId());
    trade.setAccountId(account.getId());
    trade.setSymbol(order.getSymbol());
    trade.setSide(order.getSide());
    trade.setLots(filledQuantity);
    trade.setPrice(execution.filledPrice());
    tradeRepository.save(trade);

    PositionEntity position = new PositionEntity();
    position.setAccountId(account.getId());
    position.setSymbol(order.getSymbol());
    position.setSide(order.getSide());
    position.setLots(filledQuantity);
    position.setOpenPrice(execution.filledPrice());
    position.setCurrentPrice(execution.filledPrice());
    position.setStopLoss(order.getStopLoss());
    position.setTakeProfit(order.getTakeProfit());
    position.setMarginHeld(marginToHold);
    position.setLeverage(positionLeverage(order, account));
    PositionEntity savedPosition = positionRepository.save(position);

    BigDecimal delta = marginToHold.subtract(existingOrderHold);
    boolean accountChanged = false;
    if (delta.compareTo(BigDecimal.ZERO) > 0) {
      if (accountRepository.reserveMarginIfAvailable(account.getId(), delta) != 1) {
        throw new BusinessException("INSUFFICIENT_MARGIN", "Free margin is not enough");
      }
      account.setUsedMargin(orZero(account.getUsedMargin()).add(delta));
      accountChanged = true;
    } else if (delta.compareTo(BigDecimal.ZERO) < 0) {
      account.setUsedMargin(orZero(account.getUsedMargin()).add(delta).max(BigDecimal.ZERO));
      accountChanged = true;
    }
    if (fee.compareTo(BigDecimal.ZERO) > 0) {
      BigDecimal balanceBeforeFee = orZero(account.getBalance());
      BigDecimal equityBeforeFee = accountEquity(account);
      account.setBalance(balanceBeforeFee.subtract(fee));
      account.setEquity(equityBeforeFee.subtract(fee));
      accountChanged = true;
    }
    if (accountChanged) {
      account.setFreeMargin(accountEquity(account).subtract(orZero(account.getUsedMargin())));
      accountRepository.save(account);
    }
    if (delta.compareTo(BigDecimal.ZERO) > 0) {
      ledgerService.recordMarginHold(account, delta, savedPosition.getId(), marginDescription);
    }
    if (fee.compareTo(BigDecimal.ZERO) > 0) {
      ledgerService.recordTradeFee(account, fee, savedPosition.getId(), "Trade fee charged");
    }

    return order;
  }

  private BigDecimal proportionalMargin(BigDecimal fullMargin, BigDecimal filledQuantity, BigDecimal orderQuantity) {
    if (orderQuantity == null || orderQuantity.compareTo(BigDecimal.ZERO) <= 0) {
      return BigDecimal.ZERO;
    }
    return orZero(fullMargin)
        .multiply(orZero(filledQuantity))
        .divide(orderQuantity, 8, RoundingMode.HALF_UP);
  }

  private Integer positionLeverage(OrderEntity order, TradingAccountEntity account) {
    if (order.getLeverage() != null && order.getLeverage() > 0) {
      return order.getLeverage();
    }
    return account.getLeverage();
  }
}
