package com.maxkb4j.application.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maxkb4j.application.entity.ApplicationAccessEntity;
import org.apache.ibatis.annotations.Mapper;

/** 应用访问记录 Mapper 接口，对应实体：ApplicationAccessEntity */
@Mapper
public interface ApplicationAccessMapper extends BaseMapper<ApplicationAccessEntity> {
}
