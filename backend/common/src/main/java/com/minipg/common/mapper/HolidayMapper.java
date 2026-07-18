package com.minipg.common.mapper;

import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface HolidayMapper {

    @Select("SELECT HOLI_DT FROM AD_HOLIDAY")
    List<LocalDate> selectAll();
}
