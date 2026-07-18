package com.minipg.common.mapper;

import com.minipg.common.domain.MchtFeeRate;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface MchtMapper {

    /** 기준일 시점 최신 적용분의 원가/판가 요율을 가맹점별로 조회. */
    List<MchtFeeRate> selectFeeRates(@Param("baseDt") LocalDate baseDt);

    List<Map<String, Object>> selectMchtList();
}
