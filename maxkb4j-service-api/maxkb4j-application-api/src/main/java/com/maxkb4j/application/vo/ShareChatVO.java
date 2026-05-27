package com.maxkb4j.application.vo;

import com.maxkb4j.application.entity.ApplicationChatRecordEntity;
import lombok.Data;

import java.util.List;

/**
 * 分享对话展示对象
 * <p>用于对话分享功能，包含对话摘要和完整的对话记录列表，生成可分享的对话页面</p>
 */
@Data
public class ShareChatVO {
    /** 对话摘要（对整个对话内容的总结） */
    private String summary;
    /** 对话记录列表 */
    private List<ApplicationChatRecordEntity> chatRecordList;
}
