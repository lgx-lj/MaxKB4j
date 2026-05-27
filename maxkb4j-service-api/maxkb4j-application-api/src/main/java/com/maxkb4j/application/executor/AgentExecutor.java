package com.maxkb4j.application.executor;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.maxkb4j.application.service.IApplicationChatService;
import com.maxkb4j.common.domain.dto.ChatParams;
import com.maxkb4j.common.domain.dto.ChatResponse;
import com.maxkb4j.common.enums.ChatSource;
import com.maxkb4j.common.enums.ChatUserType;
import com.maxkb4j.common.executor.AbsToolExecutor;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import reactor.core.publisher.Sinks;

import java.util.Map;

/**
 * Agent工具执行器
 * <p>继承抽象工具执行器，用于工作流中Agent节点调用应用自身的对话能力。
 * 当工作流执行到Agent节点时，通过此执行器发起一次子对话，获取AI回答后返回结果。</p>
 */
public class AgentExecutor extends AbsToolExecutor {

    /** 应用ID */
    private final String appId;
    /** 应用对话服务 */
    private final IApplicationChatService chatService;

    public AgentExecutor(String appId, IApplicationChatService chatService) {
        this.appId = appId;
        this.chatService = chatService;
    }

    /**
     * 执行Agent工具调用
     * <p>解析工具调用请求中的参数，构建对话请求并调用对话服务获取回答</p>
     *
     * @param toolExecutionRequest 工具执行请求（包含参数信息）
     * @param memoryId 会话ID（用作对话的上下文标识）
     * @return AI生成的回答文本
     */
    @Override
    public String execute(ToolExecutionRequest toolExecutionRequest, Object memoryId) {
        // 解析工具调用参数
        Map<String, Object> args = argumentsAsMap(toolExecutionRequest.arguments());
        String message = (String) args.getOrDefault("message","");
        // 构建对话请求参数
        ChatParams params = ChatParams.builder()
                .message(message)
                .reChat(false)
                .stream(false)
                .appId(appId)
                .chatId(String.valueOf(memoryId))
                .chatUserId(IdWorker.get32UUID())
                .chatUserType(ChatUserType.ANONYMOUS_USER.name())
                .source(ChatSource.ONLINE)
                .debug(false)
                .build();
        ChatResponse chatResponse = chatService.chatMessage(params, Sinks.many().unicast().onBackpressureBuffer());
        return chatResponse.getAnswer();
    }

}

