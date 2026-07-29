package com.fxplatform.trading.service;

import java.math.BigDecimal;
import java.util.Objects;

/** Explicit conservative pricing authority for one Linear Perpetual DEPTH risk projection. */
public final class PerpetualRiskPricing {

  private final BigDecimal marginAndFeePrice;
  private final BigDecimal adverseClosePrice;
  private final BigDecimal feeRate;
  private final Object planIdentity;

  public PerpetualRiskPricing(
      BigDecimal marginAndFeePrice,
      BigDecimal adverseClosePrice,
      BigDecimal feeRate
  ) {
    this(marginAndFeePrice, adverseClosePrice, feeRate, null);
  }

  private PerpetualRiskPricing(
      BigDecimal marginAndFeePrice,
      BigDecimal adverseClosePrice,
      BigDecimal feeRate,
      Object planIdentity
  ) {
    this.marginAndFeePrice = marginAndFeePrice;
    this.adverseClosePrice = adverseClosePrice;
    this.feeRate = feeRate;
    this.planIdentity = planIdentity;
  }

  static PerpetualRiskPricing boundToPlan(
      BigDecimal marginAndFeePrice,
      BigDecimal adverseClosePrice,
      BigDecimal feeRate,
      Object planIdentity,
      DepthOrderExecutionService.RiskPricingAuthority authority
  ) {
    Objects.requireNonNull(authority, "riskPricingAuthority");
    return new PerpetualRiskPricing(
        marginAndFeePrice,
        adverseClosePrice,
        feeRate,
        Objects.requireNonNull(planIdentity, "planIdentity"));
  }

  boolean hasSamePlanAuthority(PerpetualRiskPricing candidate) {
    return candidate != null
        && planIdentity != null
        && planIdentity == candidate.planIdentity
        && sameDecimal(marginAndFeePrice, candidate.marginAndFeePrice)
        && sameDecimal(adverseClosePrice, candidate.adverseClosePrice)
        && sameDecimal(feeRate, candidate.feeRate);
  }

  public BigDecimal marginAndFeePrice() {
    return marginAndFeePrice;
  }

  public BigDecimal adverseClosePrice() {
    return adverseClosePrice;
  }

  public BigDecimal feeRate() {
    return feeRate;
  }

  private static boolean sameDecimal(BigDecimal expected, BigDecimal candidate) {
    return expected == null
        ? candidate == null
        : candidate != null && expected.compareTo(candidate) == 0;
  }

  @Override
  public boolean equals(Object candidate) {
    if (this == candidate) {
      return true;
    }
    if (!(candidate instanceof PerpetualRiskPricing other)) {
      return false;
    }
    return Objects.equals(marginAndFeePrice, other.marginAndFeePrice)
        && Objects.equals(adverseClosePrice, other.adverseClosePrice)
        && Objects.equals(feeRate, other.feeRate);
  }

  @Override
  public int hashCode() {
    return Objects.hash(marginAndFeePrice, adverseClosePrice, feeRate);
  }

  @Override
  public String toString() {
    return "PerpetualRiskPricing[marginAndFeePrice=" + marginAndFeePrice
        + ", adverseClosePrice=" + adverseClosePrice
        + ", feeRate=" + feeRate + "]";
  }
}
