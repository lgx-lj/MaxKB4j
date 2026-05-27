package com.maxkb4j.application.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.maxkb4j.common.mp.base.BaseEntity;
import com.maxkb4j.common.typehandler.StringListTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 应用对话分享链接实体
 * <p>对应数据库表：application_chat_share_link，存储对话记录的分享链接信息，
 * 支持将对话内容分享给其他人查看</p>
 */
@EqualsAndHashCode(callSuper = true)
@Data
@TableName(value = "application_chat_share_link",autoResultMap = true)
public class ApplicationChatShareLinkEntity extends BaseEntity {
    /** 分享类型（如：single=单条记录、multiple=多条记录） */
    private String shareType;
    /** 被分享的聊天记录ID列表 */
    @TableField(typeHandler = StringListTypeHandler.class)
    private List<String> chatRecordIds;
    /** 所属对话会话ID */
    private String chatId;
    /** 所属应用ID */
    private String applicationId;
    /** 分享创建者用户ID */
    private String userId;
}
