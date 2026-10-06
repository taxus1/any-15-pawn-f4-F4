package com.somepro.interfaces.rest.redeem.converter;

import com.somepro.domain.redeem.model.PawnRedeem;
import com.somepro.domain.shared.model.PageResult;
import com.somepro.interfaces.rest.common.vo.PageVO;
import com.somepro.interfaces.rest.redeem.vo.PawnRedeemVO;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 赎当领域对象 → VO 转换器（用户接口层）。Controller 不直接把领域对象塞进 Result。
 */
public final class PawnRedeemVoConverter {

    private PawnRedeemVoConverter() {
    }

    public static PawnRedeemVO toVo(PawnRedeem domain) {
        return new PawnRedeemVO(
                domain.getId(),
                domain.getRedeemNo(),
                domain.getTicketId(),
                domain.getRedeemedAt(),
                domain.getUsedDays(),
                domain.getFeeAmount(),
                domain.getTotalAmount(),
                domain.getCreateTime());
    }

    public static PageVO<PawnRedeemVO> toPageVo(PageResult<PawnRedeem> page) {
        List<PawnRedeemVO> content = page.content().stream()
                .map(PawnRedeemVoConverter::toVo)
                .collect(Collectors.toList());
        return new PageVO<>(content, page.total(), page.pageNum(), page.pageSize(), page.totalPages());
    }
}
