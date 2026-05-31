package com.dragon.agent.controller;

import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dragon.agent.model.ChatRequest;
import com.dragon.agent.service.AiService;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.deepseek.DeepSeekAssistantMessage;

import jakarta.validation.Valid;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * SSE 流式对话接口——逐 token 推送 AI 回复，实现打字机效果。
 *
 * 三种标准 SSE 事件类型：
 *   event:thinking —— 推理/思考过程 token（仅推理类模型产生，如 DeepSeek R1）
 *   event:content  —— 正文回复 token（所有模型必产生）
 *   event:done     —— 流结束信号（固定为末尾事件，由后端主动发送）
 *
 * 典型时序：
 *   推理模型：thinking* → content* → done
 *   普通模型：content* → done
 *
 * Service 层返回纯粹的 Flux<ChatResponse>（Spring AI 原生类型），
 * Controller 负责将 ChatResponse 映射为以上三种 SSE 事件。
 *
 * @author 陈龙
 * @since 2026-05-31
 */
@RestController
@RequestMapping("/api")
public class StreamController {

    private final AiService aiService;

    public StreamController(AiService aiService) {
        this.aiService = aiService;
    }

    /**
     * POST /api/stream
     * Content-Type: text/event-stream
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(@Valid @RequestBody ChatRequest request) {
        // 流结束事件，固定为最后一个 SSE 帧
        ServerSentEvent<String> doneEvent = ServerSentEvent.<String>builder()
                .event("done")
                .data("")
                .build();

        return aiService.stream(request.msg())
                .flatMapSequential(response -> {
                    Flux<ServerSentEvent<String>> events = Flux.empty();
                    for (var gen : response.getResults()) {
                        AssistantMessage output = gen.getOutput();

                        // 推理内容 → event:thinking（仅推理模型有，普通模型跳过）
                        String reasoning = extractReasoningContent(output);
                        if (reasoning != null && !reasoning.isEmpty()) {
                            events = events.concatWith(Mono.just(
                                    ServerSentEvent.<String>builder()
                                            .event("thinking")
                                            .data(reasoning)
                                            .build()));
                        }

                        // 正文 token → event:content（所有模型都有）
                        String content = output.getText();
                        if (content != null && !content.isEmpty()) {
                            events = events.concatWith(Mono.just(
                                    ServerSentEvent.<String>builder()
                                            .event("content")
                                            .data(content)
                                            .build()));
                        }
                    }
                    return events;
                })
                .concatWith(Mono.just(doneEvent));
    }

    /**
     * 从消息中提取推理/思考内容。
     *
     * 当前仅支持 DeepSeek R1 系列模型（DeepSeekAssistantMessage）。
     * 接入其他推理模型时在此处追加对应的 instanceof 分支即可，
     * 无需修改上层 SSE 映射逻辑。
     *
     * 示例扩展点：
     *   if (output instanceof OpenAiAssistantMessage oaiMsg) {
     *       return oaiMsg.getReasoningContent();
     *   }
     */
    private String extractReasoningContent(AssistantMessage output) {
        if (output instanceof DeepSeekAssistantMessage deepMsg) {
            return deepMsg.getReasoningContent();
        }
        return null;
    }
}
