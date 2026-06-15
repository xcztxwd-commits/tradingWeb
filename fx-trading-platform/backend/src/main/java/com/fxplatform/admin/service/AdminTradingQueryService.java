package com.fxplatform.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fxplatform.admin.dto.AdminPageResponse;
import com.fxplatform.admin.dto.response.AdminOrderResponse;
import com.fxplatform.admin.dto.response.AdminPositionResponse;
import com.fxplatform.admin.dto.response.AdminTradeResponse;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.entity.PositionEntity;
import com.fxplatform.trading.entity.TradeEntity;
import com.fxplatform.trading.repository.OrderRepository;
import com.fxplatform.trading.repository.PositionRepository;
import com.fxplatform.trading.repository.TradeRepository;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 后台交易只读查询服务。
 */
@Service
@RequiredArgsConstructor
public class AdminTradingQueryService {

  /** 订单 Mapper，用于后台订单分页查询。 */
  private final OrderRepository orderRepository;
  /** 持仓 Mapper，用于后台持仓分页查询。 */
  private final PositionRepository positionRepository;
  /** 成交 Mapper，用于后台成交分页查询。 */
  private final TradeRepository tradeRepository;

  /** 分页查询全部订单，按创建时间倒序返回。 */
  public AdminPageResponse<AdminOrderResponse> orders(int page, int size) {
    return AdminPageResponse.from(orderRepository
        .findAll(AdminPageRequests.page(page, size), Map.of("createdAt", "created_at"), "createdAt", false)
        .convert(AdminOrderResponse::from));
  }

  /** 按截图订单列表协议分页筛选订单。 */
  public AdminPageResponse<AdminOrderResponse> orders(AdminFeaturePageQuery query) {
    QueryWrapper<OrderEntity> wrapper = new QueryWrapper<>();
    AdminFeatureQuerySupport.eqUuidIfPresent(wrapper, query, "userId", "user_id");
    AdminFeatureQuerySupport.eqUuidIfPresent(wrapper, query, "uid", "user_id");
    AdminFeatureQuerySupport.eqUuidIfPresent(wrapper, query, "accountId", "account_id");
    AdminFeatureQuerySupport.likeIfPresent(wrapper, query, "symbol", "symbol");
    AdminFeatureQuerySupport.likeIfPresent(wrapper, query, "product", "symbol");
    AdminFeatureQuerySupport.eqIfPresent(wrapper, query, "side", "side");
    AdminFeatureQuerySupport.eqIfPresent(wrapper, query, "direction", "side");
    AdminFeatureQuerySupport.eqIfPresent(wrapper, query, "status", "status");
    AdminFeatureQuerySupport.eqIfPresent(wrapper, query, "orderType", "order_type");
    AdminFeatureQuerySupport.eqIfPresent(wrapper, query, "type", "order_type");
    AdminFeatureQuerySupport.applyOrder(wrapper, query, Map.ofEntries(
        Map.entry("userId", "user_id"),
        Map.entry("uid", "user_id"),
        Map.entry("symbol", "symbol"),
        Map.entry("product", "symbol"),
        Map.entry("side", "side"),
        Map.entry("direction", "side"),
        Map.entry("status", "status"),
        Map.entry("orderType", "order_type"),
        Map.entry("type", "order_type"),
        Map.entry("lots", "lots"),
        Map.entry("executionPrice", "execution_price"),
        Map.entry("createdAt", "created_at"),
        Map.entry("updatedAt", "updated_at")), "created_at", false);
    return AdminPageResponse.from(orderRepository
        .selectPage(AdminFeatureQuerySupport.page(query), wrapper)
        .convert(AdminOrderResponse::from));
  }

  /** 分页查询全部持仓，按开仓时间倒序返回。 */
  public AdminPageResponse<AdminPositionResponse> positions(int page, int size) {
    return AdminPageResponse.from(positionRepository
        .findAll(AdminPageRequests.page(page, size), Map.of("openedAt", "opened_at"), "openedAt", false)
        .convert(AdminPositionResponse::from));
  }

  /** 按截图列表协议分页筛选持仓。 */
  public AdminPageResponse<AdminPositionResponse> positions(AdminFeaturePageQuery query) {
    QueryWrapper<PositionEntity> wrapper = new QueryWrapper<>();
    AdminFeatureQuerySupport.eqUuidIfPresent(wrapper, query, "accountId", "account_id");
    AdminFeatureQuerySupport.likeIfPresent(wrapper, query, "symbol", "symbol");
    AdminFeatureQuerySupport.likeIfPresent(wrapper, query, "product", "symbol");
    AdminFeatureQuerySupport.eqIfPresent(wrapper, query, "side", "side");
    AdminFeatureQuerySupport.eqIfPresent(wrapper, query, "direction", "side");
    AdminFeatureQuerySupport.eqIfPresent(wrapper, query, "status", "status");
    AdminFeatureQuerySupport.applyOrder(wrapper, query, Map.of(
        "symbol", "symbol",
        "product", "symbol",
        "side", "side",
        "direction", "side",
        "status", "status",
        "lots", "lots",
        "openedAt", "opened_at",
        "floatingPnl", "floating_pnl"), "opened_at", false);
    return AdminPageResponse.from(positionRepository
        .selectPage(AdminFeatureQuerySupport.page(query), wrapper)
        .convert(AdminPositionResponse::from));
  }

  /** 分页查询全部成交，按成交时间倒序返回。 */
  public AdminPageResponse<AdminTradeResponse> trades(int page, int size) {
    return AdminPageResponse.from(tradeRepository
        .findAll(AdminPageRequests.page(page, size), Map.of("executedAt", "executed_at"), "executedAt", false)
        .convert(AdminTradeResponse::from));
  }

  /** 按截图列表协议分页筛选成交记录。 */
  public AdminPageResponse<AdminTradeResponse> trades(AdminFeaturePageQuery query) {
    QueryWrapper<TradeEntity> wrapper = new QueryWrapper<>();
    AdminFeatureQuerySupport.eqUuidIfPresent(wrapper, query, "orderId", "order_id");
    AdminFeatureQuerySupport.eqUuidIfPresent(wrapper, query, "accountId", "account_id");
    AdminFeatureQuerySupport.likeIfPresent(wrapper, query, "symbol", "symbol");
    AdminFeatureQuerySupport.likeIfPresent(wrapper, query, "product", "symbol");
    AdminFeatureQuerySupport.eqIfPresent(wrapper, query, "side", "side");
    AdminFeatureQuerySupport.eqIfPresent(wrapper, query, "direction", "side");
    AdminFeatureQuerySupport.applyOrder(wrapper, query, Map.of(
        "orderId", "order_id",
        "symbol", "symbol",
        "product", "symbol",
        "side", "side",
        "direction", "side",
        "lots", "lots",
        "price", "price",
        "realizedPnl", "realized_pnl",
        "executedAt", "executed_at"), "executed_at", false);
    return AdminPageResponse.from(tradeRepository
        .selectPage(AdminFeatureQuerySupport.page(query), wrapper)
        .convert(AdminTradeResponse::from));
  }

  /** 返回成交记录总数，供后台统计使用。 */
  public long tradeCount() {
    return tradeRepository.count();
  }
}
