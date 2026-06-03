package com.dragon.agent.service;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.Generation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;

import com.dragon.agent.service.DocumentService.RagResult;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * AI 对话服务——同步/流式对话、RAG 检索增强、推理过程提取。
 *
 * 通过 Spring AI ChatClient 调用 DeepSeek LLM，支持 MessageChatMemoryAdvisor
 * 管理多轮对话历史。RAG 上下文以 system message 注入，不持久化到 ChatMemory。
 * 文档服务在向量数据库未就绪时自动降级为纯对话模式。
 *
 * @author 陈龙
 * @since 2026-05-31
 */
@Service
public class AiService {

    private static final Logger log = LoggerFactory.getLogger(AiService.class);

    @Autowired
    private ChatClient.Builder chatClientBuilder;

    @Autowired
    private ChatMemory chatMemory;

    @Autowired(required = false)
    private DocumentService documentService;

    @Autowired
    private ConversationService conversationService;

    /**
     * 同步对话——保存消息、检索知识库、调用 LLM、保存回复。
     *
     * @param message
     *            用户消息
     * @param conversationId
     *            会话 ID
     * @param enableRag
     *            是否启用知识库检索
     * @param userMsgId
     *            前端生成的用户消息 ID
     * @param aiMsgId
     *            前端生成的 AI 消息 ID
     * @return AI 完整回复
     */
    public String chat(String message, String conversationId, boolean enableRag, String userMsgId, String aiMsgId) {
        conversationService.saveUserMessage(userMsgId, conversationId, message);

        RagResult rag = retrieveKnowledgeBase(message, enableRag);
        if (!rag.isEmpty()) {
            conversationService.saveRetrievalTraces(userMsgId, conversationId, rag.traces());
        }

        ChatClient client = chatClientBuilder.defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
        var prompt = client.prompt().advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId)).user(message);
        if (!rag.isEmpty()) {
            prompt = prompt.system(buildSystemPrompt(rag.context()));
        }

        String response = prompt.call().content();
        conversationService.saveAssistantMessage(aiMsgId, conversationId, response);
        return response;
    }

    /**
     * SSE 流式对话——逐 token 推送，流结束后持久化消息和推理过程。
     *
     * @param message
     *            用户消息
     * @param conversationId
     *            会话 ID
     * @param enableRag
     *            是否启用知识库检索
     * @param userMsgId
     *            用户消息 ID
     * @param aiMsgId
     *            AI 消息 ID
     * @return SSE 事件流（thinking / content / done）
     */
    public Flux<ServerSentEvent<String>> stream(String message, String conversationId, boolean enableRag,
            String userMsgId, String aiMsgId) {
        conversationService.saveUserMessage(userMsgId, conversationId, message);

        RagResult rag = retrieveKnowledgeBase(message, enableRag);
        if (!rag.isEmpty()) {
            conversationService.saveRetrievalTraces(userMsgId, conversationId, rag.traces());
        }

        ChatClient client = chatClientBuilder.defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
        var prompt = client.prompt().advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId)).user(message);
        if (!rag.isEmpty()) {
            prompt = prompt.system(buildSystemPrompt(rag.context()));
        }

        String doneData = buildDoneData(rag);

        ServerSentEvent<String> doneEvent = ServerSentEvent.<String>builder().event("done").data(doneData).build();
        StringBuilder contentBuf = new StringBuilder();
        StringBuilder reasoningBuf = new StringBuilder();

        return prompt.stream().chatResponse().flatMap(response -> {
            Flux<ServerSentEvent<String>> events = Flux.empty();
            for (Generation gen : response.getResults()) {
                AssistantMessage output = gen.getOutput();
                String reasoning = extractReasoning(output);
                if (reasoning != null && !reasoning.isEmpty()) {
                    reasoningBuf.append(reasoning);
                    events = events.concatWith(
                            Mono.just(ServerSentEvent.<String>builder().event("thinking").data(reasoning).build()));
                }
                String content = output.getText();
                if (content != null && !content.isEmpty()) {
                    contentBuf.append(content);
                    events = events.concatWith(
                            Mono.just(ServerSentEvent.<String>builder().event("content").data(content).build()));
                }
            }
            return events;
        }).concatWith(Mono.just(doneEvent)).doOnComplete(() -> {
            conversationService.saveAssistantMessage(aiMsgId, conversationId, contentBuf.toString());
            if (reasoningBuf.length() > 0) {
                conversationService.saveReasoningTrace(UUID.randomUUID().toString(), aiMsgId, conversationId,
                        reasoningBuf.toString());
            }
        });
    }

    /** 从知识库检索上下文 */
    private RagResult retrieveKnowledgeBase(String message, boolean enableRag) {
        if (!enableRag || documentService == null) {
            return RagResult.EMPTY;
        }
        return documentService.retrieveContext(message);
    }

    /** 构建 RAG 系统提示词 */
    private String buildSystemPrompt(String context) {
        return """
                以下是用户本地知识库中的文档内容。
                如果文档中有相关信息，请基于文档内容回答，并在文中提及文档名。
                如果文档信息不足，可以结合你的知识补充回答。

                ## 本地知识库文档
                %s
                """.formatted(context);
    }

    /** 编码 SSE done 事件数据——检索到的文档名列表 */
    private String buildDoneData(RagResult rag) {
        if (rag.isEmpty())
            return "";
        return rag.traces().stream()
                .map(t -> t.get("documentName") + "|"
                        + ((String) t.get("contentSnippet")).replace("\n", " ").replace("\r", " "))
                .reduce((a, b) -> a + "\n" + b).orElse("");
    }

    /** 从 AssistantMessage 中提取 DeepSeek 推理内容 */
    private String extractReasoning(AssistantMessage output) {
        if (output instanceof org.springframework.ai.deepseek.DeepSeekAssistantMessage deepMsg) {
            return deepMsg.getReasoningContent();
        }
        return null;
    }
}
