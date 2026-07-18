package com.minipg.common.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 선불 매체 잔액. 잔액 UPDATE는 이 매퍼가 유일한 통로이며,
 * 차감은 {@code BLNC + delta >= 0} 조건부 UPDATE로 잔액부족을 원자적으로 걸러낸다 (0행 = 부족/매체없음).
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
