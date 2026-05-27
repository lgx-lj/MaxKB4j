package com.maxkb4j.application.dto;

import lombok.Data;

import java.util.List;

/**
 * 提示词生成数据传输对象
 * <p>用于根据对话历史和已有提示词，生成或优化系统提示词</p>
 */
@Data
public class PromptGenerateDTO {
    /** 对话历史消息列表 */
    private List<ChatMessageDTO> messages;
    /** 当前系统提示词内容 */
    private String prompt;
}
