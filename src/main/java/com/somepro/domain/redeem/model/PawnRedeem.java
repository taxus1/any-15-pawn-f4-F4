package com.somepro.domain.redeem.model;

import com.somepro.common.exception.BizException;
import com.somepro.domain.shared.model.BaseEntity;
import com.somepro.domain.ticket.model.PawnTicket;
import com.somepro.domain.ticket.model.TicketStatus;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * 赎当结算聚合根（纯领域对象，不带任何持久化注解）。
 *
 * 一条记录 = 一次赎当结清。核心不变量：
 * 1. 只有在当（ACTIVE）的当票才赎得了；已赎回 / 已绝当 / 已撤销都是定了案的历史票，不收；
 * 2. 费用照票面上的月利率、月综合费率快照算 —— 那是开票当时抄下来定格的，
 *    赎回时不读现在的费率配置，老票的账不跟着新配置乱跳；
 * 3. 计费按天走：日费率 =（月利率 + 月综合费率）÷ 30，费用 = 当金 × 日费率 × 计费天数，
 *    计费天数 = 赎当日期 − 起当日期的自然日数，不足一天按一天算（至少 1 天）；
 * 4. 应还总额 = 当金 + 费用；费用与总额都保留两位小数、四舍五入；
 * 5. 晚于到期日期来赎照收，费用按实际天数算，不额外加罚；
 * 6. 同一张票只赎一回 —— 这是跨聚合（赎当结算 + 当票状态 + 当物状态）的并发约束，
 *    由仓储在写锁内对当票做「仍在当」的条件更新来保证，本聚合只管单笔自身的规则。
 *
 * 赎当单号 redeemNo（SD-2026-0001 样式）由仓储按当年序号生成，全局唯一、一单一号。
 * 赎回办成后当票由在当转已赎、当物由已典当转已赎回，两处状态在仓储同一事务里一起翻。
 */
@Getter
@Setter
public class PawnRedeem extends BaseEntity {

    /** 月费率换日费率的天数除数：日费率 =（月利率 + 月综合费率）÷ 30。 */
    private static final BigDecimal DAYS_PER_MONTH = BigDecimal.valueOf(30);

    private Long id;

    /** 赎当单号，如 SD-2026-0001；办理时由仓储生成，业务上不可改。 */
    private String redeemNo;

    /** 赎的是哪张当票（t_pawn_ticket.id）。 */
    private Long ticketId;

    /** 赎当办理时刻，由应用层按行里时区补当下时刻，原样落账。 */
    private LocalDateTime redeemedAt;

    /** 计费天数：赎当日期 − 起当日期的自然日数，不足一天按一天算（至少 1）。 */
    private Integer usedDays;

    /** 利息与综合费合计（元）= 当金 × 日费率 × 计费天数，两位小数四舍五入。 */
    private BigDecimal feeAmount;

    /** 应还总额（元）= 当金 + 费用，两位小数四舍五入。 */
    private BigDecimal totalAmount;

    /**
     * 工厂方法：办理一次赎当，把该收的本息一次算清。
     *
     * @param ticket     办理当下从库里读出的当票（状态、当金、利率费率快照、起当日期以它为准）
     * @param redeemedAt 赎当办理时刻（行里时区，由应用层补服务端当下时间，不接受前端指定）
     */
    public static PawnRedeem apply(PawnTicket ticket, LocalDateTime redeemedAt) {
        if (ticket == null || ticket.getId() == null) {
            throw new BizException("必须指定赎的是哪张当票");
        }
        if (ticket.getStatus() != TicketStatus.ACTIVE) {
            throw new BizException("只有在当的当票才能赎当，当前状态："
                    + (ticket.getStatus() == null ? "-" : ticket.getStatus().label()));
        }
        BigDecimal pawnAmount = ticket.getPawnAmount();
        if (pawnAmount == null || pawnAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("当票当金异常，无法结算赎当");
        }
        // 利率费率只认票面上的快照，不读现在的配置：老票照老账算
        BigDecimal monthlyRate = ticket.getMonthlyRate();
        BigDecimal serviceRate = ticket.getServiceRate();
        if (monthlyRate == null || serviceRate == null) {
            throw new BizException("当票利率费率快照缺失，无法结算赎当");
        }
        LocalDate startDate = ticket.getStartDate();
        if (startDate == null) {
            throw new BizException("当票起当日期缺失，无法结算赎当");
        }
        if (redeemedAt == null) {
            throw new BizException("赎当办理时刻缺失，不能赎当");
        }

        // 计费天数 = 赎当日期 − 起当日期的自然日数；当天来赎不足一天，按一天算。
        // 晚于到期日期来赎也照这个算法走，按实际天数收，不额外加罚。
        long days = ChronoUnit.DAYS.between(startDate, redeemedAt.toLocalDate());
        int usedDays = (int) Math.max(1, days);

        // 费用 = 当金 ×（月利率 + 月综合费率）÷ 30 × 计费天数。
        // 不在中间步骤截断日费率（除不尽时先截断会差出分位），算到底再保留两位小数四舍五入。
        BigDecimal fee = pawnAmount
                .multiply(monthlyRate.add(serviceRate))
                .multiply(BigDecimal.valueOf(usedDays))
                .divide(DAYS_PER_MONTH, 2, RoundingMode.HALF_UP);
        BigDecimal total = pawnAmount.add(fee).setScale(2, RoundingMode.HALF_UP);

        PawnRedeem redeem = new PawnRedeem();
        redeem.ticketId = ticket.getId();
        redeem.redeemedAt = redeemedAt;
        redeem.usedDays = usedDays;
        redeem.feeAmount = fee;
        redeem.totalAmount = total;
        return redeem;
    }
}
