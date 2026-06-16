package com.fxplatform.market.service;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.model.ProductType;
import java.util.Locale;

public final class SymbolProductTypes {

  public static final String REQUIRED_CODE = "SYMBOL_PRODUCT_TYPE_REQUIRED";
  public static final String CONFLICT_CODE = "SYMBOL_PRODUCT_TYPE_ASSET_CLASS_CONFLICT";

  private SymbolProductTypes() {
  }

  public static ProductType requireExplicit(ProductType productType) {
    if (productType == null) {
      throw new BusinessException(REQUIRED_CODE, "Symbol product type is required");
    }
    return productType;
  }

  public static SymbolEntity requireExplicit(SymbolEntity symbol) {
    requireExplicit(symbol.getProductType());
    return symbol;
  }

  public static ProductType readOrLegacy(SymbolEntity symbol) {
    ProductType productType = symbol.getProductType();
    return productType == null ? legacyFromAssetClass(symbol.getAssetClass()) : productType;
  }

  public static ProductType legacyFromAssetClass(String assetClass) {
    return switch (normalize(assetClass)) {
      case "SPOT", "CRYPTO" -> ProductType.CRYPTO_SPOT;
      case "LINEAR_PERP", "LINEAR_PERPETUAL", "PERPETUAL", "SWAP", "FUTURES", "CONTRACT" -> ProductType.LINEAR_PERP;
      case "INVERSE_PERP", "INVERSE_PERPETUAL" -> ProductType.INVERSE_PERP;
      default -> ProductType.FX_MARGIN;
    };
  }

  public static void validateAssetClassCompatibility(ProductType productType, String assetClass) {
    ProductType expected = legacyFromAssetClass(assetClass);
    if (productType != expected) {
      throw new BusinessException(CONFLICT_CODE, "Symbol product type conflicts with asset class");
    }
  }

  private static String normalize(String value) {
    return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
  }
}
