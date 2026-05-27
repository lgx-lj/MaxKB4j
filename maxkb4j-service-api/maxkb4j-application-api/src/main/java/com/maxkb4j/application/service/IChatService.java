package com.maxkb4j.application.service;

import com.maxkb4j.application.vo.ApplicationVO;
import com.maxkb4j.common.domain.dto.ChatMessageVO;
import com.maxkb4j.common.domain.dto.ChatParams;
import com.maxkb4j.common.domain.dto.ChatResponse;
import reactor.core.publisher.Sinks;

/**
 * 通用聊天服务接口
 * <p>接收用户消息，结合应用配置和上下文，调用大模型生成回答并通过Sink推送流式响应</p>
 */
public interface IChatService {

    /** 处理聊天消息，返回AI回答 */
    ChatResponse chatMessage(ApplicationVO application, ChatParams chatParams, Sinks.Many<ChatMessageVO> sink);
}
