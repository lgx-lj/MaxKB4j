package com.maxkb4j.application.entity;

import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.maxkb4j.common.mp.base.BaseEntity;
import com.maxkb4j.common.mp.entity.KnowledgeSetting;
import com.maxkb4j.common.mp.entity.LlmModelSetting;
import com.maxkb4j.common.typehandler.DatasetSettingTypeHandler;
import com.maxkb4j.common.typehandler.JSONBTypeHandler;
import com.maxkb4j.common.typehandler.LlmModelSettingTypeHandler;
import com.maxkb4j.common.typehandler.StringListTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 应用版本实体
 * <p>对应数据库表：application_version，存储应用发布时的快照版本信息，
 * 用于版本管理和回滚</p>
 */
@EqualsAndHashCode(callSuper = true)
@Data
@TableName(value = "application_version",autoResultMap = true)
public class ApplicationVersionEntity extends BaseEntity {

	/** 所属应用ID */
	private String applicationId;
	/** 应用名称（发布时快照） */
	private String applicationName;
	/** 发布者用户ID */
	private String publishUserId;
	/** 发布者用户名 */
	private String publishUserName;
	/** 版本名称 */
	private String name;
	/** 版本描述 */
	private String desc;
	/** 开场白/开场引导语 */
	private String prologue;
	/** 历史对话轮数 */
	private Integer dialogueNumber;
	/** 知识库设置（JSON格式） */
	@TableField(typeHandler = DatasetSettingTypeHandler.class)
	private KnowledgeSetting knowledgeSetting;
	/** 大模型设置（JSON格式） */
	@TableField(typeHandler = LlmModelSettingTypeHandler.class)
	private LlmModelSetting modelSetting;
	/** 是否开启问题优化 */
	private Boolean problemOptimization;
	/** 关联的大模型ID */
	private String modelId;
	/** 创建者用户ID */
	private String userId;
	/** 应用图标 */
	private String icon;
	/** 应用类型 */
	private String type;
	/** 工作流配置（JSON格式） */
	@TableField(typeHandler = JSONBTypeHandler.class)
	private JSONObject workFlow;
	/** 模型参数配置（JSON格式） */
	@TableField(typeHandler = JSONBTypeHandler.class)
	private JSONObject modelParamsSetting;
	/** 语音转文字（STT）模型ID */
	private String sttModelId;
	/** 是否启用语音转文字功能 */
	private Boolean sttModelEnable;
	/** 语音转文字后是否自动发送 */
	private Boolean sttAutoSend;
	/** 文字转语音（TTS）模型ID */
	private String ttsModelId;
	/** 是否启用文字转语音功能 */
	private Boolean ttsModelEnable;
	/** 是否自动播放语音 */
	private Boolean ttsAutoplay;
	/** TTS类型 */
	private String ttsType;
	/** 问题优化的提示词模板 */
	private String problemOptimizationPrompt;
	/** TTS模型参数配置（JSON格式） */
	@TableField(typeHandler = JSONBTypeHandler.class)
	private JSONObject ttsModelParamsSetting;
	/** 对话记录自动清理时间（单位：天） */
	private Integer cleanTime;
	/** 是否启用文件上传功能 */
	private Boolean fileUploadEnable;
	/** 文件上传配置（JSON格式） */
	@TableField(typeHandler = JSONBTypeHandler.class)
	private JSONObject fileUploadSetting;
	/** 关联的工具ID列表 */
	@TableField(typeHandler = StringListTypeHandler.class)
	private List<String> toolIds;
	/** 关联的子应用ID列表 */
	@TableField(typeHandler = StringListTypeHandler.class)
	private List<String> applicationIds;
	/** 关联的知识库ID列表 */
	@TableField(typeHandler = StringListTypeHandler.class)
	private List<String> knowledgeIds;
	/** 是否启用工具输出展示 */
	private Boolean toolOutputEnable;
	/** 是否启用长期记忆功能 */
	private Boolean longTermEnable;
}
