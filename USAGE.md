# Dragon Agent — 企业级 AI 知识库平台使用说明

## 系统概述

Dragon Agent 是基于 Spring AI + DeepSeek 的企业级 RAG 智能对话平台，支持文档知识库管理、语义检索增强生成、多轮对话和推理过程可视化。

### 技术架构

| 层级 | 技术栈 |
|------|--------|
| 后端框架 | Spring Boot 4.0.6 + WebFlux (Netty) |
| AI 引擎 | Spring AI 2.0.0-M8 + DeepSeek v4-pro |
| 向量数据库 | Milvus 2.5.4 |
| 对象存储 | MinIO (S3 兼容) |
| 关系数据库 | MySQL 8.0 |
| 文档解析 | Apache Tika 3.1.0 |
| Embedding | BGE-M3 via Text Embeddings Inference (TEI) |
| 前端框架 | React 19 + TypeScript 6 + Vite 8 |

---

## 环境要求

| 软件 | 最低版本 | 说明 |
|------|---------|------|
| JDK | 21+ | 推荐 BellSoft LibericaJDK 21 |
| Maven | 3.9+ | 或使用项目自带 mvnw |
| Node.js | 20+ | 前端构建 |
| Docker | 24+ | 运行基础设施容器 |
| 内存 | 16GB+ | TEI 需约 4GB，Milvus 需约 2GB |

---

## 快速启动

### 1. 配置 API Key

编辑 `agent/src/main/resources/config/ai.properties`：

```properties
AI_API_KEY=sk-your-deepseek-api-key
AI_BASE_URL=https://api.deepseek.com
AI_MODEL=deepseek-v4-pro
```

### 2. 启动基础设施

```bash
cd dragon-agent
docker compose up -d
```

启动容器：MySQL (3306)、etcd (2379)、Milvus (19530)、MinIO (9000/9001)、TEI BGE-M3 (8081)。

首次启动 TEI 需下载 BGE-M3 模型（约 2.2GB），等待 3-5 分钟。

### 3. 启动后端

```bash
cd agent

# 开发环境（dev profile，自动建表）
mvn spring-boot:run -Dspring-boot.run.profiles=dev

# 生产环境（prod profile，JPA validate）
mvn spring-boot:run -Dspring-boot.run.profiles=prod
```

### 4. 启动前端

```bash
cd agent-ui
npm install
npm run dev
```

访问 `http://localhost:5173`。

---

## 环境变量

| 变量名 | 说明 | 默认值 |
|--------|------|--------|
| AI_API_KEY | DeepSeek API Key | - |
| AI_BASE_URL | DeepSeek API 地址 | https://api.deepseek.com |
| AI_MODEL | DeepSeek 模型名 | deepseek-v4-pro |
| MYSQL_PASSWORD | MySQL 密码 | root |
| MYSQL_URL | MySQL JDBC URL (prod) | - |
| MYSQL_USER | MySQL 用户名 (prod) | - |
| MILVUS_HOST | Milvus 地址 | localhost |
| MINIO_ENDPOINT | MinIO 地址 | http://localhost:9000 |
| MINIO_ACCESS_KEY | MinIO Access Key | minioadmin |
| MINIO_SECRET_KEY | MinIO Secret Key | minioadmin |
| TEI_BASE_URL | TEI Embedding 地址 | http://localhost:8081/v1 |
| CORS_ORIGINS | 允许的跨域域名 | http://localhost:5173 |
| AUTH_TOKEN_SECRET | HMAC 签名密钥 | - |

---

## 数据库表结构

### chat_messages（聊天消息）
| 字段 | 类型 | 说明 |
|------|------|------|
| id | VARCHAR(36) PK | UUID |
| conversation_id | VARCHAR(36) | 会话 ID |
| role | VARCHAR(10) | USER / ASSISTANT |
| content | TEXT | 消息正文 |
| created_at | TIMESTAMP | 创建时间 |

### reasoning_traces（推理过程）
| 字段 | 类型 | 说明 |
|------|------|------|
| id | VARCHAR(36) PK | UUID |
| message_id | VARCHAR(36) | 关联 AI 消息 ID |
| conversation_id | VARCHAR(36) | 会话 ID |
| content | TEXT | 推理思考文本 |
| created_at | TIMESTAMP | 创建时间 |

### retrieval_traces（检索追溯）
| 字段 | 类型 | 说明 |
|------|------|------|
| id | VARCHAR(36) PK | UUID |
| message_id | VARCHAR(36) | 关联用户消息 ID |
| conversation_id | VARCHAR(36) | 会话 ID |
| document_name | VARCHAR(500) | 文档名 |
| chunk_index | INT | 分块序号 |
| score | DOUBLE | 相似度分数 |
| content_snippet | TEXT | 片段内容 |
| created_at | TIMESTAMP | 创建时间 |

