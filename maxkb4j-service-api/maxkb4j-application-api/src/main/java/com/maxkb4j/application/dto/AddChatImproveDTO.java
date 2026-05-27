package com.maxkb4j.application.dto;

import lombok.Data;

import java.util.List;

/**
 * 添加聊天优化记录的数据传输对象
 * <p>用于将聊天会话中的记录关联到知识库文档，作为优化素材</p>
 */
@Data
public class AddChatImproveDTO {
    /** 聊天会话ID列表 */
    private List<String> chatIds;
    /** 知识库ID */
    private String knowledgeId;
    /** 文档ID，优化素材关联的目标文档 */
    private String documentId;
}
