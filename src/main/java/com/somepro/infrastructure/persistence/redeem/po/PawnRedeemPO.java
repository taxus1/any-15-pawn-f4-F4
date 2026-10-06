package com.somepro.infrastructure.persistence.redeem.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.somepro.infrastructure.persistence.base.BasePO;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * t_pawn_redeem 表的持久化对象（PO，基础设施层）。只描述表结构，不放业务规则。
 *
 * 表已由 doc/schema/pawn.sql 建好，列名即契约，本类不做任何建表/改表动作。
 * used_days / fee_amount / total_amount 都是办理当下算定定格的账，落库后不回写。
 */
@Getter
@Setter
@TableName("t_pawn_redeem")
public class PawnRedeemPO extends BasePO {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    @TableField("redeem_no")
    private String redeemNo;

    @TableField("ticket_id")
    private Long ticketId;

    @TableField("redeemed_at")
    private LocalDateTime redeemedAt;

    @TableField("used_days")
    private Integer usedDays;

    @TableField("fee_amount")
    private BigDecimal feeAmount;

    @TableField("total_amount")
    private BigDecimal totalAmount;
}
