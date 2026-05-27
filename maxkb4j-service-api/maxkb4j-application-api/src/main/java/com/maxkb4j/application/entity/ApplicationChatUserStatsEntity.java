package com.maxkb4j.application.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.maxkb4j.common.mp.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 应用对话用户统计实体
 * <p>对应数据库表：application_chat_user_stats，存储用户使用某应用的访问统计数据</p>
 */
@EqualsAndHashCode(callSuper = true)
@Data
@TableName("application_chat_user_stats")
public class ApplicationChatUserStatsEntity extends BaseEntity {
	/** 累计访问次数 */
	private Integer accessNum;
	/** 当天访问次数 */
	private Integer intraDayAccessNum;
	/** 所属应用ID */
	private String applicationId;
	/** 对话用户ID */
	private String chatUserId;
	/** 对话用户类型（如：匿名用户、注册用户等） */
	private String chatUserType;
} 
