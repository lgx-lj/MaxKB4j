package com.maxkb4j.application.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.maxkb4j.common.mp.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 应用长期记忆实体
 * <p>对应数据库表：application_long_term_memory，存储用户与应用对话过程中沉淀的长期记忆信息</p>
 */
@EqualsAndHashCode(callSuper = true)
@Data
@TableName(value = "application_long_term_memory", autoResultMap = true)
public class ApplicationLongTermMemoryEntity extends BaseEntity {
    /** 长期记忆内容 */
    private String memory;
    /** 所属应用ID */
    private String applicationId;
    /** 对话用户ID */
    private String chatUserId;
}
