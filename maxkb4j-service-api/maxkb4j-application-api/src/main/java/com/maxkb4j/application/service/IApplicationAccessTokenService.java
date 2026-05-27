package com.maxkb4j.application.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.maxkb4j.application.entity.ApplicationAccessTokenEntity;

/**
 * 应用访问令牌服务接口
 * <p>通过访问令牌查询关联的应用信息，用于分享链接和嵌入页面的鉴权</p>
 */
public interface IApplicationAccessTokenService extends IService<ApplicationAccessTokenEntity> {
    /** 根据访问令牌查询令牌实体 */
    ApplicationAccessTokenEntity getByAccessToken(String accessToken);
}
