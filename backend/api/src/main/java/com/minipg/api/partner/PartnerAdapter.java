package com.minipg.api.partner;

import com.minipg.api.flow.FlowStop;
import com.minipg.api.flow.PayContext;
import com.minipg.common.domain.TrMstr;

/**
 * 제휴사 어댑터 계약 — 새 제휴사 연동은 이 인터페이스 구현 클래스 하나를
 * "partner|제휴사코드" 별명으로 등록하면 끝난다. 전문 스펙·서명·암호화 방식은
 * 전부 어댑터 안에 캡슐화하고, 프로세스는 계약만 본다.
 */
public interface PartnerAdapter {

    /** 승인 — 결제유형별 요청 구성은 어댑터가 문맥(work.payType 등)을 보고 스스로 한다. */
    PartnerResult approve(PayContext ctx);

    /** 원거래 취소. */
    PartnerResult cancel(PayContext ctx, TrMstr approval, String reason);

    /** 망취소 — 승인 직후 내부 실패 시 되돌리기. 문맥의 승인 결과(tid 등)로 취소한다. */
    PartnerResult netCancel(PayContext ctx);

    default PartnerResult issueBillKey(PayContext ctx) {
        throw new FlowStop("1300", "이 제휴사는 빌키 발급을 지원하지 않습니다");
    }

    default PartnerResult removeBillKey(String bid) {
        throw new FlowStop("1300", "이 제휴사는 빌키 삭제를 지원하지 않습니다");
    }
}
