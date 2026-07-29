package com.fxplatform.execution;

public final class PropertyDemoExecutionPolicyProvider implements DemoExecutionPolicyProvider {

  private final DemoExecutionPolicy policy;

  public PropertyDemoExecutionPolicyProvider(ExecutionProperties properties) {
    ExecutionProperties.DemoProperties demo = properties.getDemo();
    this.policy = new DemoExecutionPolicy(
        demo.getMatchingMode(),
        demo.getMakerFeeRate(),
        demo.getTakerFeeRate(),
        demo.getLiquidationFeeRate(),
        demo.getSlippageRate(),
        demo.getBids(),
        demo.getAsks(),
        demo.getMaxFillQuantityPerTick());
  }

  @Override
  public DemoExecutionPolicy current() {
    return policy;
  }
}
