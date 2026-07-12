package com.fxplatform.execution;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.enums.AccountType;
import com.fxplatform.account.enums.AccountStatus;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.exception.ErrorCode;
import com.fxplatform.common.market.SymbolNormalizer;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.market.repository.SymbolRepository;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class DemoExecutionGuard {

  private static final Map<String, ProductType> ALLOWED_PRODUCTS = Map.ofEntries(
      Map.entry("BTCUSDT", ProductType.CRYPTO_SPOT),
      Map.entry("ETHUSDT", ProductType.CRYPTO_SPOT),
      Map.entry("BNBUSDT", ProductType.CRYPTO_SPOT),
      Map.entry("SOLUSDT", ProductType.CRYPTO_SPOT),
      Map.entry("XRPUSDT", ProductType.CRYPTO_SPOT),
      Map.entry("BTCUSDT-PERP", ProductType.LINEAR_PERP),
      Map.entry("ETHUSDT-PERP", ProductType.LINEAR_PERP),
      Map.entry("BNBUSDT-PERP", ProductType.LINEAR_PERP),
      Map.entry("SOLUSDT-PERP", ProductType.LINEAR_PERP),
      Map.entry("XRPUSDT-PERP", ProductType.LINEAR_PERP));

  private final ExecutionProperties executionProperties;
  private final SymbolRepository symbolRepository;

  public DemoExecutionGuard(
      ExecutionProperties executionProperties,
      SymbolRepository symbolRepository
  ) {
    this.executionProperties = executionProperties;
    this.symbolRepository = symbolRepository;
  }

  public void requireDemo(
      TradingAccountEntity account,
      ProductType productType,
      String canonicalSymbol
  ) {
    requireDemoAccount(account);
    requireAllowedProduct(productType, canonicalSymbol);
  }

  public void requireDemoAccount(TradingAccountEntity account) {
    if (executionProperties.mode() != ExecutionMode.DEMO) {
      throw new BusinessException(
          ErrorCode.EXECUTION_DISABLED,
          "Trading writes require execution.mode=demo");
    }
    if (account == null || account.getAccountType() != AccountType.DEMO) {
      throw new BusinessException(
          ErrorCode.DEMO_ACCOUNT_REQUIRED,
          "Trading writes require a demo account");
    }
    if (account.getStatus() != AccountStatus.ACTIVE) {
      throw new BusinessException(
          ErrorCode.ACCOUNT_NOT_ACTIVE,
          "Trading writes require an active account");
    }
  }

  private void requireAllowedProduct(ProductType productType, String canonicalSymbol) {
    if (productType != ProductType.CRYPTO_SPOT && productType != ProductType.LINEAR_PERP) {
      throw new BusinessException(
          ErrorCode.PRODUCT_NOT_ALLOWED,
          "Product is not allowed for demo execution");
    }

    String symbol = canonical(canonicalSymbol);
    ProductType whitelistedProduct = ALLOWED_PRODUCTS.get(symbol);
    if (whitelistedProduct == null) {
      throw symbolNotAllowed();
    }

    SymbolEntity configuredSymbol = symbolRepository.findBySymbol(symbol)
        .orElseThrow(DemoExecutionGuard::symbolNotAllowed);
    if (!Boolean.TRUE.equals(configuredSymbol.getTradable())
        || configuredSymbol.getProductType() != whitelistedProduct
        || productType != whitelistedProduct) {
      throw symbolNotAllowed();
    }
  }

  private static String canonical(String symbol) {
    if (symbol == null || symbol.isBlank()) {
      return "";
    }
    return SymbolNormalizer.normalize(symbol.trim());
  }

  private static BusinessException symbolNotAllowed() {
    return new BusinessException(
        ErrorCode.SYMBOL_NOT_ALLOWED,
        "Symbol is not allowed for demo execution");
  }
}
