package com.fxplatform.market.funding;

public enum FundingSource {
  BINANCE("binance-usdm"),
  OKX("okx-swap"),
  FIXED("fixed");

  private final String providerCode;

  FundingSource(String providerCode) {
    this.providerCode = providerCode;
  }

  public String providerCode() {
    return providerCode;
  }
}
