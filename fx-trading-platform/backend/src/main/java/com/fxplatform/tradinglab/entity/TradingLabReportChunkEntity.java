package com.fxplatform.tradinglab.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@TableName("trading_lab.report_chunks")
public class TradingLabReportChunkEntity {

  @TableId(value = "id", type = IdType.INPUT)
  private UUID id;
  private UUID reportId;
  private String section;
  private Long sequence;
  private String encoding;
  private Long uncompressedBytes;
  private Long compressedBytes;
  private byte[] payload;
  private String checksum;

  @TableField(fill = FieldFill.INSERT)
  private Instant createdAt;
}
