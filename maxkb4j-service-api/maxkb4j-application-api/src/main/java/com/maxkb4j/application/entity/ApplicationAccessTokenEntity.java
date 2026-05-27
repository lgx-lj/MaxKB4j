package com.maxkb4j.application.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.maxkb4j.common.typehandler.StringListTypeHandler;
import com.maxkb4j.common.util.MD5Util;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * 应用访问令牌实体
 * <p>对应数据库表：application_access_token，存储应用对外访问的令牌配置，
 * 包括访问控制、IP白名单、界面展示等设置</p>
 */
@NoArgsConstructor
@Data
@TableName(value = "application_access_token",autoResultMap = true)
public class ApplicationAccessTokenEntity {

    /** 应用ID（主键） */
    @TableId
    private String applicationId;
    /** 访问令牌，用于外部访问鉴权 */
    private String accessToken;
    /** 是否启用访问 */
    private Boolean isActive;
    /** 允许的最大访问次数 */
    private Integer accessNum;
    /** 是否启用IP白名单 */
    private Boolean whiteActive;
    /** IP白名单列表 */
    @TableField(typeHandler = StringListTypeHandler.class)
    private List<String> whiteList;
    /** 是否显示引用来源 */
    private Boolean showSource;
    /** 是否显示执行过程 */
    private Boolean showExec;
    /** 是否启用身份认证 */
    private Boolean authentication;
    /** 界面语言，默认zh-CH（中文） */
    private String language;
    /** 创建时间 */
    @TableField(fill = FieldFill.INSERT)
    private Date createTime;
    /** 更新时间 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;



    public ApplicationAccessTokenEntity(Boolean isActive, Integer accessNum, Boolean whiteActive, List<String> whiteList, Boolean showSource,Boolean showExec,String language) {
        this.isActive = isActive;
        this.accessNum = accessNum;
        this.whiteActive = whiteActive;
        this.whiteList = whiteList;
        this.showSource = showSource;
        this.showExec = showExec;
        this.language = language;
        this.accessToken= MD5Util.encrypt(UUID.randomUUID().toString(), 8, 24);
    }

    public static ApplicationAccessTokenEntity createDefault() {
        return new ApplicationAccessTokenEntity(true,100,false,List.of(),false,false,"zh-CH");
    }
}
