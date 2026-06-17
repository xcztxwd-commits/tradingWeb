package com.fxplatform.execution;

public interface ExecutionAdapterReadiness {

  ExecutionMode mode();

  boolean readyForLiveTrading();

  String notReadyMessage();
}
