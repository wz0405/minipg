package com.minipg.common.mapper;

import com.minipg.common.domain.InPgTrx;
import com.minipg.common.domain.RcResult;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ReconMapper {

    int deleteResult(@Param("reconDt") LocalDate reconDt);

    int insertOnlyUs(@Param("reconDt") LocalDate reconDt);

    int insertOnlyPg(@Param("reconDt") LocalDate reconDt);

    int insertAmtMismatch(@Param("reconDt") LocalDate reconDt);

    List<RcResult> selectResult(@Param("reconDt") LocalDate reconDt);

    List<Map<String, Object>> selectSummary(@Param("reconDt") LocalDate reconDt);

    int insertPgTrxBatch(@Param("list") List<InPgTrx> list);

    int countPgTrx(@Param("reconDt") LocalDate reconDt);

    /** PG 대사 소스가 없는 날, 내부 원장을 그대로 미러링해 모의 소스를 만든다 (AUTO_MOCK). */
    int insertMockFromTrMstr(@Param("reconDt") LocalDate reconDt);

    /** 재시딩 멱등화 — 기존 시딩 PG소스 제거. */
    int deleteSeedSrc(@Param("fromDt") LocalDate fromDt, @Param("toDt") LocalDate toDt);
}
