package com.maxkb4j.application.service;

import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.extension.service.IService;
import com.maxkb4j.application.dto.EmbedDTO;
import com.maxkb4j.application.entity.ApplicationEntity;
import com.maxkb4j.application.vo.ApplicationVO;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * 应用核心服务接口
 * <p>提供应用级别的通用功能，包括语音转写、语音合成、嵌入代码生成、应用概览等</p>
 */
public interface IApplicationService extends IService<ApplicationEntity> {

    /** 语音转文字（STT），将语音文件转为文本 */
    String speechToText(String appId, MultipartFile file, boolean debug) throws IOException;
    /** 文字转语音（TTS），将文本转为语音音频 */
    byte[] textToSpeech(String appId, JSONObject data, boolean debug);
    /** 生成应用嵌入代码（iframe方式嵌入第三方网页） */
    String embed(EmbedDTO dto);
    /** 获取应用概览信息（包含关联知识库列表、界面配置等） */
    ApplicationVO appProfile(String appId);
}
