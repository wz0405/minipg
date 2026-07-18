-- minipg 스키마 초기화 (docker-entrypoint-initdb.d)
-- 명명 규칙: {도메인}_{테이블}  SI=가맹점정보, TR=거래, SM=정산, IN=수신, RC=대사, AD=운영
CREATE DATABASE IF NOT EXISTS minipg DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE minipg;

CREATE TABLE SI_MCHT (
    MCHT_ID       VARCHAR(20)  NOT NULL COMMENT '가맹점 ID',
    MCHT_NM       VARCHAR(100) NOT NULL COMMENT '가맹점명',
    SETTLE_CYCLE INT          NOT NULL DEFAULT 2 COMMENT '지급예정일 = 거래일 + N영업일',
    USE_FLG      CHAR(1)      NOT NULL DEFAULT 'Y',
    REG_DT       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (MCHT_ID)
) COMMENT='가맹점';

CREATE TABLE SI_PTN_FEE (
    MCHT_ID   VARCHAR(20)  NOT NULL,
    PM_CD    VARCHAR(10)  NOT NULL DEFAULT 'CARD' COMMENT '결제수단',
    APPLY_DT DATE         NOT NULL COMMENT '적용 시작일',
    FEE_RATE DECIMAL(6,4) NOT NULL COMMENT '원가율 %% (PG -> 당사)',
    PRIMARY KEY (MCHT_ID, PM_CD, APPLY_DT)
) COMMENT='원가 수수료 정책';

CREATE TABLE SI_STMT_FEE (
    MCHT_ID   VARCHAR(20)  NOT NULL,
    PM_CD    VARCHAR(10)  NOT NULL DEFAULT 'CARD',
    APPLY_DT DATE         NOT NULL COMMENT '적용 시작일',
    FEE_RATE DECIMAL(6,4) NOT NULL COMMENT '판가율 %% (당사 -> 가맹점)',
    PRIMARY KEY (MCHT_ID, PM_CD, APPLY_DT)
) COMMENT='판가 수수료 정책';

CREATE TABLE TR_MSTR (
    TR_SEQ         BIGINT       NOT NULL AUTO_INCREMENT,
    TID            VARCHAR(40)  NOT NULL COMMENT 'PG 거래번호',
    ORG_TID           VARCHAR(40)  NOT NULL COMMENT '원거래번호 (승인행 = 자기 TID)',
    MCHT_ID         VARCHAR(20)  NOT NULL,
    PM_CD          VARCHAR(10)  NOT NULL DEFAULT 'CARD',
    TX_ST_CD      CHAR(1)      NOT NULL COMMENT '0=승인, 2=취소',
    AMT            BIGINT       NOT NULL COMMENT '취소행은 음수',
    TR_DT          DATE         NOT NULL COMMENT '발생일 (취소행 = 취소일)',
    TR_TM          DATETIME     NOT NULL,
    CHANNEL        VARCHAR(10)  NOT NULL COMMENT 'LIVE=실결제, SEED=시딩',
    PAY_TYPE       VARCHAR(10)  NOT NULL DEFAULT 'AUTH' COMMENT 'AUTH=결제창, KEYIN=수기',
    ORDER_ID       VARCHAR(64)  NULL,
    GOODS_NM       VARCHAR(100) NULL,
    CARD_NO_MASKED VARCHAR(20)  NULL COMMENT '마스킹 카드번호 (원본 비저장)',
    USR_ID         VARCHAR(20)  NULL COMMENT '선불(MONEY/POINT) 거래 회원 — 취소 시 잔액 복원용',
    REG_DT         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (TR_SEQ),
    UNIQUE KEY UX_TR_MSTR (TID, TX_ST_CD),
    KEY IX_TR_MSTR_DT (TR_DT, MCHT_ID)
) COMMENT='거래 원장 (INSERT-only, 취소=음수 별도행)';

CREATE TABLE MB_PAY_REQ (
    REQ_ID     VARCHAR(40)  NOT NULL COMMENT '결제요청 ID (가맹점 주문과 1:1)',
    MCHT_ID     VARCHAR(20)  NOT NULL,
    AMT        BIGINT       NOT NULL COMMENT '요청 금액 — 승인 시 대조(변조 방지)',
    GOODS_NM   VARCHAR(100) NULL,
    RETURN_URL VARCHAR(300) NULL COMMENT '결제 완료 후 가맹점 복귀 URL',
    NOTIFY_URL VARCHAR(300) NULL COMMENT '승인 결과 서버통보 URL (별도 스레드 발송)',
    REQ_ST_CD  CHAR(1)      NOT NULL DEFAULT '0' COMMENT '0=요청, 1=승인완료, 2=실패',
    TID        VARCHAR(40)  NULL COMMENT '승인 완료 시 거래번호',
    REG_DT     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UPD_DT     DATETIME     NULL,
    PRIMARY KEY (REQ_ID),
    KEY IX_MB_PAY_REQ (MCHT_ID, REG_DT)
) COMMENT='결제요청 (주문 사전등록 — 상태 테이블, UPDATE 허용)';

