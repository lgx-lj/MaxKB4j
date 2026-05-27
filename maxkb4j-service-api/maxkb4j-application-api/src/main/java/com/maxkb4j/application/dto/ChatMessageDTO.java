package com.maxkb4j.application.dto;

import lombok.Data;

/**
 * 聊天消息数据传输对象
 * <p>表示对话中的一条消息，包含消息内容和角色标识</p>
 */
@Data
public class ChatMessageDTO {
    /** 消息内容 */
    private String content;
    /** 消息角色，如 user（用户）、assistant（助手）、system（系统） */
    private String role;
}
