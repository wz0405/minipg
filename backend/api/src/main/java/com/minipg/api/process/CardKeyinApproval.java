package com.minipg.api.process;

import org.springframework.stereotype.Service;

/** 수기(키인) 승인 — 카드 원본은 어댑터에서 제휴사로 넘긴 뒤 버려지고 마스킹본만 남는다. */
@Service("process|KEYIN")
public class CardKeyinApproval extends CardApprovalBase {

    @Override
    protected String payType() {
        return "KEYIN";
    }
}
