package com.maxkb4j.application.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.maxkb4j.common.mp.base.BaseEntity;
import com.maxkb4j.common.typehandler.StringListTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 应用API密钥实体
 * <p>对应数据库表：application_api_key，存储应用的API密钥信息，用于第三方接口调用鉴权</p>
 */
@EqualsAndHashCode(callSuper = true)
@Data
@TableName(value = "application_api_key", autoResultMap = true)
public class ApplicationApiKeyEntity extends BaseEntity {
	/** API密钥 */
	private String secretKey;
	/** 是否启用 */
	private Boolean isActive;
	/** 所属应用ID */
	private String applicationId;
	/** 创建者用户ID */
	private String userId;
	/** 是否允许跨域访问 */
	private Boolean allowCrossDomain;
	/** 允许跨域的域名白名单列表 */
	@TableField(typeHandler = StringListTypeHandler.class)
	private List<String> crossDomainList;
} 
