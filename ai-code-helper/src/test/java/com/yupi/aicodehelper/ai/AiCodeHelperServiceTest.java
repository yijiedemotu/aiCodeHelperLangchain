package com.yupi.aicodehelper.ai;

import dev.langchain4j.service.Result;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class AiCodeHelperServiceTest {

    @Resource
    private AiCodeHelperService aiCodeHelperService;

    /**
     * 结构化输出服务。
     *
     * <p>注意它<b>不是</b> AiCodeHelperService 的一部分，而是独立接口——
     * 因为结构化输出是「无状态」能力，而记忆是挂在 AiService 级别的，
     * 混在同一个接口里会导致所有无 {@code @MemoryId} 的方法共享一个
     * default 记忆桶（跨用户串号）。详见 {@link ReportAssistant} 的类注释。
     */
    @Resource
    private ReportAssistant reportAssistant;

    @Test
    void chatWithMemory() {
        String result = aiCodeHelperService.chat("你好，我是程序员邵冠铭");
        System.out.println(result);
        result = aiCodeHelperService.chat("我是谁");
        System.out.println(result);
    }


    @Test
    void chatForReport() {
        String userMessage = "我是邵冠铭，我想学习Java，建议我学习哪些内容？";
        ReportAssistant.Report report = reportAssistant.chatForReport(userMessage);
        System.out.println(report);
    }


    @Test
    void chatWithRag() {
        String result = aiCodeHelperService.chat("怎么学习 Java？有哪些常见面试题？");
        System.out.println(result);
    }


    @Test
    void chatWithTools() {
        String result = aiCodeHelperService.chat("有哪些常见的计算机网络面试题？");
        System.out.println(result);
    }


}