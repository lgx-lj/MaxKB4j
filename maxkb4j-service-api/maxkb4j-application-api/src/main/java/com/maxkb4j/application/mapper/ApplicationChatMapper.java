package com.maxkb4j.application.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maxkb4j.application.dto.ChatQueryDTO;
import com.maxkb4j.application.entity.ApplicationChatEntity;
import com.maxkb4j.application.vo.ApplicationStatisticsVO;
import com.maxkb4j.application.vo.ChatRecordDetailVO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 应用对话 Mapper 接口，对应实体：ApplicationChatEntity
 */
@Mapper
public interface ApplicationChatMapper extends BaseMapper<ApplicationChatEntity>{

    /** 根据对话ID列表批量查询对话记录详情 */
    List<ChatRecordDetailVO> chatRecordDetail(List<String>  ids);
    /** 查询应用对话统计数据 */
    List<ApplicationStatisticsVO> statistics(String appId, ChatQueryDTO query);
    /** 查询应用用户Token使用量统计 */
    List<ApplicationStatisticsVO> userTokenUsage(String appId, ChatQueryDTO query);
    /** 查询应用热门问题排行 */
    List<ApplicationStatisticsVO> topQuestions(String appId, ChatQueryDTO query);
}
