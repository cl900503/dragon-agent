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

import com.dragon.agent.repository.UserRepository;
import com.dragon.agent.service.DocumentService.RagSearchResult;

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

    @Autowired(required = false)
    private com.dragon.agent.service.rag.QueryProcessor queryProcessor;

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private UserRepository userRepository;

    /**
     * SSE 流式对话——逐 token 推送，流结束后持久化消息和推理过程。
     * @param conversationId 会话 ID
     * @param enableRag      是否启用知识库检索
     * @param userMsgId      用户消息 ID
     * @param aiMsgId        AI 消息 ID
     * @param username       当前用户名
     * @return SSE 事件流（thinking / content / done）
     */
    public Flux<ServerSentEvent<String>> stream(String message, String conversationId, boolean enableRag,
            String userMsgId, String aiMsgId, String username) {
        conversationService.saveUserMessage(userMsgId, conversationId, message);

        RagSearchResult rag = retrieveKnowledgeBase(message, enableRag, username);
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
                    events = events.concatWith(Mono.just(
                            ServerSentEvent.<String>builder().event("thinking").data(reasoning).build()));
                }
                String content = output.getText();
                if (content != null && !content.isEmpty()) {
                    contentBuf.append(content);
                    events = events.concatWith(Mono.just(
                            ServerSentEvent.<String>builder().event("content").data(content).build()));
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

    /** 从知识库检索上下文，优先使用 QueryProcessor 进行查询改写和多路检索融合 */
    private RagSearchResult retrieveKnowledgeBase(String message, boolean enableRag, String username) {
        if (!enableRag || documentService == null) {
            return RagSearchResult.EMPTY;
        }
        Long userId = userRepository.findByUsername(username).map(u -> u.getId()).orElse(null);
        if (userId == null) {
            return RagSearchResult.EMPTY;
        }

        // 优先使用 QueryProcessor（查询改写 + 多路检索融合），不可用时降级为直接检索
        if (queryProcessor != null) {
            var result = queryProcessor.process(message, userId);
            return new RagSearchResult(result.context(), result.traces());
        }

        return documentService.retrieveContext(message, userId);
    }

    /** 构建 RAG 系统提示词 */
    private String buildSystemPrompt(String context) {
        return """
                你是企业知识库助手。请严格遵循以下规则回答问题：

                ## 回答规则
                1. **优先使用文档**：基于下方「知识库文档」中的内容回答用户问题。
                2. **引用格式**：引用文档时使用 `[N]` 标注来源编号，例如 `[1] 指出...` 或 `根据 [3] ...`。
                3. **知之为知之**：如果文档内容不足以回答问题，请明确说明"知识库中未找到相关信息"，不要编造内容。
                4. **综合回答**：当多个文档片段相互补充时，综合各片段的信息给出完整答案。

                ## 知识库文档
                %s
                """.formatted(context);
    }

    /** 编码 SSE done 事件——检索到的文档名、片段和分数 */
    private String buildDoneData(RagSearchResult rag) {
        if (rag.isEmpty())
            return "";
        return rag.traces().stream()
                .map(t -> {
                    String name = (String) t.getOrDefault("documentName", "未知");
                    String snippet = ((String) t.getOrDefault("contentSnippet", "")).replace("\n", " ").replace("\r", " ");
                    Object score = t.get("score");
                    Object chunkIdx = t.get("chunkIndex");
                    String scoreStr = score != null ? String.format("%.4f", (Double) score) : "";
                    String idxStr = chunkIdx != null ? chunkIdx.toString() : "";
                    return name + "|" + idxStr + "|" + scoreStr + "|" + snippet;
                })
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
