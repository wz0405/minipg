package com.minipg.common.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 선불 매체 잔액.
 *
 * 결제 차감은 여러 매체를 소진순서로 순회하며 깎아야 하므로(주매체→포인트),
 * 읽기 시점에 {@code SELECT ... FOR UPDATE}로 대상 행을 잠그고 그 안에서
 * 검증·차감을 한 트랜잭션으로 묶는다. 실무 잔액이 암호화되어 있어 DB 산술 비교가 불가능하고
 * 다중 행을 순회 차감해야 하는 구조를 그대로 반영한 것 — 단건 평문 잔액이면 조건부 UPDATE 한 방이
 * 더 단순하지만, 다중 행·순서 의존에서는 잠금 후 판정이 정석이다.
 *
 * 여러 매체를 잠글 때는 항상 같은 순서(PP_TYPE)로 잠가 교차 데드락을 막는다.
 */
@Mapper
public interface PpMapper {

    @Select("""
            SELECT u.USR_ID, u.USR_NM, m.PP_TYPE, m.BLNC
              FROM MB_USR u
              JOIN MB_PP_MTHD m ON m.USR_ID = u.USR_ID
             ORDER BY u.USR_ID, m.PP_TYPE
            """)
    List<Map<String, Object>> selectUsersWithBalance();

    /**
     * 결제 대상 매체를 잠금 순서 고정(PP_TYPE)으로 잠근다. 데드락 방지를 위해 정렬은 필수.
     * 반환은 소진순서로 다시 정렬해 서비스가 순회한다.
     */
    @Select("""
            <script>
            SELECT PP_TYPE, BLNC
              FROM MB_PP_MTHD
             WHERE USR_ID = #{usrId}
               AND PP_TYPE IN
               <foreach collection="ppTypes" item="t" open="(" separator="," close=")">#{t}</foreach>
             ORDER BY PP_TYPE
               FOR UPDATE
            </script>
            """)
    List<Map<String, Object>> selectMediaForUpdate(@Param("usrId") String usrId,
            @Param("ppTypes") List<String> ppTypes);

    @Update("""
            UPDATE MB_PP_MTHD
               SET BLNC = BLNC + #{delta}
             WHERE USR_ID = #{usrId}
               AND PP_TYPE = #{ppType}
               AND BLNC + #{delta} >= 0
            """)
    int addBalance(@Param("usrId") String usrId, @Param("ppType") String ppType, @Param("delta") long delta);

    @Select("SELECT BLNC FROM MB_PP_MTHD WHERE USR_ID = #{usrId} AND PP_TYPE = #{ppType}")
    Long selectBalance(@Param("usrId") String usrId, @Param("ppType") String ppType);

    @Insert("""
            INSERT INTO MB_PP_HIST (USR_ID, PP_TYPE, HIST_TYPE, AMT, BLNC_AFTER, TID)
            VALUES (#{usrId}, #{ppType}, #{histType}, #{amt}, #{blncAfter}, #{tid})
            """)
    int insertHist(@Param("usrId") String usrId, @Param("ppType") String ppType,
            @Param("histType") String histType, @Param("amt") long amt,
            @Param("blncAfter") long blncAfter, @Param("tid") String tid);

    @Select("""
            SELECT * FROM MB_PP_HIST
             WHERE USR_ID = #{usrId}
             ORDER BY HIST_SEQ DESC LIMIT 50
            """)
    List<Map<String, Object>> selectHist(@Param("usrId") String usrId);
}
