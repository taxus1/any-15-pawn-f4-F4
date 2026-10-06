package com.somepro.application.redeem;

import com.somepro.common.exception.BizException;
import com.somepro.domain.redeem.model.PawnRedeem;
import com.somepro.domain.redeem.repository.PawnRedeemRepository;
import com.somepro.domain.shared.model.PageResult;
import com.somepro.domain.ticket.repository.PawnTicketRepository;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 赎当应用服务：编排办理赎当、查看赎当、按当票翻赎当记录三个用例，不写表映射。
 *
 * 出入参用领域对象/基础类型，不认识 PO 与 VO。
 *
 * 办理这条链在这里收口：认票（当票仓储读出最新票面）→ 聚合卡状态、按票面利率费率快照
 * 把计费天数、费用、应还总额一次算清 → 仓储在写锁内条件结清票、联动当物状态、
 * 生成赎当单号、同事务落赎当结算。赎当单号唯一与「同票只赎一回」的并发约束在仓储里；
 * 单笔自身规则在 PawnRedeem 聚合里。
 */
@Service
public class PawnRedeemAppService {

    /** 业务时刻统一按行里所在时区算，避免容器 UTC 下把计费天数算偏一天。 */
    private static final ZoneId BIZ_ZONE = ZoneId.of("Asia/Shanghai");

    private final PawnRedeemRepository pawnRedeemRepository;
    private final PawnTicketRepository pawnTicketRepository;

    public PawnRedeemAppService(PawnRedeemRepository pawnRedeemRepository,
                                PawnTicketRepository pawnTicketRepository) {
        this.pawnRedeemRepository = pawnRedeemRepository;
        this.pawnTicketRepository = pawnTicketRepository;
    }

    /**
     * 办理赎当：只认当票 id；费用照票面利率费率快照按实际天数算，晚于到期日期来赎也照收、
     * 不加罚。只有在当的票赎得了；同一时点重复递交只成一次（仓储写锁内条件更新兜底）。
     * 赎当单号由仓储按 SD-年份-序号 生成；办成后票转已赎、当物转已赎回，同一事务落库。
     */
    public Mono<PawnRedeem> redeem(Long ticketId) {
        if (ticketId == null) {
            return Mono.error(new BizException("必须指定赎的是哪张当票"));
        }
        // 办理时刻以服务端行里时区为准，不接受前端指定
        LocalDateTime redeemedAt = LocalDateTime.now(BIZ_ZONE);
        return pawnTicketRepository.findById(ticketId)
                .switchIfEmpty(Mono.error(new BizException("当票不存在")))
                .flatMap(ticket -> pawnRedeemRepository.insert(PawnRedeem.apply(ticket, redeemedAt)));
    }

    /** 查看赎当单：id 或 redeemNo（SD-编号）任一指定。 */
    public Mono<PawnRedeem> detail(Long id, String redeemNo) {
        if (id != null) {
            return pawnRedeemRepository.findById(id)
                    .switchIfEmpty(Mono.error(new BizException("赎当单不存在")));
        }
        if (redeemNo != null && !redeemNo.isBlank()) {
            return pawnRedeemRepository.findByRedeemNo(redeemNo.trim())
                    .switchIfEmpty(Mono.error(new BizException("赎当单不存在")));
        }
        return Mono.error(new BizException("请指定要查看的赎当单（id 或 redeemNo）"));
    }

    /** 按当票翻赎当记录：必须指定当票，一页一页走，每行带赎当单号，稳定按办理次序排列。 */
    public Mono<PageResult<PawnRedeem>> pageByTicket(int pageNum, int pageSize, Long ticketId) {
        if (pageNum < 1 || pageSize < 1) {
            return Mono.error(new BizException("页码与每页条数必须为正整数"));
        }
        if (ticketId == null) {
            return Mono.error(new BizException("必须指定按哪张当票翻赎当记录"));
        }
        return pawnRedeemRepository.pageByTicket(pageNum, pageSize, ticketId);
    }
}
