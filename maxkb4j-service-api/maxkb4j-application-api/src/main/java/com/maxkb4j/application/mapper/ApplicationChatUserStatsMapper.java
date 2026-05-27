package com.maxkb4j.application.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maxkb4j.application.dto.ChatQueryDTO;
import com.maxkb4j.application.entity.ApplicationChatUserStatsEntity;
import com.maxkb4j.application.vo.ApplicationStatisticsVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 应用对话用户统计 Mapper 接口，对应实体：ApplicationChatUserStatsEntity */
@Mapper
public interface ApplicationChatUserStatsMapper extends BaseMapper<ApplicationChatUserStatsEntity>{

    /** 查询客户数量变化趋势 */
    List<ApplicationStatisticsVO> getCustomerCountTrend(String appId, @Param("query") ChatQueryDTO query);

}
