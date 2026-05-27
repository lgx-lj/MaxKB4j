package com.maxkb4j.application.dto;

import lombok.Data;

/**
 * 聊天优化数据传输对象
 * <p>用于向知识库添加优化内容，将用户反馈的问题及回答作为知识库素材</p>
 */
@Data
public class ChatImproveDTO {
    /** 回答内容 */
    private String content;
    /** 用户提问的原文 */
    private String problemText;
    /** 优化素材标题 */
    private String title;
}
