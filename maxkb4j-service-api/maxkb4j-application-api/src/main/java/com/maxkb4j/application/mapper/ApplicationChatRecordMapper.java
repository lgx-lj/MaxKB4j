package com.maxkb4j.application.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maxkb4j.application.entity.ApplicationChatRecordEntity;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 应用对话记录 Mapper 接口，对应实体：ApplicationChatRecordEntity
 */
@Mapper
public interface ApplicationChatRecordMapper extends BaseMapper<ApplicationChatRecordEntity>{

    /** 根据应用ID和对话用户ID分页查询对话记录 */
    List<ApplicationChatRecordEntity> listByAppIdAndChatUserId(String applicationId, String chatUserId,int pageSize,int offset);
    /** 根据应用ID和对话用户ID统计对话记录总数 */
    long countByAppIdAndChatUserId(String applicationId, String chatUserId);
}
