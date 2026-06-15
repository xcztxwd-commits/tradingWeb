package com.fxplatform.audit.service;

import com.fxplatform.admin.dto.response.AdminVerificationCodeLogResponse;
import com.fxplatform.audit.entity.VerificationCodeLogEntity;
import com.fxplatform.audit.repository.VerificationCodeLogRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * VerificationCodeLogService 负责验证码发送记录写入和后台查询。
 */
@Service
@RequiredArgsConstructor
public class VerificationCodeLogService {

  private final VerificationCodeLogRepository verificationCodeLogRepository;

  /** 写入一条验证码发送记录。 */
  public AdminVerificationCodeLogResponse record(
      String scene,
      String account,
      String channel,
      String code,
      String status,
      String errorMessage
  ) {
    VerificationCodeLogEntity log = new VerificationCodeLogEntity();
    log.setScene(scene);
    log.setAccount(account);
    log.setChannel(channel);
    log.setCode(code);
    log.setStatus(status);
    log.setErrorMessage(errorMessage);
    return AdminVerificationCodeLogResponse.from(verificationCodeLogRepository.save(log));
  }

  /** 查询最近验证码发送记录。 */
  public List<AdminVerificationCodeLogResponse> recent(int size) {
    return verificationCodeLogRepository.findRecent(size).stream()
        .map(AdminVerificationCodeLogResponse::from)
        .toList();
  }
}
