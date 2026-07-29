package com.fxplatform.admin.service;

import com.fxplatform.auth.enums.UserStatus;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

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
  public static final String TRADING_ACCOUNT_FORCE_CLEANUP = "trading:account:force-cleanup";
  public static final String TRADING_ACCOUNT_DEMO_RESET = "trading:account:demo-reset";

  public static final String TRADING_LAB_VIEW = "TRADING_LAB_VIEW";
  public static final String TRADING_LAB_EXECUTE = "TRADING_LAB_EXECUTE";
  public static final String SUPER_ADMIN = "SUPER_ADMIN";
  public static final UUID SUPER_ADMIN_ROLE_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000061");

  public static final String USER_READ = "user:read";
  public static final String USER_UPDATE = "user:update";
  public static final String USER_DISABLE = "user:disable";
  public static final String USER_FORCE_LOGOUT = "user:force-logout";

  public static final String CONTENT_CAMPAIGN_READ = "content:campaign:read";
  public static final String CONTENT_CAMPAIGN_EDIT = "content:campaign:edit";
  public static final String CONTENT_CAMPAIGN_PUBLISH = "content:campaign:publish";
  public static final String CONTENT_CAMPAIGN_DELETE = "content:campaign:delete";
  public static final String CONTENT_CAMPAIGN_STATS = "content:campaign:stats";
  public static final String CONTENT_CAMPAIGN_USER_DETAIL = "content:campaign:user-detail";
  public static final String CONTENT_POPUP_POLICY_UPDATE = "content:popup-policy:update";
  public static final String CONTENT_MESSAGE_READ = "content:message:read";
  public static final String CONTENT_MESSAGE_EDIT = "content:message:edit";
  public static final String CONTENT_MESSAGE_SEND = "content:message:send";
  public static final String CONTENT_MESSAGE_DELETE = "content:message:delete";

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
