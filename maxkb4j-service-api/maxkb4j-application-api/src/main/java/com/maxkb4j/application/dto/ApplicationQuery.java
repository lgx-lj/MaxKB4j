package com.maxkb4j.application.dto;

import lombok.Data;

/**
 * 应用查询条件
 * <p>用于按名称、发布状态、创建人、文件夹、类型等条件筛选应用</p>
 */
@Data
public class ApplicationQuery {
    /** 应用名称（模糊查询） */
    private String name;
    /** 发布状态 */
    private String publishStatus;
    /** 创建人ID */
    private String createUser;
    /** 所属文件夹ID */
    private String folderId;
    /** 应用类型 */
    private String type;
}
