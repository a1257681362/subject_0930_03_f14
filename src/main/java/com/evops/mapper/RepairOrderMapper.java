package com.evops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.entity.RepairOrder;
import org.apache.ibatis.annotations.Select;

public interface RepairOrderMapper extends BaseMapper<RepairOrder> {

    @Select("SELECT NEXT VALUE FOR seq_rework_no")
    long nextReworkSeq();
}
