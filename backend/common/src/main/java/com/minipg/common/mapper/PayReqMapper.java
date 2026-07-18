package com.minipg.common.mapper;

import com.minipg.common.domain.PayReq;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface PayReqMapper {

    @Insert("""
            INSERT INTO MB_PAY_REQ (REQ_ID, MCHT_ID, AMT, GOODS_NM, RETURN_URL, NOTIFY_URL)
            VALUES (#{reqId}, #{mchtId}, #{amt}, #{goodsNm}, #{returnUrl}, #{notifyUrl})
            """)
    int insert(PayReq req);

    @Select("SELECT * FROM MB_PAY_REQ WHERE REQ_ID = #{reqId}")
    PayReq selectById(@Param("reqId") String reqId);

    @Update("""
            UPDATE MB_PAY_REQ
               SET REQ_ST_CD = #{reqStCd}, TID = #{tid}, UPD_DT = NOW()
             WHERE REQ_ID = #{reqId}
            """)
    int updateStatus(@Param("reqId") String reqId, @Param("reqStCd") String reqStCd, @Param("tid") String tid);
}
