package com.yupi.aicodehelper.ai;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service//作用：将AiCodeHelper类标记为一个服务组件，使Spring能够自动扫描并创建该类的实例。
@Slf4j
public class AiCodeHelper {

    @Resource//作用：自动注入ChatModel实例，使AiCodeHelper类能够使用ChatModel的功能。
    private ChatModel qwenChatModel;

    public String chat(String message) {
        UserMessage userMessage = UserMessage.from(message);
        ChatResponse chatResponse = qwenChatModel.chat(userMessage);
        AiMessage aiMessage = chatResponse.aiMessage();
        log.info("ai回复：{}", aiMessage.toString());
        return aiMessage.text();
    }
}
