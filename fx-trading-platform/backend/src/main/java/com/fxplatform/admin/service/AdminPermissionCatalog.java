package com.fxplatform.admin.service;

import com.fxplatform.auth.enums.UserStatus;
import java.util.Map;
import java.util.Optional;

public final class AdminPermissionCatalog {

  public static final String MARKET_SYMBOL_READ = "market:symbol:read";
  public static final String MARKET_SYMBOL_CREATE = "market:symbol:create";
  public static final String MARKET_SYMBOL_UPDATE = "market:symbol:update";
  public static final String MARKET_SYMBOL_DISABLE = "market:symbol:disable";
  public static final String MARKET_DATA_PROVIDER_UPDATE = "market:data-provider:update";

  public static final String FINANCE_FUND_ORDER_READ = "finance:fund-order:read";
  public static final String FINANCE_FUND_ORDER_APPROVE = "finance:fund-order:approve";
  public static final String FINANCE_FUND_ORDER_REJECT = "finance:fund-order:reject";
  public static final String FINANCE_ADJUSTMENT_CREATE = "finance:adjustment:create";

  public static final String TRADING_ORDER_READ = "trading:order:read";
  public static final String TRADING_ORDER_CANCEL = "trading:order:cancel";
  public static final String TRADING_POSITION_FORCE_CLOSE = "trading:position:force-close";

  public static final String USER_READ = "user:read";
  public static final String USER_UPDATE = "user:update";
  public static final String USER_DISABLE = "user:disable";
  public static final String USER_FORCE_LOGOUT = "user:force-logout";

  private AdminPermissionCatalog() {
  }

  public static Optional<String> permissionForFeatureAction(
      String pageKey,
      String action,
      Map<String, Object> payload
  ) {
    Map<String, Object> safePayload = payload == null ? Map.of() : payload;
    return Optional.ofNullable(switch (pageKey + ":" + action) {
      case "products:create" -> MARKET_SYMBOL_CREATE;
      case "products:edit", "products:risk" -> MARKET_SYMBOL_UPDATE;
      case "products:delete" -> MARKET_SYMBOL_DISABLE;
      case "price-schedules:create", "price-schedules:cancel" -> FINANCE_ADJUSTMENT_CREATE;
      case "recharge-orders:review", "withdrawal-orders:review" -> fundReviewPermission(safePayload);
      case "order-history:cancel" -> TRADING_ORDER_CANCEL;
      case "order-history:close-position" -> TRADING_POSITION_FORCE_CLOSE;
      case "members:kick-offline" -> USER_FORCE_LOGOUT;
      case "members:edit" -> memberEditPermission(safePayload);
      case "members:real-name", "members:remark", "members:one-click-profit", "members:one-click-normal",
          "members:send-message" -> USER_UPDATE;
      default -> null;
    });
  }

  private static String fundReviewPermission(Map<String, Object> payload) {
    return AdminFeaturePayloads.approved(payload) ? FINANCE_FUND_ORDER_APPROVE : FINANCE_FUND_ORDER_REJECT;
  }

  private static String memberEditPermission(Map<String, Object> payload) {
    return AdminFeaturePayloads.userStatus(payload) == UserStatus.DISABLED ? USER_DISABLE : USER_UPDATE;
  }
}
