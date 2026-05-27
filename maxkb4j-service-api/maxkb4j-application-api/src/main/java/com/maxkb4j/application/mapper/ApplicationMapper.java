package com.maxkb4j.application.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maxkb4j.application.entity.ApplicationEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 应用主表 Mapper 接口，对应实体：ApplicationEntity
 */
@Mapper
public interface ApplicationMapper extends BaseMapper<ApplicationEntity>{

}
