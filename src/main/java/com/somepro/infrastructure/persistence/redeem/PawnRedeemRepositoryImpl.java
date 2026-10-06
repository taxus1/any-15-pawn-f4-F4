package com.somepro.infrastructure.persistence.redeem;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.pagehelper.PageHelper;
import com.somepro.common.exception.BizException;
import com.somepro.domain.collateral.model.CollateralStatus;
import com.somepro.domain.redeem.model.PawnRedeem;
import com.somepro.domain.redeem.repository.PawnRedeemRepository;
import com.somepro.domain.shared.model.PageResult;
import com.somepro.domain.ticket.model.TicketStatus;
import com.somepro.infrastructure.config.ReactiveOperatorContext;
import com.somepro.infrastructure.persistence.audit.AuditContextHolder;
import com.somepro.infrastructure.persistence.redeem.converter.PawnRedeemPoConverter;
import com.somepro.infrastructure.persistence.redeem.po.PawnRedeemPO;
import com.somepro.infrastructure.persistence.ticket.PawnTicketMapper;
import com.somepro.infrastructure.persistence.ticket.TicketCollateralMapper;
import com.somepro.infrastructure.persistence.ticket.po.PawnTicketPO;
import com.somepro.infrastructure.persistence.ticket.po.TicketCollateralPO;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 赎当仓储适配器（基础设施层）：MyBatis-Plus 阻塞 JDBC 经 blocking(...) 桥接进响应式链路。
 *
 * 本类三处关键业务语义：
 *
 * 1. 同一张票只赎一回（含并发重复递交）
 *    办理先抢 MySQL 命名锁 GET_LOCK('pawn_redeem:write')（全实例互斥），锁内事务里先对当票做
 *    条件更新：id 命中、状态仍是 ACTIVE 才置 REDEEMED。柜台手快重复递交同一笔，第二笔拿到锁时
 *    票面已是已赎，条件不成立、更新 0 行，整段回滚 —— 赎当记录只落一条，票也只结清一次。
 *    票在办理瞬间被撤销/绝当（状态离开 ACTIVE）同样挡回。
 *
 * 2. 赎当单号生成 SD-yyyy-NNNN
 *    同一把写锁内：取当年赎当单号的最大整数序号 +1（序号在 Java 侧解析，
 *    避免字符串排序把 9999 排在 10000 前），锁内算号天然不撞；
 *    取号刻意包含已删除的赎当：单号一经分配永久占用。
 *    uk_redeem_no 唯一索引是最后防线，极端瞬态冲突整段重试，不甩底层错给柜台。
 *
 * 3. 票、物、结算同一事务
 *    当票（在当 → 已赎）、当物（已典当 → 已赎回）、赎当结算写入在同一事务里落库，
 *    两处状态一起翻，要么一起成、要么一起回滚，不会出现「票已赎、物还押着」的裂账。
 *
 * 锁的连接与时序同当票/续当模块：用一条【独立于事务的原始连接】在事务开启前 GET_LOCK、
 * 在事务【提交之后】才 RELEASE_LOCK，避免「锁已放、事务未提交」导致后到者漏看刚结清的票。
 */
@Repository
public class PawnRedeemRepositoryImpl implements PawnRedeemRepository {

    /** 业务日期统一按行里所在时区算，避免容器 UTC 下单号跨年。 */
    private static final ZoneId BIZ_ZONE = ZoneId.of("Asia/Shanghai");
    /** 赎当办理临界区命名锁（MySQL 全实例同名互斥）。 */
    private static final String WRITE_LOCK = "pawn_redeem:write";
    private static final int LOCK_WAIT_SECONDS = 10;
    private static final int MAX_RETRY = 5;

    private final PawnRedeemMapper pawnRedeemMapper;
    private final PawnTicketMapper pawnTicketMapper;
    private final TicketCollateralMapper ticketCollateralMapper;
    private final TransactionTemplate transactionTemplate;
    private final DataSource dataSource;

    public PawnRedeemRepositoryImpl(PawnRedeemMapper pawnRedeemMapper,
                                    PawnTicketMapper pawnTicketMapper,
                                    TicketCollateralMapper ticketCollateralMapper,
                                    PlatformTransactionManager transactionManager,
                                    DataSource dataSource) {
        this.pawnRedeemMapper = pawnRedeemMapper;
        this.pawnTicketMapper = pawnTicketMapper;
        this.ticketCollateralMapper = ticketCollateralMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.dataSource = dataSource;
    }

