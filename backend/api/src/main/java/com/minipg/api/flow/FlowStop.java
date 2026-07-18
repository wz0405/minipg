package com.minipg.api.flow;

import lombok.Getter;

/** 업무 판정에 의한 처리 중단 — 결과코드/메시지가 그대로 응답 전문이 된다. */
@Getter
public class FlowStop extends RuntimeException {

    private final String code;

    public FlowStop(String code, String msg) {
        super(msg);
        this.code = code;
    }
}
