package com.maxkb4j.application.vo;

import com.maxkb4j.application.entity.ApplicationEntity;
import com.maxkb4j.common.domain.dto.KnowledgeDTO;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 应用详情展示对象
 * <p>继承应用实体，扩展了关联知识库列表和界面展示配置信息，用于应用详情页的数据展示</p>
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class ApplicationVO extends ApplicationEntity {
    /** 关联的知识库列表 */
    private List<KnowledgeDTO> knowledgeList;
    /** 创建者昵称 */
    private String nickname;
    /** 是否显示引用来源 */
    private Boolean showSource;
    /** 是否显示执行过程 */
    private Boolean showExec;
    /** 语言设置 */
    private String language;
}
