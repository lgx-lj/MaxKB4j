package com.maxkb4j.application.dto;

import lombok.Data;

import java.util.List;

/**
 * 分享聊天记录的数据传输对象
 * <p>用于将指定的聊天记录生成可公开访问的分享链接</p>
 */
@Data
public class ShareChatDTO {
    /** 要分享的聊天记录ID列表 */
    private List<String> chatRecordIds;
    /** 是否分享当前会话的全部记录 */
    private Boolean isCurrentAll;
}
