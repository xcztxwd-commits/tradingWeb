package com.fxplatform;

import static org.assertj.core.api.Assertions.assertThat;

import com.fxplatform.admin.enums.AdminFeatureRecordStatus;
import com.fxplatform.admin.enums.AdminTaskStatus;
import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.auth.enums.KycStatus;
import com.fxplatform.finance.enums.FundOrderStatus;
import com.fxplatform.finance.enums.FundOrderType;
import com.fxplatform.finance.enums.PaymentAccountType;
import com.fxplatform.market.enums.PriceAdjustmentStatus;
import com.fxplatform.market.enums.ProviderHealthStatus;
import com.fxplatform.trading.dto.response.OrderResponse;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.entity.PositionEntity;
import com.fxplatform.trading.entity.TradeEntity;
import com.fxplatform.wallet.enums.AssetLedgerEntryType;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

class DomainEnumContractTest {

  private static final String ENUM_PACKAGE = "com.fxplatform.trading.enums.";

  @Test
  void fundOrderEnumsKeepExistingCodesAndAliases() {
    assertThat(FundOrderType.fromCode("DEPOSIT")).isEqualTo(FundOrderType.RECHARGE);
    assertThat(FundOrderType.fromCode("withdraw")).isEqualTo(FundOrderType.WITHDRAWAL);

    assertThat(FundOrderStatus.PENDING_REVIEW.code()).isEqualTo("PENDING_REVIEW");
    assertThat(FundOrderStatus.PENDING_REVIEW.isPendingReview()).isTrue();
    assertThat(FundOrderStatus.PENDING.isPendingReview()).isTrue();
    assertThat(FundOrderStatus.fromReviewCode("通过")).isEqualTo(FundOrderStatus.APPROVED);
    assertThat(FundOrderStatus.fromReviewCode("拒绝")).isEqualTo(FundOrderStatus.REJECTED);
  }

  @Test
  void kycAndPaymentAccountEnumsKeepAdminInputCompatibility() {
    assertThat(KycStatus.NOT_SUBMITTED.code()).isEqualTo("NOT_SUBMITTED");
    assertThat(KycStatus.fromReviewCode("待审核")).isEqualTo(KycStatus.PENDING);
    assertThat(KycStatus.fromReviewCode("approved")).isEqualTo(KycStatus.APPROVED);

    assertThat(PaymentAccountType.fromCode("银行卡")).isEqualTo(PaymentAccountType.BANK);
    assertThat(PaymentAccountType.fromCode("wallet")).isEqualTo(PaymentAccountType.WALLET);
  }

  @Test
  void adminAndMarketStatusEnumsKeepStoredCodes() {
    assertThat(AdminTaskStatus.QUEUED.code()).isEqualTo("QUEUED");
    assertThat(AdminFeatureRecordStatus.fromAction("delete"))
        .isEqualTo(AdminFeatureRecordStatus.DELETED);
    assertThat(AdminFeatureRecordStatus.fromAction("submit"))
        .isEqualTo(AdminFeatureRecordStatus.ACTIVE);

    assertThat(ProviderHealthStatus.UNKNOWN.code()).isEqualTo("UNKNOWN");
    assertThat(ProviderHealthStatus.fromConfigured(true)).isEqualTo(ProviderHealthStatus.UP);
    assertThat(ProviderHealthStatus.fromConfigured(false)).isEqualTo(ProviderHealthStatus.DOWN);

    assertThat(PriceAdjustmentStatus.SCHEDULED.code()).isEqualTo("SCHEDULED");
    assertThat(PriceAdjustmentStatus.CANCELED.isCanceled()).isTrue();
  }

  @Test
  void assetLedgerEntryTypeKeepsWalletLedgerCodes() {
    assertThat(AssetLedgerEntryType.fromCode("credit_available"))
        .isEqualTo(AssetLedgerEntryType.CREDIT_AVAILABLE);
    assertThat(AssetLedgerEntryType.LOCK_AVAILABLE.code()).isEqualTo("LOCK_AVAILABLE");
    assertThat(AssetLedgerEntryType.RELEASE_LOCKED.code()).isEqualTo("RELEASE_LOCKED");
  }

