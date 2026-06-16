package com.fxplatform.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fxplatform.auth.enums.KycStatus;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * KYC 申请资料实体。
 */
@Getter
@Setter
@NoArgsConstructor
@TableName("auth.kyc_applications")
public class KycApplicationEntity {

  @TableId(value = "id", type = IdType.INPUT)
  private UUID id;
  private UUID userId;
  private String realName;
  private String documentType;
  private String documentNo;
  private String frontImageUrl;
  private String backImageUrl;
  private KycStatus status = KycStatus.PENDING;
  private String reviewReason;
  private UUID reviewedBy;
  private Instant reviewedAt;
  @TableField(fill = FieldFill.INSERT)
  private Instant createdAt;
  @TableField(fill = FieldFill.INSERT_UPDATE)
  private Instant updatedAt;

  public void setStatus(KycStatus status) {
    this.status = status;
  }

  public void setStatus(String status) {
    this.status = KycStatus.fromReviewCode(status);
  }
}
