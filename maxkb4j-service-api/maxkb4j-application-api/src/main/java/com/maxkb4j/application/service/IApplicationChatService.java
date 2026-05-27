package com.maxkb4j.application.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.maxkb4j.application.dto.ShareChatDTO;
import com.maxkb4j.application.entity.ApplicationChatEntity;
import com.maxkb4j.application.vo.ShareChatVO;
import com.maxkb4j.common.domain.dto.ChatMessageVO;
import com.maxkb4j.common.domain.dto.ChatParams;
import com.maxkb4j.common.domain.dto.ChatResponse;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 应用对话服务接口
 * <p>管理用户与应用之间的对话会话生命周期，包括开启对话、发送消息、删除会话、分享对话等</p>
 */
public interface IApplicationChatService extends IService<ApplicationChatEntity> {

    /** 开启一个新的对话会话，返回会话ID */
    String chatOpen(String appId, boolean debug);
    /** 发送消息并获取AI回答（同步阻塞，通过Sink推送流式消息） */
    ChatResponse chatMessage(ChatParams chatParams, Sinks.Many<ChatMessageVO> sink);
    /** 发送消息并获取AI回答（异步非阻塞版本） */
    CompletableFuture<ChatResponse> chatMessageAsync(ChatParams chatParams, Sinks.Many<ChatMessageVO> sink);
    /** 删除指定对话会话 */
    Boolean deleteById(String chatId);
    /** 将对话记录生成分享链接 */
    Map<String, String> shareChat(String id, String chatId, ShareChatDTO dto);
    /** 获取已分享的对话内容 */
    ShareChatVO shareChat(String id);
}