CREATE TABLE SM_STMT_TID (
    STMT_TID_SEQ BIGINT       NOT NULL AUTO_INCREMENT,
    SETTLE_DT    DATE         NOT NULL COMMENT '정산 대상 거래일',
    MCHT_ID       VARCHAR(20)  NOT NULL,
    PM_CD        VARCHAR(10)  NOT NULL,
    TR_SEQ       BIGINT       NOT NULL,
    TID          VARCHAR(40)  NOT NULL,
    TX_ST_CD    CHAR(1)      NOT NULL,
    AMT          BIGINT       NOT NULL,
    COST_RATE    DECIMAL(6,4) NOT NULL COMMENT '적용 원가율 스냅샷',
    SALES_RATE   DECIMAL(6,4) NOT NULL COMMENT '적용 판가율 스냅샷',
    COST_FEE     BIGINT       NOT NULL,
    SALES_FEE    BIGINT       NOT NULL,
    FEE_VAT      BIGINT       NOT NULL COMMENT '판가 수수료 VAT (절사)',
    MARGIN_AMT   BIGINT       NOT NULL COMMENT '판가 - 원가',
    PAYOUT_AMT   BIGINT       NOT NULL COMMENT '거래액 - 판가수수료 - VAT',
    REG_DT       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (STMT_TID_SEQ),
    KEY IX_SM_STMT_TID (SETTLE_DT, MCHT_ID)
) COMMENT='건별 정산 (수수료 계산 스냅샷)';

CREATE TABLE SM_STMT (
    SETTLE_DT  DATE        NOT NULL,
    MCHT_ID     VARCHAR(20) NOT NULL,
    PM_CD      VARCHAR(10) NOT NULL,
    TRX_CNT    INT         NOT NULL,
    TRX_AMT    BIGINT      NOT NULL,
    COST_FEE   BIGINT      NOT NULL,
    SALES_FEE  BIGINT      NOT NULL,
    FEE_VAT    BIGINT      NOT NULL,
    MARGIN_AMT BIGINT      NOT NULL,
    PAYOUT_AMT BIGINT      NOT NULL,
    PAYOUT_DT  DATE        NOT NULL COMMENT '지급예정일 (거래일 + N영업일)',
    STMT_ST_CD CHAR(1)     NOT NULL DEFAULT '0' COMMENT '0=지급예정, 1=지급완료',
    REG_DT     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (SETTLE_DT, MCHT_ID, PM_CD)
) COMMENT='일 정산 집계 (가맹점 × 결제수단)';

CREATE TABLE SI_VACNT_POOL (
    POOL_SEQ  BIGINT      NOT NULL AUTO_INCREMENT,
    BANK_CD   VARCHAR(4)  NOT NULL,
    BANK_NM   VARCHAR(30) NOT NULL,
    VACNT_NO  VARCHAR(30) NOT NULL COMMENT '가상계좌번호 (제휴사 벌크 수령분)',
    ASSIGN_ST CHAR(1)     NOT NULL DEFAULT '0' COMMENT '0=미사용, 1=사용중',
    REG_DT    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (POOL_SEQ),
    UNIQUE KEY UX_SI_VACNT_POOL (VACNT_NO)
) COMMENT='가상계좌 풀 — 제휴사로부터 벌크 수령, 채번=할당·만료=회수';

CREATE TABLE MB_VACNT (
    TID      VARCHAR(40) NOT NULL COMMENT '채번 거래번호',
    POOL_SEQ BIGINT      NULL COMMENT '할당된 풀 계좌',
    REQ_ID   VARCHAR(40) NULL,
    MCHT_ID   VARCHAR(20) NOT NULL,
    AMT      BIGINT      NOT NULL,
    BANK_CD  VARCHAR(4)  NULL,
    BANK_NM  VARCHAR(30) NULL,
    VACNT_NO VARCHAR(30) NOT NULL COMMENT '가상계좌번호 (할당 시점 스냅샷)',
    EXP_DT   VARCHAR(8)  NULL COMMENT '입금기한 YYYYMMDD',
    ST_CD    CHAR(1)     NOT NULL DEFAULT '0' COMMENT '0=채번, 1=입금완료, 9=만료',
    REG_DT   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    DPST_DT  DATETIME    NULL COMMENT '입금 시각',
    PRIMARY KEY (TID),
    KEY IX_MB_VACNT_NO (VACNT_NO, ST_CD)
) COMMENT='가상계좌 채번 (입금통보 수신 시 TR_MSTR 승인행 적재)';

