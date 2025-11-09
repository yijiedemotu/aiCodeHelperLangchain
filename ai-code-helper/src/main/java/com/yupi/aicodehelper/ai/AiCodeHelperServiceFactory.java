package com.yupi.aicodehelper.ai;

import com.yupi.aicodehelper.ai.tools.InterviewQuestionTool;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.service.AiServices; // 添加这行导入
import dev.langchain4j.model.chat.ChatModel;
import io.modelcontextprotocol.client.McpClient;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiCodeHelperServiceFactory {

    @Resource
    private ChatModel qwenChatModel;

    @Resource
    private ContentRetriever contentRetriever;

    @Resource
    private StreamingChatModel qwenStreamingChatModel;

    @Bean
    public AiCodeHelperService aiCodeHelperService() {
//        return AiServices.create(AiCodeHelperService.class, qwenChatModel);

        //会话记忆
        MessageWindowChatMemory chatMemory = MessageWindowChatMemory.withMaxMessages(10);
        //构造 Ai Services 实现类
        AiCodeHelperService build = AiServices.builder(AiCodeHelperService.class)
                .chatModel(qwenChatModel)
                .chatMemory(chatMemory) // 会话记忆
                .chatMemoryProvider((memoryId) -> MessageWindowChatMemory.withMaxMessages(10)) //每个会话独立存储
                .contentRetriever(contentRetriever) // RAG 检索增强生成
                .tools(new InterviewQuestionTool()) //工具调用
//                .toolProvider(mcpToolProvider)// 从 MCP 服务获取工具
                .streamingChatModel(qwenStreamingChatModel) // 流式对话模型
                .build();
        return build;
    }



    /*调用 AiServices.create 方法就可以创建 AI Service 的实现类了，
    背后的原理是利用   Java 反射机制  创建了一个实现  接口的代理对象  ，代理对象负责输入和输出的转换，
    比如把 String 类型的用户消息参数转为 UserMessage 类型并调用 ChatModel，再
    将 AI 返回的 AiMessage 类型转换为 String 类型作为返回值。*/
}
