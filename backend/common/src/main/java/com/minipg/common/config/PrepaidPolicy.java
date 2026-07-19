package com.minipg.common.config;

import java.util.List;

/** 선불 소진순서 — 복합결제 시 이 순서로 매체를 차감한다 (주매체 머니 → 포인트). */
public final class PrepaidPolicy {

    /** 잠금·차감 소진순서. 앞에서부터 잔액을 소진하고 부족분을 다음 매체로 넘긴다. */
    public static final List<String> CONSUME_ORDER = List.of("MONEY", "POINT");

    private PrepaidPolicy() {
    }
}
