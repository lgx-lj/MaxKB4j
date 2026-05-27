package com.maxkb4j.application.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maxkb4j.application.entity.ApplicationAccessTokenEntity;
import org.apache.ibatis.annotations.Mapper;


/**
 * 应用访问令牌 Mapper 接口，对应实体：ApplicationAccessTokenEntity
 */
@Mapper
public interface ApplicationAccessTokenMapper extends BaseMapper<ApplicationAccessTokenEntity>{
}
