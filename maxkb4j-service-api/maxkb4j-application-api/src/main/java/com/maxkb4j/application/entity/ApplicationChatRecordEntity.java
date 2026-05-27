package com.maxkb4j.application.entity;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.maxkb4j.common.mp.base.BaseEntity;
import com.maxkb4j.common.typehandler.JSONBTypeHandler;
import com.maxkb4j.common.typehandler.StringListTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 应用对话记录实体
 * <p>对应数据库表：application_chat_record，存储单轮对话中用户提问和AI回答的详细记录</p>
 */
@EqualsAndHashCode(callSuper = true)
@Data
@TableName(value = "application_chat_record",autoResultMap = true)
public class ApplicationChatRecordEntity extends BaseEntity {
	/** 投票状态（如：agree=赞同、disagree=反对） */
	private String voteStatus;
	/** 投票原因 */
	private String voteReason;
	/** 投票补充内容（如用户填写的其他反馈） */
	private String voteOtherContent;
	/** 用户提问文本 */
	private String problemText;
	/** AI回答文本 */
	private String answerText;
	/** 问题消息消耗的Token数 */
	private Integer messageTokens;
	/** 回答消耗的Token数 */
	private Integer answerTokens;
	/** 整轮对话消耗的总Token数 */
	private Integer cost;
	/** 对话详情（JSON格式），包含推理过程、引用段落等详细信息 */
	@TableField(typeHandler = JSONBTypeHandler.class)
	private JSONObject details;
	/** 引用改进的段落ID列表 */
	@TableField(typeHandler = StringListTypeHandler.class)
	private List<String> improveParagraphIdList;
	/** 本轮对话的运行耗时（秒） */
	private Float runTime;
	/** 对话记录在同一会话中的排序索引 */
	private Integer index;
	/** 所属对话会话ID */
	private String chatId;
	/** 回答文本列表（JSON数组格式），流式场景下存储多个回答片段 */
	@TableField(typeHandler = JSONBTypeHandler.class)
	private JSONArray answerTextList;
}
