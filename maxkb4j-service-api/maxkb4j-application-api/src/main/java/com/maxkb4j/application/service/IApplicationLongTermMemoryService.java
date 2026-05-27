package com.maxkb4j.application.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.maxkb4j.application.entity.ApplicationLongTermMemoryEntity;

/**
 * 应用长期记忆服务接口
 * <p>支持多轮对话的上下文记忆保存、查询和删除，用于增强AI的连续对话能力</p>
 */
public interface IApplicationLongTermMemoryService extends IService<ApplicationLongTermMemoryEntity> {

    /** 保存长期记忆（从最近的对话记录中提取关键信息并压缩存储） */
    void saveMemory(String applicationId, String chatUserId, String modelId, int pageSize);
    /** 查询指定用户的长期记忆内容 */
    String getMemory(String applicationId, String chatUserId);
    /** 删除指定应用的全部长期记忆 */
    void deleteMemory(String applicationId);
}
