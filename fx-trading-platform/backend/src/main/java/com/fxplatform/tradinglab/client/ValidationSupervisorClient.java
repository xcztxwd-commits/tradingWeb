package com.fxplatform.tradinglab.client;

/**
 * Typed seam to the fixed host-side Validation Supervisor.
 *
 * <p>The interface deliberately has no generic action, URI, command, path, or service selector.</p>
 */
public interface ValidationSupervisorClient {

  ValidationSupervisorStatus status();

  ValidationSupervisorHealth health();

  ValidationSupervisorActionResult start();

  ValidationSupervisorActionResult stop();

  ValidationSupervisorActionResult restart();
}
