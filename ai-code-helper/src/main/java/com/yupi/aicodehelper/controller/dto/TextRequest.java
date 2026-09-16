package com.yupi.aicodehelper.controller.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 单条文本类接口的请求体（POST）。
 *
 * <p>用于 {@code /chat-sync}、{@code /report} 这类只需一段输入的同步接口。
 * 采用 POST + 请求体的动机与 {@link ChatRequest} 完全一致——
 * 绕开 GET 的「URL 长度」与「Tomcat 8KB 请求头」双重上限，
 * 让用户可以放心粘贴长文本，而不必担心超长输入被容器层挡下。
 *
 * <p><b>校验说明</b>：这里只校验「非空」。长度上限统一交给
 * {@code ai-helper.guardrail.max-input-length} 对应的输入护栏处理，
 * 避免同一个阈值在 DTO 与配置里各写一份（详见 {@link ChatRequest} 的类尾说明）。
 *
 * @param message 用户输入。
 */
public record TextRequest(

        // @NotBlank 同时排除 null、空串与纯空白三种情况。
        // 纯空白输入对下游毫无价值：它既浪费一次模型调用，
        // 又会让模型输出一段「请提供具体问题」的废话。
        @NotBlank(message = "message 不能为空")
        String message
) {
}
