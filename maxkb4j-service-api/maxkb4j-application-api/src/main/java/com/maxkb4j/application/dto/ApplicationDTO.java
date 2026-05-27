package com.maxkb4j.application.dto;

import com.alibaba.fastjson.JSONObject;
import com.maxkb4j.application.entity.ApplicationEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 应用数据传输对象
 * <p>继承应用实体，扩展了工作流模板字段，用于应用创建和编辑场景</p>
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class ApplicationDTO extends ApplicationEntity {
    /** 工作流模板配置（JSON格式） */
    private JSONObject  workFlowTemplate;
}
