package com.somepro.interfaces.rest.redeem.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 办理赎当入参（用户接口层）。
 *
 * 用可变 bean + @ModelAttribute：WebFlux 下 application/x-www-form-urlencoded 表单、
 * query string 都能直接绑定。只认当票 id：费用按票面快照由服务端算、
 * 赎当单号由服务端生成、办理时刻取服务端当下，统统不接受外部指定。
 */
@Getter
@Setter
public class RedeemCreateRequest {

    /** 赎的是哪张当票（t_pawn_ticket.id）。 */
    private Long ticketId;
}
