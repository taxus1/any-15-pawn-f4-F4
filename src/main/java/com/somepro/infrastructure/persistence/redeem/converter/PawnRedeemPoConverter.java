package com.somepro.infrastructure.persistence.redeem.converter;

import com.somepro.domain.redeem.model.PawnRedeem;
import com.somepro.infrastructure.persistence.redeem.po.PawnRedeemPO;

/**
 * PawnRedeemPO（表）↔ PawnRedeem（领域）转换器（基础设施层），PO 不外泄。
 * 各列都是普通日期/数字/字符串，直接映射，没有枚举列。
 */
public final class PawnRedeemPoConverter {

    private PawnRedeemPoConverter() {
    }

    public static PawnRedeemPO toPo(PawnRedeem domain) {
        PawnRedeemPO po = new PawnRedeemPO();
        po.setId(domain.getId());
        po.setRedeemNo(domain.getRedeemNo());
        po.setTicketId(domain.getTicketId());
        po.setRedeemedAt(domain.getRedeemedAt());
        po.setUsedDays(domain.getUsedDays());
        po.setFeeAmount(domain.getFeeAmount());
        po.setTotalAmount(domain.getTotalAmount());
        po.setDelFlag(domain.getDelFlag());
        po.setCreateBy(domain.getCreateBy());
        po.setCreateTime(domain.getCreateTime());
        po.setUpdateBy(domain.getUpdateBy());
        po.setUpdateTime(domain.getUpdateTime());
        return po;
    }

    public static PawnRedeem toDomain(PawnRedeemPO po) {
        PawnRedeem domain = new PawnRedeem();
        domain.setId(po.getId());
        domain.setRedeemNo(po.getRedeemNo());
        domain.setTicketId(po.getTicketId());
        domain.setRedeemedAt(po.getRedeemedAt());
        domain.setUsedDays(po.getUsedDays());
        domain.setFeeAmount(po.getFeeAmount());
        domain.setTotalAmount(po.getTotalAmount());
        domain.setDelFlag(po.getDelFlag());
        domain.setCreateBy(po.getCreateBy());
        domain.setCreateTime(po.getCreateTime());
        domain.setUpdateBy(po.getUpdateBy());
        domain.setUpdateTime(po.getUpdateTime());
        return domain;
    }
}