    @Override
    public Mono<PawnRedeem> insert(PawnRedeem redeem) {
        return blocking(() -> {
            // 每轮重试用独立连接重新抢锁；兜住单号撞号 / 锁等待超时等瞬态冲突
            for (int attempt = 0; attempt < MAX_RETRY; attempt++) {
                try {
                    return inWriteLock(() -> transactionTemplate.execute(status -> {
                        // 锁内读票面拿当物 id：当物挂在哪张票上一经开票不改，锁内读的是最新定论
                        PawnTicketPO ticket = pawnTicketMapper.selectById(redeem.getTicketId());
                        if (ticket == null) {
                            throw new BizException("当票不存在");
                        }
                        // 同一张票只赎一回：条件更新「仍在当」才置已赎。
                        // 重复递交的第二笔状态已不是 ACTIVE，更新 0 行，整段回滚不落记录。
                        PawnTicketPO ticketUpdate = new PawnTicketPO();
                        ticketUpdate.setStatus(TicketStatus.REDEEMED.code());
                        int rows = pawnTicketMapper.update(ticketUpdate,
                                Wrappers.<PawnTicketPO>lambdaUpdate()
                                        .eq(PawnTicketPO::getId, redeem.getTicketId())
                                        .eq(PawnTicketPO::getStatus, TicketStatus.ACTIVE.code()));
                        if (rows == 0) {
                            throw new BizException("当票状态已变化，本次赎当未生效；请刷新后按最新票面办理");
                        }
                        // 票物联动：票赎了，押的当物跟着从已典当转已赎回（只翻「已典当」的，
                        // 别踩了别的流程置的状态），与票、结算同一事务落库
                        markCollateralRedeemed(ticket.getCollateralId());
                        PawnRedeemPO po = PawnRedeemPoConverter.toPo(redeem);
                        po.setId(IdUtil.getSnowflakeNextId());
                        po.setRedeemNo(nextRedeemNo());
                        pawnRedeemMapper.insert(po);
                        return PawnRedeemPoConverter.toDomain(po);
                    }));
                } catch (DuplicateKeyException | TransientDataAccessException e) {
                    // uk_redeem_no 是最后防线，锁内正常不会撞；撞了整段重新取号重试
                    if (attempt == MAX_RETRY - 1) {
                        throw new BizException("系统繁忙，请稍后重试");
                    }
                    try {
                        Thread.sleep(10L * (attempt + 1));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new BizException("系统繁忙，请稍后重试");
                    }
                }
            }
            throw new BizException("系统繁忙，请稍后重试");
        });
    }

    @Override
    public Mono<PawnRedeem> findById(Long id) {
        return blocking(() -> {
            PawnRedeemPO po = pawnRedeemMapper.selectById(id);
            return po == null ? null : PawnRedeemPoConverter.toDomain(po);
        });
    }

    @Override
    public Mono<PawnRedeem> findByRedeemNo(String redeemNo) {
        return blocking(() -> {
            PawnRedeemPO po = pawnRedeemMapper.selectOne(
                    Wrappers.<PawnRedeemPO>lambdaQuery().eq(PawnRedeemPO::getRedeemNo, redeemNo));
            return po == null ? null : PawnRedeemPoConverter.toDomain(po);
        });
    }

    @Override
    public Mono<PageResult<PawnRedeem>> pageByTicket(int pageNum, int pageSize, Long ticketId) {
        return this.<PageResult<PawnRedeem>>blocking(() -> {
            try {
                PageHelper.startPage(pageNum, pageSize);
                LambdaQueryWrapper<PawnRedeemPO> wrapper = Wrappers.<PawnRedeemPO>lambdaQuery()
                        .eq(PawnRedeemPO::getTicketId, ticketId)
                        // 稳定排序：一页页往后翻不会重复、不会跳条，赎当先后也对得上办理次序
                        .orderByAsc(PawnRedeemPO::getId);
                List<PawnRedeemPO> rows = pawnRedeemMapper.selectList(wrapper);
                long total = rows instanceof com.github.pagehelper.Page
                        ? ((com.github.pagehelper.Page<?>) rows).getTotal()
                        : rows.size();
                List<PawnRedeem> content = rows.stream()
                        .map(PawnRedeemPoConverter::toDomain)
                        .collect(Collectors.toList());
                return new PageResult<>(content, total, pageNum, pageSize);
            } finally {
                // PageHelper 靠 ThreadLocal 传分页参数，必须清，避免污染线程池下一次调用
                PageHelper.clearPage();
            }
        });
    }

