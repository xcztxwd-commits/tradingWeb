package com.fxplatform.home.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@TableName("core.home_promo_cards")
public class HomePromoCardEntity {

  @TableId(value = "slot", type = IdType.INPUT)
  private String slot;
  private String frontRank;
  private String frontLabel;
  private String backTitle;
  private String backValue;
  private Boolean enabled = true;
  private Integer displayOrder = 0;

  @TableField(fill = FieldFill.INSERT)
  private Instant createdAt;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private Instant updatedAt;
}
