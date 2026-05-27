package com.maxkb4j.application.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maxkb4j.application.entity.ApplicationVersionEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 应用版本 Mapper 接口，对应实体：ApplicationVersionEntity
 */
@Mapper
public interface ApplicationVersionMapper extends BaseMapper<ApplicationVersionEntity>{
 
}
