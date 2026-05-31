package com.dragon.agent.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dragon.agent.model.ChatRequest;
import com.dragon.agent.service.AiService;

import jakarta.validation.Valid;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 同步对话接口——等 AI 完整回复后一次性返回给前端。
 *
 * AiService.chat() 内部通过 ChatClient.call().content() 发起同步 HTTP 调用，
 * 是阻塞操作。在 WebFlux 中，阻塞调用不能跑在 Netty I/O 线程上，
 * 所以用 Mono.fromCallable + boundedElastic 切到独立线程池执行。
 *
 * @author 陈龙
 * @since 2026-05-31
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private final AiService aiService;

    public ChatController(AiService aiService) {
        this.aiService = aiService;
    }

    /**
     * POST /api/chat
     * 请求体：{"msg": "用户消息"}
     * 响应：AI 完整回复文本
     */
    @PostMapping("/chat")
    public Mono<ResponseEntity<String>> chat(@Valid @RequestBody ChatRequest request) {
        return Mono.fromCallable(() -> aiService.chat(request.msg()))
                .subscribeOn(Schedulers.boundedElastic())
                .map(ResponseEntity::ok);
    }
}
