package com.yupi.aicodehelper.controller.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 学习方案工作流的请求体（POST /api/ai/study-plan）。
 *
 * <p>单拆一个 record 而不是复用 {@link TextRequest}，是因为这个接口的语义字段
 * 叫「主题（topic）」而不是「消息（message）」——字段名本身就是接口契约的一部分，
 * 语义准确比复用一个类更重要。
 *
 * <p><b>校验说明</b>：{@code topic} 是三个 Agent 接力工作的唯一输入，
 * 一旦为空，工作流会在第一步就抛异常，而且那是一次已经发生的模型调用
 * （约 45 秒、三次串行请求）。在入口处拦下空主题，
 * 是把「必然失败的长耗时任务」提前变成一个立刻返回的 400，省时省钱。
 *
 * @param topic 学习方向，例如「Java 后端开发」。
 */
public record TopicRequest(

        @NotBlank(message = "topic 不能为空")
        String topic
) {
}
