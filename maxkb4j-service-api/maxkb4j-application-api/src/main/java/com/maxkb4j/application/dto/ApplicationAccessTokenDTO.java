package com.maxkb4j.application.dto;

import com.maxkb4j.application.entity.ApplicationAccessTokenEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 应用访问令牌数据传输对象
 * <p>继承应用访问令牌实体，扩展了令牌重置状态标识</p>
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class ApplicationAccessTokenDTO extends ApplicationAccessTokenEntity {
    /** 访问令牌是否已重置 */
    private Boolean accessTokenReset;
}
