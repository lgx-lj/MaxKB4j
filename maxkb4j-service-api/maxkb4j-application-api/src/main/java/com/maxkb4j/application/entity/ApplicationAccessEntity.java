package com.maxkb4j.application.entity;

import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.maxkb4j.common.mp.base.BaseEntity;
import com.maxkb4j.common.typehandler.JSONBTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 应用访问配置实体
 * <p>对应数据库表：application_access，存储应用的访问状态和配置信息</p>
 */
@EqualsAndHashCode(callSuper = true)
@Data
@TableName(value = "application_access", autoResultMap = true)
public class ApplicationAccessEntity extends BaseEntity {
    /** 访问状态信息（JSON格式），如启用/禁用等状态标识 */
    @TableField(typeHandler = JSONBTypeHandler.class)
    private JSONObject status;
    /** 访问配置信息（JSON格式），存储应用的访问相关配置参数 */
    @TableField(typeHandler = JSONBTypeHandler.class)
    private JSONObject config;
}