  @TestFactory
  Stream<DynamicTest> exposesRequiredEnumValues() {
    return Stream.of(
            enumContract("PositionMode", "ONE_WAY", "HEDGE"),
            enumContract("PositionSide", "BOTH", "LONG", "SHORT"),
            enumContract("MarginMode", "CASH", "CROSS", "ISOLATED"),
            enumContract("QuantityUnit", "BASE", "QUOTE", "CONTRACTS"),
            enumContract("TimeInForce", "GTC"),
            enumContract(
                "OrderOrigin",
                "USER",
                "PROTECTIVE",
                "LIQUIDATION",
                "ADMIN_FORCE_CLOSE",
                "BATCH_CLOSE",
                "OCO"),
            enumContract("ProtectionType", "TAKE_PROFIT", "STOP_LOSS"),
            enumContract("TriggerPriceType", "LAST_PRICE", "MARK_PRICE"),
            enumContract("TriggerExecutionType", "MARKET", "LIMIT"),
            enumContract("LiquidityRole", "MAKER", "TAKER"),
            enumContract("OrderType", "MARKET", "LIMIT", "STOP", "STOP_MARKET"),
            enumContract(
                "OrderStatus",
                "PENDING_ACTIVATION",
                "PENDING",
                "WORKING",
                "PARTIALLY_FILLED",
                "FILLED",
                "CANCELED",
                "REJECTED",
                "EXPIRED"))
        .map(contract -> DynamicTest.dynamicTest(
            contract.enumName(),
            () -> assertEnumValues(contract)));
  }

  @Test
  void mapsV46EntityColumnsToExactJavaTypes() throws Exception {
    assertFields(OrderEntity.class, Map.ofEntries(
        entry("productType", "com.fxplatform.market.model.ProductType"),
        entry("positionMode", ENUM_PACKAGE + "PositionMode"),
        entry("positionSide", ENUM_PACKAGE + "PositionSide"),
        entry("marginMode", ENUM_PACKAGE + "MarginMode"),
        entry("quantityUnit", ENUM_PACKAGE + "QuantityUnit"),
        entry("originalQuantity", BigDecimal.class.getName()),
        entry("baseQuantity", BigDecimal.class.getName()),
        entry("timeInForce", ENUM_PACKAGE + "TimeInForce"),
        entry("reduceOnly", Boolean.class.getName()),
        entry("orderOrigin", ENUM_PACKAGE + "OrderOrigin"),
        entry("systemReason", String.class.getName()),
        entry("triggerPrice", BigDecimal.class.getName()),
        entry("triggerPriceType", ENUM_PACKAGE + "TriggerPriceType"),
        entry("triggerExecutionType", ENUM_PACKAGE + "TriggerExecutionType"),
        entry("protectionType", ENUM_PACKAGE + "ProtectionType"),
        entry("parentOrderId", UUID.class.getName()),
        entry("parentPositionId", UUID.class.getName()),
        entry("contingencyGroupId", UUID.class.getName()),
        entry("holdOwnerOrderId", UUID.class.getName()),
        entry("liquidityRole", ENUM_PACKAGE + "LiquidityRole"),
        entry("feeAsset", String.class.getName()),
        entry("version", Long.class.getName())));

    assertFields(TradeEntity.class, Map.ofEntries(
        entry("productType", "com.fxplatform.market.model.ProductType"),
        entry("canonicalFullFill", Boolean.class.getName()),
        entry("positionSide", ENUM_PACKAGE + "PositionSide"),
        entry("marginMode", ENUM_PACKAGE + "MarginMode"),
        entry("fee", BigDecimal.class.getName()),
        entry("feeAsset", String.class.getName()),
        entry("liquidityRole", ENUM_PACKAGE + "LiquidityRole"),
        entry("systemReason", String.class.getName()),
        entry("sourceMode", String.class.getName()),
        entry("providerCode", String.class.getName())));

    assertFields(PositionEntity.class, Map.ofEntries(
        entry("productType", "com.fxplatform.market.model.ProductType"),
        entry("positionMode", ENUM_PACKAGE + "PositionMode"),
        entry("positionSide", ENUM_PACKAGE + "PositionSide"),
        entry("marginMode", ENUM_PACKAGE + "MarginMode"),
        entry("version", Long.class.getName())));

    assertFields(TradingAccountEntity.class, Map.ofEntries(
        entry("positionMode", ENUM_PACKAGE + "PositionMode"),
        entry("demoGeneration", Long.class.getName()),
        entry("resetAt", Instant.class.getName())));

    assertFields("com.fxplatform.trading.entity.AccountSymbolSettingEntity", Map.ofEntries(
        entry("accountId", UUID.class.getName()),
        entry("symbol", String.class.getName()),
        entry("leverage", Integer.class.getName()),
        entry("marginMode", ENUM_PACKAGE + "MarginMode"),
        entry("quantityUnit", ENUM_PACKAGE + "QuantityUnit"),
        entry("version", Long.class.getName())));
  }

