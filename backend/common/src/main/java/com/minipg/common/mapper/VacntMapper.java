package com.minipg.common.mapper;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 가상계좌 — 제휴사에서 벌크로 받은 계좌 풀(SI_VACNT_POOL)에서 할당하는 자체 채번 모델.
 *
 * 채번 경합은 SELECT ... FOR UPDATE 비관락으로 푼다: 같은 미사용 계좌를
 * 두 요청이 동시에 집으면 중복 채번이 되므로, 행을 잠근 뒤 할당한다.
 * (선불 잔액의 조건부 UPDATE와 대비되는 선택 — 계좌 할당은 "어느 행이든 하나"라
 * 잠금 대기 비용이 작고, 조건부 UPDATE로는 어떤 행을 집었는지 알 수 없다)
 */
@Mapper
public interface VacntMapper {

    @Select("""
            SELECT POOL_SEQ, BANK_CD, BANK_NM, VACNT_NO
              FROM SI_VACNT_POOL
             WHERE ASSIGN_ST = '0'
             ORDER BY POOL_SEQ
             LIMIT 1
               FOR UPDATE
            """)
    Map<String, Object> selectAvailablePoolForUpdate();

    @Update("UPDATE SI_VACNT_POOL SET ASSIGN_ST = '1' WHERE POOL_SEQ = #{poolSeq}")
    int assignPool(@Param("poolSeq") long poolSeq);

    @Update("UPDATE SI_VACNT_POOL SET ASSIGN_ST = '0' WHERE POOL_SEQ = #{poolSeq}")
    int releasePool(@Param("poolSeq") long poolSeq);

    @Insert("""
            INSERT INTO MB_VACNT (TID, POOL_SEQ, REQ_ID, MCHT_ID, AMT, BANK_CD, BANK_NM, VACNT_NO, EXP_DT)
            VALUES (#{tid}, #{poolSeq}, #{reqId}, #{mchtId}, #{amt}, #{bankCd}, #{bankNm}, #{vacntNo}, #{expDt})
            """)
    int insert(@Param("tid") String tid, @Param("poolSeq") Long poolSeq, @Param("reqId") String reqId,
            @Param("mchtId") String mchtId, @Param("amt") long amt, @Param("bankCd") String bankCd,
            @Param("bankNm") String bankNm, @Param("vacntNo") String vacntNo, @Param("expDt") String expDt);

    @Select("SELECT * FROM MB_VACNT WHERE TID = #{tid}")
    Map<String, Object> selectByTid(@Param("tid") String tid);

    /** 입금통보 매칭 — 같은 계좌가 회수·재할당될 수 있으므로 입금대기(ST 0) 건만 잡는다. */
    @Select("SELECT * FROM MB_VACNT WHERE VACNT_NO = #{vacntNo} AND ST_CD = '0' ORDER BY REG_DT DESC LIMIT 1")
    Map<String, Object> selectActiveByVacntNo(@Param("vacntNo") String vacntNo);

    @Update("UPDATE MB_VACNT SET ST_CD = '1', DPST_DT = NOW() WHERE TID = #{tid} AND ST_CD = '0'")
    int markDeposited(@Param("tid") String tid);

    @Select("SELECT TID, POOL_SEQ FROM MB_VACNT WHERE ST_CD = '0' AND EXP_DT < #{todayYmd}")
    List<Map<String, Object>> selectExpired(@Param("todayYmd") String todayYmd);

    @Update("UPDATE MB_VACNT SET ST_CD = '9' WHERE TID = #{tid} AND ST_CD = '0'")
    int markExpired(@Param("tid") String tid);

    @Select("SELECT * FROM MB_VACNT ORDER BY REG_DT DESC LIMIT 100")
    List<Map<String, Object>> selectRecent();

    @Select("SELECT COUNT(*) FROM SI_VACNT_POOL WHERE ASSIGN_ST = '0'")
    int countAvailablePool();
}
