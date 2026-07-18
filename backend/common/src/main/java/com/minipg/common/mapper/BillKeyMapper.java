package com.minipg.common.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 정기결제 빌키 관리 — 카드 원본은 저장하지 않고 마스킹본만 남긴다. */
@Mapper
public interface BillKeyMapper {

    @Insert("""
            INSERT INTO MB_BILL_KEY (BID, MCHT_ID, CARD_NO_MASKED, MOID)
            VALUES (#{bid}, #{mchtId}, #{cardNoMasked}, #{moid})
            """)
    int insert(@Param("bid") String bid, @Param("mchtId") String mchtId,
            @Param("cardNoMasked") String cardNoMasked, @Param("moid") String moid);

    @Select("SELECT * FROM MB_BILL_KEY WHERE BID = #{bid} AND USE_FLG = 'Y'")
    Map<String, Object> selectActive(@Param("bid") String bid);

    @Update("UPDATE MB_BILL_KEY SET USE_FLG = 'N' WHERE BID = #{bid}")
    int deactivate(@Param("bid") String bid);

    @Select("SELECT * FROM MB_BILL_KEY ORDER BY REG_DT DESC LIMIT 100")
    List<Map<String, Object>> selectRecent();
}