### tool_traces（工具调用追溯，预留）
| 字段 | 类型 | 说明 |
|------|------|------|
| id | VARCHAR(36) PK | UUID |
| message_id | VARCHAR(36) | 关联消息 ID |
| conversation_id | VARCHAR(36) | 会话 ID |
| tool_name | VARCHAR(200) | 工具名 |
| arguments | TEXT | 参数 JSON |
| result | TEXT | 结果 JSON |
| status | VARCHAR(20) | PENDING / SUCCESS / FAILED |
| started_at | TIMESTAMP | 开始时间 |
| finished_at | TIMESTAMP | 结束时间 |

### documents（知识库文档）
| 字段 | 类型 | 说明 |
|------|------|------|
| id | VARCHAR(36) PK | UUID |
| user_id | BIGINT | 上传者 |
| original_name | VARCHAR(500) | 原始文件名 |
| stored_path | VARCHAR(1000) | MinIO 对象 Key |
| file_size | BIGINT | 字节数 |
| mime_type | VARCHAR(200) | MIME 类型 |
| chunk_count | INT | 分块数量 |
| status | VARCHAR(50) | UPLOADING/PARSING/INDEXING/READY/FAILED |
| error_message | VARCHAR(2000) | 错误信息 |
| created_at | TIMESTAMP | 创建时间 |

---

## REST API 参考

### 认证接口

**POST /api/auth/register** — 注册
```
Request:  { "username": "...", "password": "..." }
Response: { "username": "...", "message": "注册成功" }
```

**POST /api/auth/login** — 登录
```
Request:  { "username": "...", "password": "..." }
Response: { "username": "...", "message": "登录成功" }
```
认证方式：Cookie-based Session + AUTH_TOKEN HMAC Cookie

### 对话接口

**POST /api/stream** — SSE 流式对话
```
Request:  { "message", "conversationId", "userMsgId", "aiMsgId", "enableRag" }
SSE:      event:thinking (推理过程) / event:content (正文) / event:done (检索文档)
```

**POST /api/chat** — 同步对话
```
Response Header: X-Conversation-Id
Response Body:   AI 回复纯文本
```

### 会话管理

**GET /api/conversations** — 会话列表
**GET /api/conversations/{id}** — 会话消息（含 reasoning 和检索追溯）
**DELETE /api/conversations/{id}** — 删除会话

### 文档管理

**POST /api/documents/upload** (multipart/form-data) — 上传文档
**GET /api/documents** — 文档列表
**DELETE /api/documents/{id}** — 删除文档
**GET /api/documents/{id}/download** — 下载文档
**POST /api/documents/{id}/retry** — 重试失败文档

---

## 项目结构

```
dragon-agent/
├── docker-compose.yml
├── USAGE.md
├── agent/                          # Spring Boot 后端
│   ├── pom.xml
│   └── src/main/java/com/dragon/agent/
│       ├── AgentApplication.java
│       ├── config/                 # Security, CORS, MinIO, RAG
│       ├── controller/             # Auth, Chat, Stream, Conversation, Document
│       ├── dto/                    # ChatRequest, DocumentResponse, etc.
│       ├── entity/                 # Message, Document, ReasoningTrace, etc.
│       ├── repository/             # JPA Repositories
│       ├── service/                # AiService, DocumentService, etc.
│       │   ├── storage/            # MinIO 文件服务
│       │   ├── parser/             # Tika 文档解析
│       │   └── embedding/          # Embedding 服务
│       └── support/                # SecurityHelper
└── agent-ui/                       # React 前端
    └── src/
        ├── api.ts / types.ts
        ├── App.tsx
        ├── hooks/
        └── components/             # ActivityBar, Sidebar, ChatInput, MessageBubble, KnowledgeBase, etc.
```

---

## 知识库使用流程

1. 点击左侧 ActivityBar 的 **知识库** 图标进入知识库页面
2. 拖拽或点击上传文档（PDF、Word、Excel、PPT、TXT、Markdown 等）
3. 系统自动解析 → 分块 → BGE-M3 向量化 → 存入 Milvus
4. 切换到 **对话** 标签，开启新对话
5. 输入问题时确保输入框底部 **知识库** 开关开启
6. AI 回复底部展示检索到的本地文档来源

---

## 常见问题

**TEI 每次都要重新下载模型？**
正常重启使用 `docker compose down && docker compose up -d`（不加 -v），模型保留在 tei_data 卷中。

**文档上传后检索不到？**
确认 TEI 服务运行正常（`docker logs dragon-tei | grep Ready`），检查检索阈值 `app.rag.similarity-threshold`（BGE-M3 推荐 0.2-0.3）。

**如何清空所有数据？**
`docker compose down -v` 删除所有数据卷，然后 `docker compose up -d` 重建。
