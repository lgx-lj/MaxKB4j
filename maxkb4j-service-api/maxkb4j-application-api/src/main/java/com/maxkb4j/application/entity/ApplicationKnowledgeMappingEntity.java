package com.maxkb4j.application.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.maxkb4j.common.mp.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 应用与知识库关联映射实体
 * <p>对应数据库表：application_knowledge_mapping，存储应用和知识库之间的多对多关联关系</p>
 */
@EqualsAndHashCode(callSuper = true)
@Data
@TableName("application_knowledge_mapping")
public class ApplicationKnowledgeMappingEntity extends BaseEntity {
	/** 所属应用ID */
	private String applicationId;
	/** 关联的知识库ID */
	private String knowledgeId;
} 