CREATE TABLE MB_BILL_KEY (
    BID            VARCHAR(40) NOT NULL COMMENT '빌키',
    MCHT_ID         VARCHAR(20) NOT NULL,
    CARD_NO_MASKED VARCHAR(20) NULL,
    MOID           VARCHAR(64) NULL COMMENT '발급 주문번호',
    USE_FLG        CHAR(1)     NOT NULL DEFAULT 'Y',
    REG_DT         DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (BID)
) COMMENT='정기결제 빌키 (카드 원본 비저장)';

CREATE TABLE MB_USR (
    USR_ID VARCHAR(20) NOT NULL,
    USR_NM VARCHAR(50) NOT NULL,
    REG_DT DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (USR_ID)
) COMMENT='선불 회원';

CREATE TABLE MB_PP_MTHD (
    USR_ID  VARCHAR(20) NOT NULL,
    PP_TYPE VARCHAR(10) NOT NULL COMMENT 'MONEY / POINT',
    BLNC    BIGINT      NOT NULL DEFAULT 0 COMMENT '잔액 — 유일한 UPDATE 허용 지점',
    UPD_DT  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (USR_ID, PP_TYPE)
) COMMENT='선불 매체 잔액';

CREATE TABLE MB_PP_HIST (
    HIST_SEQ   BIGINT      NOT NULL AUTO_INCREMENT,
    USR_ID     VARCHAR(20) NOT NULL,
    PP_TYPE    VARCHAR(10) NOT NULL,
    HIST_TYPE  VARCHAR(10) NOT NULL COMMENT 'CHARGE / PAY / CANCEL',
    AMT        BIGINT      NOT NULL COMMENT '잔액 증감 (결제=음수)',
    BLNC_AFTER BIGINT      NOT NULL,
    TID        VARCHAR(40) NULL,
    REG_DT     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (HIST_SEQ),
    KEY IX_MB_PP_HIST (USR_ID, PP_TYPE, REG_DT)
) COMMENT='선불 잔액 변동 이력 (INSERT-only)';

CREATE TABLE IN_PG_TRX (
    SRC_SEQ   BIGINT      NOT NULL AUTO_INCREMENT,
    RECON_DT  DATE        NOT NULL COMMENT '대사 기준일',
    TID       VARCHAR(40) NOT NULL,
    TX_ST_CD CHAR(1)     NOT NULL,
    AMT       BIGINT      NOT NULL,
    SRC_NM    VARCHAR(50) NULL COMMENT '대사 소스 (SEED_GEN / AUTO_MOCK / FILE)',
    REG_DT    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (SRC_SEQ),
    KEY IX_IN_PG_TRX (RECON_DT, TID)
) COMMENT='PG 거래내역 수신 (거래대사 소스)';

CREATE TABLE RC_RESULT (
    RESULT_SEQ BIGINT      NOT NULL AUTO_INCREMENT,
    RECON_DT   DATE        NOT NULL,
    TID        VARCHAR(40) NULL,
    TX_ST_CD  CHAR(1)     NULL,
    DIFF_TYPE  VARCHAR(20) NOT NULL COMMENT 'ONLY_US / ONLY_PG / AMT_MISMATCH',
    US_AMT     BIGINT      NULL,
    PG_AMT     BIGINT      NULL,
    REG_DT     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (RESULT_SEQ),
    KEY IX_RC_RESULT (RECON_DT)
) COMMENT='거래대사 불일치 결과';

CREATE TABLE AD_HOLIDAY (
    HOLI_DT DATE        NOT NULL,
    HOLI_NM VARCHAR(50) NOT NULL,
    PRIMARY KEY (HOLI_DT)
) COMMENT='휴일 (지급예정일 영업일 계산용)';

-- ---------------------------------------------------------------
-- 기초 데이터
-- ---------------------------------------------------------------
INSERT INTO SI_MCHT (MCHT_ID, MCHT_NM, SETTLE_CYCLE) VALUES
    ('demostore01', '데모 스토어',   2),
    ('democafe02',  '데모 카페',     2),
    ('demobooks03', '데모 서점',     3);

