package com.fxplatform.engagement.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@TableName("content.message_targets")
public class MessageTargetEntity {

  private UUID publicationId;
  private UUID userId;
}
