package com.maxkb4j.application.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.maxkb4j.application.entity.ApplicationApiKeyEntity;

/**
 * 应用API密钥服务接口
 * <p>通过密钥查询关联的应用信息，用于第三方API接口调用鉴权</p>
 */
public interface IApplicationApiKeyService extends IService<ApplicationApiKeyEntity> {
    /** 根据密钥查询API密钥实体 */
    ApplicationApiKeyEntity getBySecretKey(String secretKey);
}