INSERT INTO SI_PTN_FEE (MCHT_ID, PM_CD, APPLY_DT, FEE_RATE) VALUES
    ('demostore01', 'CARD',  '2026-01-01', 1.7000),
    ('democafe02',  'CARD',  '2026-01-01', 1.8000),
    ('demobooks03', 'CARD',  '2026-01-01', 1.9000),
    ('demostore01', 'VACNT', '2026-01-01', 0.5000),
    ('democafe02',  'VACNT', '2026-01-01', 0.5000),
    ('demobooks03', 'VACNT', '2026-01-01', 0.5000),
    ('demostore01', 'MONEY', '2026-01-01', 0.0000),
    ('democafe02',  'MONEY', '2026-01-01', 0.0000),
    ('demobooks03', 'MONEY', '2026-01-01', 0.0000),
    ('demostore01', 'POINT', '2026-01-01', 0.0000),
    ('democafe02',  'POINT', '2026-01-01', 0.0000),
    ('demobooks03', 'POINT', '2026-01-01', 0.0000);

INSERT INTO SI_STMT_FEE (MCHT_ID, PM_CD, APPLY_DT, FEE_RATE) VALUES
    ('demostore01', 'CARD',  '2026-01-01', 2.9000),
    ('democafe02',  'CARD',  '2026-01-01', 3.1000),
    ('demobooks03', 'CARD',  '2026-01-01', 3.3000),
    ('demostore01', 'VACNT', '2026-01-01', 1.0000),
    ('democafe02',  'VACNT', '2026-01-01', 1.0000),
    ('demobooks03', 'VACNT', '2026-01-01', 1.1000),
    ('demostore01', 'MONEY', '2026-01-01', 1.5000),
    ('democafe02',  'MONEY', '2026-01-01', 1.5000),
    ('demobooks03', 'MONEY', '2026-01-01', 1.5000),
    ('demostore01', 'POINT', '2026-01-01', 1.5000),
    ('democafe02',  'POINT', '2026-01-01', 1.5000),
    ('demobooks03', 'POINT', '2026-01-01', 1.5000);

INSERT INTO SI_VACNT_POOL (BANK_CD, BANK_NM, VACNT_NO) VALUES
    ('020', '우리은행', '5610001000101'), ('020', '우리은행', '5610001000102'),
    ('020', '우리은행', '5610001000103'), ('020', '우리은행', '5610001000104'),
    ('020', '우리은행', '5610001000105'), ('020', '우리은행', '5610001000106'),
    ('020', '우리은행', '5610001000107'), ('020', '우리은행', '5610001000108'),
    ('004', '국민은행', '9410002000201'), ('004', '국민은행', '9410002000202'),
    ('004', '국민은행', '9410002000203'), ('004', '국민은행', '9410002000204'),
    ('004', '국민은행', '9410002000205'), ('004', '국민은행', '9410002000206'),
    ('088', '신한은행', '5620003000301'), ('088', '신한은행', '5620003000302'),
    ('088', '신한은행', '5620003000303'), ('088', '신한은행', '5620003000304'),
    ('088', '신한은행', '5620003000305'), ('088', '신한은행', '5620003000306');

INSERT INTO MB_USR (USR_ID, USR_NM) VALUES
    ('demouser01', '데모회원1'),
    ('demouser02', '데모회원2');

INSERT INTO MB_PP_MTHD (USR_ID, PP_TYPE, BLNC) VALUES
    ('demouser01', 'MONEY', 100000),
    ('demouser01', 'POINT', 50000),
    ('demouser02', 'MONEY', 30000),
    ('demouser02', 'POINT', 10000);

-- 2026년 대한민국 공휴일 (샘플 — 대체공휴일 포함 여부는 운영 시 보정)
INSERT INTO AD_HOLIDAY (HOLI_DT, HOLI_NM) VALUES
    ('2026-01-01', '신정'),
    ('2026-02-16', '설날 연휴'),
    ('2026-02-17', '설날'),
    ('2026-02-18', '설날 연휴'),
    ('2026-03-01', '삼일절'),
    ('2026-03-02', '삼일절 대체'),
    ('2026-05-05', '어린이날'),
    ('2026-05-24', '부처님오신날'),
    ('2026-05-25', '부처님오신날 대체'),
    ('2026-06-06', '현충일'),
    ('2026-08-15', '광복절'),
    ('2026-08-17', '광복절 대체'),
    ('2026-09-24', '추석 연휴'),
    ('2026-09-25', '추석'),
    ('2026-09-26', '추석 연휴'),
    ('2026-10-03', '개천절'),
    ('2026-10-05', '개천절 대체'),
    ('2026-10-09', '한글날'),
    ('2026-12-25', '성탄절');
