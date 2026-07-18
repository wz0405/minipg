package com.minipg.common.mapper;

import com.minipg.common.domain.SmStmt;
import com.minipg.common.domain.SmStmtTid;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface StmtMapper {

    int deleteStmt(@Param("settleDt") LocalDate settleDt);

    int deleteStmtTid(@Param("settleDt") LocalDate settleDt);

    int insertStmtTidBatch(@Param("list") List<SmStmtTid> list);

    List<SmStmt> selectAggregate(@Param("settleDt") LocalDate settleDt);

    int insertStmt(SmStmt stmt);

    List<SmStmt> selectStmt(@Param("fromDt") LocalDate fromDt, @Param("toDt") LocalDate toDt,
            @Param("mchtId") String mchtId);

    List<SmStmtTid> selectStmtTid(@Param("settleDt") LocalDate settleDt, @Param("mchtId") String mchtId);

    List<Map<String, Object>> selectMarginMonthly(@Param("month") String month);

    /** 지급예정일이 도래한 정산 건을 지급완료로 처리 (지급 배치). */
    int markPaidDue(@Param("today") LocalDate today);

    /** 가맹점 뷰 — 지급예정('0') 또는 지급완료('1') 목록. */
    List<SmStmt> selectByMchtAndStatus(@Param("mchtId") String mchtId, @Param("stmtStCd") String stmtStCd);
}
