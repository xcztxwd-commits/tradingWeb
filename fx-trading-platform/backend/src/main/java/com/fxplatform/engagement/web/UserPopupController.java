package com.fxplatform.engagement.web;

import com.fxplatform.common.response.ApiResponse;
import com.fxplatform.common.security.UserPrincipal;
import com.fxplatform.engagement.application.popup.PopupClaimService;
import com.fxplatform.engagement.application.popup.PopupClaimService.PopupClaim;
import com.fxplatform.engagement.application.popup.PopupClaimService.PopupSurface;
import com.fxplatform.engagement.application.popup.PopupOutcomeService;
import com.fxplatform.engagement.application.popup.PopupOutcomeService.PopupOutcomeResult;
import com.fxplatform.engagement.domain.popup.PopupOutcome;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/me/engagement")
@PreAuthorize("hasRole('USER')")
@RequiredArgsConstructor
public class UserPopupController {

  private final PopupClaimService claimService;
  private final PopupOutcomeService outcomeService;

  @PostMapping("/popup-queues")
  public ApiResponse<PopupClaim> startQueue(
      @AuthenticationPrincipal UserPrincipal principal,
      @RequestBody PopupSurface surface) {
    return ApiResponse.success(
        claimService.claimNextPopup(principal.id(), surface, null).orElse(null));
  }

  @PostMapping("/popup-queues/{sessionId}/next")
  public ApiResponse<PopupClaim> next(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable("sessionId") UUID sessionId,
      @RequestBody PopupSurface surface) {
    return ApiResponse.success(
        claimService.claimNextPopup(principal.id(), surface, sessionId).orElse(null));
  }

  @PostMapping("/popup-deliveries/{deliveryToken}/shown")
  public ApiResponse<PopupOutcomeResult> shown(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable("deliveryToken") String deliveryToken) {
    return outcome(principal, deliveryToken, PopupOutcome.SHOWN);
  }

  @PostMapping("/popup-deliveries/{deliveryToken}/close")
  public ApiResponse<PopupOutcomeResult> close(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable("deliveryToken") String deliveryToken) {
    return outcome(principal, deliveryToken, PopupOutcome.CLOSE);
  }

  @PostMapping("/popup-deliveries/{deliveryToken}/opt-out")
  public ApiResponse<PopupOutcomeResult> optOut(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable("deliveryToken") String deliveryToken) {
    return outcome(principal, deliveryToken, PopupOutcome.OPT_OUT);
  }

  @PostMapping("/popup-deliveries/{deliveryToken}/click")
  public ApiResponse<PopupOutcomeResult> click(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable("deliveryToken") String deliveryToken) {
    return outcome(principal, deliveryToken, PopupOutcome.CTA_CLICK);
  }

  private ApiResponse<PopupOutcomeResult> outcome(
      UserPrincipal principal,
      String deliveryToken,
      PopupOutcome outcome) {
    return ApiResponse.success(
        outcomeService.recordPopupOutcome(principal.id(), deliveryToken, outcome));
  }
}