    /**
     * 生成 SD-年份-序号：序号是当年已有赎当单号（含已删除）最大整数 +1，至少 4 位、超出自然进位。
     * 只在写锁（{@link #inWriteLock}）内调用，锁内串行所以不会撞号；
     * redeem_no 唯一索引是最后防线，极端瞬态冲突由外层整段重试兜底。
     */
    private String nextRedeemNo() {
        int year = LocalDate.now(BIZ_ZONE).getYear();
        String prefix = "SD-" + year + "-";
        long maxSeq = 0L;
        for (String no : pawnRedeemMapper.findRedeemNosByPrefix(prefix + "%")) {
            if (no == null || !no.startsWith(prefix)) {
                continue;
            }
            String tail = no.substring(prefix.length());
            if (tail.chars().allMatch(Character::isDigit)) {
                maxSeq = Math.max(maxSeq, Long.parseLong(tail));
            }
        }
        return prefix + String.format("%04d", maxSeq + 1);
    }

    /**
     * 当物状态联动：已典当 → 已赎回，只翻当前状态是「已典当」的行（别踩了别的流程置的状态）。
     * 联动行数不符即状态已被人动过，抛业务异常让整段事务回滚，票、物、结算都不落。
     */
    private void markCollateralRedeemed(Long collateralId) {
        TicketCollateralPO update = new TicketCollateralPO();
        update.setStatus(CollateralStatus.REDEEMED.code());
        int rows = ticketCollateralMapper.update(update, Wrappers.<TicketCollateralPO>lambdaUpdate()
                .eq(TicketCollateralPO::getId, collateralId)
                .eq(TicketCollateralPO::getStatus, CollateralStatus.PAWNED.code()));
        if (rows == 0) {
            throw new BizException("当物状态已变化，本次办理未生效；请刷新后按最新状态办理");
        }
    }

    /**
     * 在全局命名锁保护下执行一段【含事务】的写入：锁由一条独立原始连接持有，
     * 在事务开始前 GET_LOCK、在事务提交/回滚之后才 RELEASE_LOCK（顺序不能颠倒）。
     *
     * 为什么锁要走独立连接而不是 MyBatis 连接：GET_LOCK 绑定连接；
     * 若用事务所在连接，Spring 提交时归还连接会立刻放锁，存在「锁已放、事务未提交」的窗口，
     * 后到的事务取号/点在当票时读不到刚提交的数据，会算出重复号、漏看刚结清的票。
     * 独立连接持锁可把锁保到提交之后。
     */
    private <T> T inWriteLock(Supplier<T> action) {
        Connection lockConn;
        try {
            lockConn = dataSource.getConnection();
        } catch (SQLException e) {
            throw new BizException("系统繁忙，请稍后重试");
        }
        try {
            if (!namedLock(lockConn, true)) {
                throw new BizException("系统繁忙，请稍后重试");
            }
            try {
                return action.get();
            } finally {
                // 此时 action 内的事务已提交（或回滚），放锁后后到者必能看到本次写入
                namedLock(lockConn, false);
            }
        } finally {
            try {
                lockConn.close();
            } catch (SQLException ignored) {
                // 连接关闭会自动释放其上的命名锁，不影响主流程
            }
        }
    }

    /** GET_LOCK / RELEASE_LOCK；返回 MySQL 结果（1 成功）。 */
    private boolean namedLock(Connection conn, boolean get) {
        String sql = get ? "SELECT GET_LOCK(?, ?)" : "SELECT RELEASE_LOCK(?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, WRITE_LOCK);
            if (get) {
                ps.setInt(2, LOCK_WAIT_SECONDS);
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    int r = rs.getInt(1);
                    return !rs.wasNull() && r == 1;
                }
                return false;
            }
        } catch (SQLException e) {
            if (get) {
                throw new BizException("系统繁忙，请稍后重试");
            }
            return false;
        }
    }

    /**
     * 阻塞 DB 调用 → 响应式链路桥接器：先从 Reactor Context 取操作人，再切到 boundedElastic，
     * 操作人放进 AuditContextHolder 供审计填充（与当户/当票/续当模块同一套约定，顺序不能颠倒）。
     */
    private <T> Mono<T> blocking(Supplier<T> supplier) {
        return Mono.deferContextual(ctx -> {
            String operator = ReactiveOperatorContext.getOperator(ctx);
            return Mono.fromCallable(() -> {
                AuditContextHolder.setOperator(operator);
                try {
                    return supplier.get();
                } finally {
                    AuditContextHolder.clear();
                }
            }).subscribeOn(Schedulers.boundedElastic());
        });
    }
}
