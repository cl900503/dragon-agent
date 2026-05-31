package com.dragon.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 应用启动和核心 Bean 装配验证。
 *
 * @author 陈龙
 * @since 2026-05-31
 */
@SpringBootTest
class AgentApplicationTests {

    @Autowired
    private ApplicationContext context;

    @Test
    void contextLoads() {
        assertThat(context).isNotNull();
    }

    @Test
    void chatClientBuilderIsAvailable() {
        assertThat(context.getBeanNamesForType(ChatClient.Builder.class)).isNotEmpty();
    }
}
