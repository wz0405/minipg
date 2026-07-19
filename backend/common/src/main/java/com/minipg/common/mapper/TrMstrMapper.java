package com.minipg.common.mapper;

import com.minipg.common.domain.TrMstr;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface TrMstrMapper {

    int insert(TrMstr tr);

    int insertBatch(@Param("list") List<TrMstr> list);

    List<TrMstr> selectByTrDt(@Param("trDt") LocalDate trDt);

    List<TrMstr> selectByTrDtAndMcht(@Param("trDt") LocalDate trDt, @Param("mchtId") String mchtId);

    TrMstr selectApproval(@Param("tid") String tid);

    int countCancel(@Param("tid") String tid);

    /** 같은 원거래 그룹(OTID)의 승인행 — 복합결제(매체별 sub-거래)의 일괄 취소에 쓴다. */
    List<TrMstr> selectApprovalsByOtid(@Param("otid") String otid);

    /** 당일 LIVE 승인 중 취소행이 아직 없는 건 — PG 자동취소 동기화 대상. */
    List<TrMstr> selectLiveUncancelled(@Param("trDt") LocalDate trDt);

    /** 재시딩 멱등화 — TID에 박힌 발생일 기준으로 기존 SEED 거래(익일취소행 포함)를 걷어낸다. */
    int deleteSeedByOriginDate(@Param("fromYmd") String fromYmd, @Param("toYmd") String toYmd);
}
