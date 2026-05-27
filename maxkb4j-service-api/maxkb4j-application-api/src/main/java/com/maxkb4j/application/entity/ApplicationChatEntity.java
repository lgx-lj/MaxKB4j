package com.maxkb4j.application.entity;

import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.maxkb4j.common.mp.base.BaseEntity;
import com.maxkb4j.common.typehandler.JSONBTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 应用对话实体
 * <p>对应数据库表：application_chat，存储用户与应用之间的一次完整对话会话信息</p>
 */
@EqualsAndHashCode(callSuper = true)
@Data
@TableName(value = "application_chat",autoResultMap = true)
public class ApplicationChatEntity extends BaseEntity {
    /** 对话摘要/标题 */
    private String summary;
    /** 所属应用ID */
    private String applicationId;
    /** 对话用户ID */
    private String chatUserId;
    /** 对话用户类型（如：匿名用户、注册用户等） */
    private String chatUserType;
    /** 提问者信息（JSON格式），存储提问用户的详细信息 */
    @TableField(typeHandler = JSONBTypeHandler.class)
    private JSONObject asker;
    /** 对话元数据（JSON格式），存储对话的附加信息 */
    @TableField(typeHandler = JSONBTypeHandler.class)
    private JSONObject meta;
    /** 点赞数 */
    private Integer starNum;
    /** 踩数 */
    private Integer trampleNum;
    /** 该对话下的聊天记录数量 */
    private Integer chatRecordCount;
    /** 标注数量 */
    private Integer markSum;
    /** 是否已删除（逻辑删除标志） */
    private Boolean isDeleted;
    /** 用户IP地址 */
    private String ipAddress;
    /** 对话来源信息（JSON格式），如Web、API、分享链接等 */
    @TableField(typeHandler = JSONBTypeHandler.class)
    private JSONObject source;
} 
