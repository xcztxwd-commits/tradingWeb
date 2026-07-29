package com.fxplatform.tradinglab.client;

import java.util.UUID;

/** Main-control-plane port for the isolated validation backend. */
public interface ValidationBackendClient {

  ValidationBackendExchange<ValidationResetReceipt> reset(ValidationResetRequest request);

  ValidationBackendExchange<ValidationRunAccepted> startRun(ValidationRunStartRequest request);

  ValidationBackendExchange<ValidationRunStateObservation> state(UUID runId);

  ValidationBackendExchange<ValidationEventPage> eventsAfter(UUID runId, long afterSequence);

  ValidationBackendExchange<ValidationControlReceipt> pause(UUID runId);

  ValidationBackendExchange<ValidationControlReceipt> resume(UUID runId);

  ValidationBackendExchange<ValidationControlReceipt> cancel(UUID runId);
}
