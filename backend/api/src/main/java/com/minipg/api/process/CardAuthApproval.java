package com.minipg.api.process;

import org.springframework.stereotype.Service;

/** 결제창 인증 완료 건 승인. */
@Service("process|APPROVE")
public class CardAuthApproval extends CardApprovalBase {

    @Override
    protected String payType() {
        return "AUTH";
    }
}
