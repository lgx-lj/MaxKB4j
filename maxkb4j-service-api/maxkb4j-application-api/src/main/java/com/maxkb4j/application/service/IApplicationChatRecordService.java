package com.maxkb4j.application.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.maxkb4j.application.entity.ApplicationChatRecordEntity;
import com.maxkb4j.application.vo.ApplicationChatRecordVO;

/**
 * 应用对话记录服务接口
 * <p>提供对话记录的详情查询和分页查询功能</p>
 */
public interface IApplicationChatRecordService extends IService<ApplicationChatRecordEntity> {

    /** 获取单条对话记录详情（包含引用段落和工作流执行详情） */
    ApplicationChatRecordVO getChatRecordInfo(String chatId, String chatRecordId);
    /** 分页查询对话记录 */
    IPage<ApplicationChatRecordVO> chatRecordPage(String chatId, int current, int size);
}
