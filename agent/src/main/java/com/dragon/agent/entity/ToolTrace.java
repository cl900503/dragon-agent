package com.dragon.agent.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * 工具调用追溯（ToolTrace）——记录 MCP / Function Calling 的工具调用历史。 当前为预留设计，后续接入 MCP 时启用。
 *
 * @author 陈龙
 * @since 2026-06-01
 */
@Entity
@Table(name = "tool_traces")
public class ToolTrace {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "message_id", nullable = false, length = 36)
    private String messageId; // FK → chat_messages.id (ASSISTANT)

    @Column(name = "conversation_id", nullable = false, length = 36)
    private String conversationId;

    @Column(name = "tool_name", nullable = false, length = 200)
    private String toolName;

    @Column(columnDefinition = "TEXT")
    private String arguments; // JSON string

    @Column(columnDefinition = "TEXT")
    private String result; // JSON string

    @Column(nullable = false, length = 20)
    private String status; // PENDING / RUNNING / SUCCESS / FAILED

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @PrePersist
    void prePersist() {
        if (startedAt == null)
            startedAt = Instant.now();
    }

    public ToolTrace() {}

    public ToolTrace(String id, String messageId, String conversationId, String toolName, String arguments,
            String status) {
        this.id = id;
        this.messageId = messageId;
        this.conversationId = conversationId;
        this.toolName = toolName;
        this.arguments = arguments;
        this.status = status;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public String getArguments() {
        return arguments;
    }

    public void setArguments(String arguments) {
        this.arguments = arguments;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }
}
