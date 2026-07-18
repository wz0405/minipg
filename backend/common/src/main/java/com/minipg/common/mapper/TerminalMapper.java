package com.minipg.common.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 오프라인 단말기 레지스트리 — VAN 통보 전문은 가맹점을 모르고 단말번호만 안다. */
@Mapper
public interface TerminalMapper {

    @Select("SELECT MCHT_ID FROM SI_TERMINAL WHERE TERM_NO = #{termNo} AND USE_FLG = 'Y'")
    String selectMchtId(@Param("termNo") String termNo);

    @Select("SELECT TERM_NO, MCHT_ID, USE_FLG FROM SI_TERMINAL ORDER BY TERM_NO")
    List<Map<String, Object>> selectAll();
}
