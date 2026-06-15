package com.fxplatform.audit.service;

import com.fxplatform.admin.dto.response.AdminRequestLogResponse;
import com.fxplatform.audit.entity.RequestLogEntity;
import com.fxplatform.audit.repository.RequestLogRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RequestLogService {

  private final RequestLogRepository requestLogRepository;

  public AdminRequestLogResponse record(
      String requestId,
      String method,
      String path,
      String clientIp,
      String userAgent,
      Integer statusCode,
      Long durationMs,
      String errorMessage
  ) {
    RequestLogEntity log = new RequestLogEntity();
    log.setRequestId(requestId);
    log.setMethod(method);
    log.setPath(path);
    log.setClientIp(clientIp);
    log.setUserAgent(userAgent);
    log.setStatusCode(statusCode);
    log.setDurationMs(durationMs);
    log.setErrorMessage(errorMessage);
    return AdminRequestLogResponse.from(requestLogRepository.save(log));
  }

  public List<AdminRequestLogResponse> recent(int size) {
    return requestLogRepository.findRecent(size).stream()
        .map(AdminRequestLogResponse::from)
        .toList();
  }
}
