package com.somepro.interfaces.rest.redeem.vo;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 赎当单对外对象（不可变 record）：办理/查看/翻记录共用。
 *
 * 每行都带 redeemNo（SD-年份-序号），方便柜台跟赎当凭证对号；
 * usedDays/feeAmount/totalAmount 是办理当下算定定格的账，原样外放。
 * 刻意不暴露 delFlag / createBy / updateBy 等内部字段。
 */
public record PawnRedeemVO(Long id,
                           String redeemNo,
                           Long ticketId,
                           LocalDateTime redeemedAt,
                           Integer usedDays,
                           BigDecimal feeAmount,
                           BigDecimal totalAmount,
                           LocalDateTime createTime) implements Serializable {
}