  @Test
  void exposesImmutableOrderSnapshotFields() {
    assertRecordComponents(OrderResponse.class, Map.ofEntries(
        entry("productType", "com.fxplatform.market.model.ProductType"),
        entry("positionMode", ENUM_PACKAGE + "PositionMode"),
        entry("positionSide", ENUM_PACKAGE + "PositionSide"),
        entry("marginMode", ENUM_PACKAGE + "MarginMode"),
        entry("quantityUnit", ENUM_PACKAGE + "QuantityUnit"),
        entry("originalQuantity", BigDecimal.class.getName()),
        entry("baseQuantity", BigDecimal.class.getName()),
        entry("timeInForce", ENUM_PACKAGE + "TimeInForce"),
        entry("reduceOnly", Boolean.class.getName()),
        entry("origin", ENUM_PACKAGE + "OrderOrigin"),
        entry("systemReason", String.class.getName()),
        entry("fee", BigDecimal.class.getName()),
        entry("feeAsset", String.class.getName()),
        entry("liquidityRole", ENUM_PACKAGE + "LiquidityRole"),
        entry("triggerPrice", BigDecimal.class.getName()),
        entry("triggerPriceType", ENUM_PACKAGE + "TriggerPriceType"),
        entry("triggerExecutionType", ENUM_PACKAGE + "TriggerExecutionType"),
        entry("protectionType", ENUM_PACKAGE + "ProtectionType"),
        entry("parentOrderId", UUID.class.getName()),
        entry("parentPositionId", UUID.class.getName()),
        entry("contingencyGroupId", UUID.class.getName()),
        entry("holdOwnerOrderId", UUID.class.getName())));
  }

  @Test
  void exposesAccountSymbolSettingRepository() throws Exception {
    Class<?> repository = Class.forName(
        "com.fxplatform.trading.repository.AccountSymbolSettingRepository");

    assertThat(Arrays.stream(repository.getGenericInterfaces())
        .map(type -> type.getTypeName()))
        .anyMatch(type -> type.contains("AccountSymbolSettingEntity"));
  }

  private static EnumContract enumContract(String enumName, String... values) {
    return new EnumContract(enumName, values);
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static void assertEnumValues(EnumContract contract) throws Exception {
    Class<?> enumType = Class.forName(ENUM_PACKAGE + contract.enumName());
    assertThat(enumType.isEnum()).isTrue();
    for (String value : contract.values()) {
      assertThat(Enum.valueOf((Class<? extends Enum>) enumType, value).name()).isEqualTo(value);
    }
  }

  private static void assertFields(String className, Map<String, String> expectedFields)
      throws Exception {
    assertFields(Class.forName(className), expectedFields);
  }

  private static void assertFields(Class<?> type, Map<String, String> expectedFields) {
    Map<String, String> actualFields = new LinkedHashMap<>();
    for (Field field : type.getDeclaredFields()) {
      actualFields.put(field.getName(), field.getType().getName());
    }
    SoftAssertions softly = new SoftAssertions();
    expectedFields.forEach((name, fieldType) -> softly.assertThat(actualFields)
        .as(type.getSimpleName() + "." + name)
        .containsEntry(name, fieldType));
    softly.assertAll();
  }

  private static void assertRecordComponents(
      Class<?> type,
      Map<String, String> expectedComponents
  ) {
    Map<String, String> actualComponents = new LinkedHashMap<>();
    for (RecordComponent component : type.getRecordComponents()) {
      actualComponents.put(component.getName(), component.getType().getName());
    }
    SoftAssertions softly = new SoftAssertions();
    expectedComponents.forEach((name, componentType) -> softly.assertThat(actualComponents)
        .as(type.getSimpleName() + "." + name)
        .containsEntry(name, componentType));
    softly.assertAll();
  }

  private static Map.Entry<String, String> entry(String name, String type) {
    return Map.entry(name, type);
  }

  private record EnumContract(String enumName, String[] values) {
  }
}
