package com.dragon.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Dragon Agent 启动入口。
 *
 * 基于 Spring Boot WebFlux（Netty）+ Spring AI DeepSeek，
 * 提供同步和 SSE 流式两种对话接口。
 * SpringBootApplication 已包含自动配置和组件扫描，无需额外注解。
 *
 * @author 陈龙
 * @since 2026-05-31
 */
@SpringBootApplication
public class AgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentApplication.class, args);
    }
}
