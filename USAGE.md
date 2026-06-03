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

### 核心能力

- 多轮对话 (SSE 流式) + DeepSeek R1 推理过程可视化
- 知识库管理：上传、搜索、分页、类型筛选、批量操作
- RAG 检索增强：BGE-M3 语义检索 + 引用来源展示
- 四大 Trace 追溯：ChatMemory / ReasoningTrace / RetrievalTrace / ToolTrace
- 开发调试工具：RAG 检索调试面板、Markdown 渲染测试

---

## 环境要求

| 软件 | 最低版本 | 说明 |
|------|---------|------|
| JDK | 21+ | 推荐 BellSoft LibericaJDK 21 |
| Maven | 3.9+ | 或使用项目自带 mvnw |
| Node.js | 20+ | 前端构建 |
| Docker | 24+ | 运行基础设施容器 |
| 内存 | 16GB+ | TEI ~4GB，Milvus ~2GB |

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

**服务端口一览**：

| 服务 | 端口 | 说明 |
|------|------|------|
| MySQL | 3306 | 关系数据库 |
| etcd | 2379 | Milvus 元数据存储 |
| Milvus | 19530 | 向量数据库 gRPC |
| MinIO S3 | 9000 | 对象存储 API |
| MinIO Console | 9001 | 对象存储 Web 管理 |
| TEI BGE-M3 | 8081 | Embedding 服务 |
| Milvus Attu | 8000 | 向量数据库 Web 管理 |
| Backend | 8080 | Spring Boot API |
| Frontend | 5173 | Vite 开发服务器 |

首次启动 TEI 需下载 BGE-M3 模型（约 2.2GB），等待 3-5 分钟。正常重启使用 `docker compose down && docker compose up -d`（不加 -v），模型保留在 tei_data 卷中。

### 3. 中间件管理界面

| 工具 | 地址 | 说明 |
|------|------|------|
| MinIO Console | http://localhost:9001 | 对象存储管理，浏览/上传/删除文件 |
| Milvus Attu | http://localhost:8000 | 向量数据库管理，查看 Collection、数据、索引 |

**MinIO Console**：账号 `minioadmin`，密码 `minioadmin`。登录后可查看 `dragon-agent` bucket 中存储的所有文档。

**Milvus Attu**：首次打开填写 Milvus 连接地址 `localhost:19530`，点击连接即可查看 `vector_store` Collection 中的向量数据和索引状态。

### 4. 启动后端

```bash
cd agent
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

生产环境使用 `-Dspring-boot.run.profiles=prod`，所有敏感值通过环境变量注入。

### 5. 启动前端

```bash
cd agent-ui
npm install
npm run dev
```

访问 `http://localhost:5173`，注册账号后即可使用。

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
| AUTH_TOKEN_SECRET | AUTH_TOKEN HMAC 签名密钥 | dragon-agent-default-secret-change-in-production |

### 应用配置参数

```yaml
app:
  rag:
    chunk-size: 512            # 分块大小 (tokens)
    chunk-overlap: 50          # 分块重叠 (tokens)
    top-k: 5                   # 检索返回数
    similarity-threshold: 0.2  # BGE-M3 相似度阈值 (0.2-0.5)
  embedding:
    tei:
      base-url: http://localhost:8081/v1  # TEI BGE-M3 服务地址
```

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

### reasoning_traces（推理过程追溯）
| 字段 | 类型 | 说明 |
|------|------|------|
| id | VARCHAR(36) PK | UUID |
| message_id | VARCHAR(36) | 关联 AI 消息 ID |
| conversation_id | VARCHAR(36) | 会话 ID |
| content | TEXT | DeepSeek R1 思考过程文本 |
| created_at | TIMESTAMP | 创建时间 |

### retrieval_traces（检索追溯）
| 字段 | 类型 | 说明 |
|------|------|------|
| id | VARCHAR(36) PK | UUID |
| message_id | VARCHAR(36) | 关联用户消息 ID |
| conversation_id | VARCHAR(36) | 会话 ID |
| document_name | VARCHAR(500) | 文档名 |
| chunk_index | INT | 分块序号 |
| score | DOUBLE | BGE-M3 相似度分数 |
| content_snippet | TEXT | 片段内容 |
| created_at | TIMESTAMP | 创建时间 |

### tool_traces（工具调用追溯，预留 MCP/Function Calling）
| 字段 | 类型 | 说明 |
|------|------|------|
| id | VARCHAR(36) PK | UUID |
| message_id | VARCHAR(36) | 关联消息 ID |
| conversation_id | VARCHAR(36) | 会话 ID |
| tool_name | VARCHAR(200) | 工具名 |
| arguments | TEXT | 参数 JSON |
| result | TEXT | 结果 JSON |
| status | VARCHAR(20) | PENDING / RUNNING / SUCCESS / FAILED |
| started_at | TIMESTAMP | 开始时间 |
| finished_at | TIMESTAMP | 结束时间 |

### documents（知识库文档）
| 字段 | 类型 | 说明 |
|------|------|------|
| id | VARCHAR(36) PK | UUID |
| user_id | BIGINT | 上传者 |
| conversation_id | VARCHAR(36) | 关联会话（可空） |
| original_name | VARCHAR(500) | 原始文件名 |
| stored_path | VARCHAR(1000) | MinIO 对象 Key |
| file_size | BIGINT | 字节数 |
| mime_type | VARCHAR(200) | MIME 类型 |
| chunk_count | INT | 分块数量 |
| status | ENUM | UPLOADING → PARSING → INDEXING → READY / FAILED |
| error_message | VARCHAR(2000) | 失败原因 |
| created_at | TIMESTAMP | 创建时间 |

### 索引设计
- `documents`: idx_doc_user_id, idx_doc_conversation_id, idx_doc_status, idx_doc_user_status
- `chat_messages`: idx_msg_conversation (conversation_id, created_at)
- `retrieval_traces`: idx_rt_message, idx_rt_conversation

---

## REST API 参考

### 认证
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/auth/register | 注册 { username, password } |
| POST | /api/auth/login | 登录 { username, password } |
| POST | /api/auth/logout | 登出 |
| GET | /api/auth/me | 当前会话状态 |

认证方式：Cookie-based WebSession + AUTH_TOKEN HMAC Cookie 会话恢复。

### 对话
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/stream | SSE 流式对话 { message, conversationId, userMsgId, aiMsgId, enableRag } |
| POST | /api/chat | 同步对话，Header X-Conversation-Id |

SSE 事件类型：`thinking` (DeepSeek R1 推理), `content` (正文 token), `done` (检索文档列表)

### 会话管理
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/conversations | 会话列表 |
| GET | /api/conversations/{id} | 会话消息（含 reasoning + retrievalTraces） |
| DELETE | /api/conversations/{id} | 删除会话 |

### 文档管理
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/documents/upload | 上传 (multipart: file) |
| GET | /api/documents | 文档列表 |
| DELETE | /api/documents/{id} | 删除文档（清理 MinIO + Milvus + MySQL） |
| GET | /api/documents/{id}/download | 下载原始文件 |
| POST | /api/documents/{id}/retry | 重试失败文档 |

### RAG 调试（开发工具）
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/documents/test-retrieval | 检索测试 { query } → 返回 chunk 列表 + 分数 + 上下文 |
| POST | /api/documents/eval-dataset | 批量评测 { questions: [] } → 返回每个问题的检索结果 |

---

## 项目结构

```
dragon-agent/
├── docker-compose.yml              # MySQL + etcd + Milvus + MinIO + TEI
├── USAGE.md                        # 本文档
├── eval/                           # RAG 评测工具
│   ├── ragas_eval.py               # Ragas 评测脚本
│   ├── eval_questions.json         # 评测集模板
│   └── requirements.txt            # Python 依赖
├── agent/                          # Spring Boot 后端
│   ├── pom.xml
│   └── src/main/
│       ├── resources/
│       │   ├── application.yaml
│       │   ├── application-dev.yaml
│       │   ├── application-prod.yaml
│       │   └── config/ai.properties
│       └── java/com/dragon/agent/
│           ├── AgentApplication.java
│           ├── config/             # SecurityConfig, CorsConfig, MinioConfig, RagConfig
│           ├── controller/         # AuthController, ChatController, StreamController,
│           │                       # ConversationController, DocumentController
│           ├── dto/                # ChatRequest, DocumentResponse, AuthResponse, etc.
│           ├── entity/             # UserEntity, ConversationEntity, DocumentEntity,
│           │                       # MessageEntity, ReasoningTrace, RetrievalTrace, ToolTrace
│           ├── exception/          # GlobalExceptionHandler, UsernameAlreadyExistsException
│           ├── repository/         # JPA Repositories
│           ├── service/            # AiService, ConversationService, DocumentService,
│           │                       # ChunkingService, UserService, TokenService
│           │   ├── storage/        # FileStorageService, MinioFileStorageService
│           │   └── parser/         # DocumentParserService (Apache Tika)
│           └── support/            # SecurityHelper
└── agent-ui/                       # React 前端
    ├── vite.config.ts
    └── src/
        ├── main.tsx
        ├── App.tsx / App.css
        ├── api.ts                  # SSE 客户端 + REST API
        ├── types.ts                # 类型定义
        ├── auth.ts                 # 认证 API
        ├── hooks/                  # useAuth, useConversation
        └── components/
            ├── ActivityBar.tsx      # 左侧图标导航栏 (对话/知识库/工具)
            ├── Sidebar.tsx          # 侧边栏 (会话列表/知识库/开发工具)
            ├── ChatInput.tsx        # 聊天输入 (RAG 开关)
            ├── MessageBubble.tsx    # 消息气泡 (推理过程/检索来源)
            ├── KnowledgeBase.tsx    # 知识库管理 (上传/搜索/分页/批量)
            ├── LoginPage.tsx        # 登录/注册
            ├── QuestionNav.tsx      # 右侧问题导航
            ├── RagTest.tsx          # RAG 检索调试面板
            ├── MarkdownTest.tsx     # Markdown 渲染测试 (开发工具)
            ├── MarkdownRenderer.tsx # Markdown 渲染器
            ├── CodeBlock.tsx        # 代码块 (Shiki 高亮)
            └── MermaidBlock.tsx     # Mermaid 图表
```

---

## 使用指南

### 知识库管理

1. 点击左侧导航栏 **知识库** 进入管理页面
2. 拖拽或点击上传区域上传文档
3. 系统自动：Tika 解析 → TokenTextSplitter 分块 (512 tokens) → BGE-M3 向量化 → 存入 Milvus
4. 文件列表支持：搜索、类型筛选 (PDF/Word/Excel/PPT/文本)、按时间/名称/大小排序、分页、多选批量删除
5. 失败文档可点击 **重试** 重新处理

### 知识库对话

1. 切换到 **对话** 标签
2. 输入框底部 **知识库** 开关确保开启
3. 提问后系统自动从知识库检索相关文档片段
4. AI 回复底部展示检索到的本地文档来源
5. 关闭知识库开关则进入纯对话模式（不检索文档）

### RAG 检索调试

1. 点击左侧导航栏 **工具**（🔧）
2. 在侧边栏选择 **RAG 检索调试**
3. 输入查询文本查看：检索耗时、chunk 列表及分数、分数分布条、LLM 上下文、原始 JSON 响应
4. 点击 chunk 可展开查看完整内容和元数据

### Markdown 渲染测试

1. 在工具面板选择 **Markdown 渲染测试**
2. 预览 GFM Markdown、KaTeX 数学公式、Mermaid 图表、代码高亮的渲染效果

---

## RAG 评测

### 前端内置评测

在 RAG 检索调试面板中直接输入查询，查看检索结果和相似度分数。

### Ragas 评测

```bash
cd eval
pip install -r requirements.txt

# 准备评测集（编辑 eval_questions.json）
# 从浏览器获取 SESSION Cookie

python ragas_eval.py \
  --api-url http://localhost:8080 \
  --cookie "SESSION=xxx" \
  --eval-set eval_questions.json
```

评测指标：context_precision, context_recall, faithfulness, answer_relevancy。

---

## 配置 Profile

| Profile | JPA DDL | 数据源 | 日志级别 |
|---------|---------|--------|---------|
| dev | update (自动建表) | localhost:3306/dragon_agent | DEBUG |
| prod | validate (手动建表) | 环境变量 MYSQL_URL | INFO |

---

## 常见问题

**TEI 每次都要重新下载模型？**
正常重启不加 -v 不会删除数据卷。`docker compose down -v` 会清除所有数据（包括模型），仅在需要完全重置时使用。

**文档上传后检索不到？**
1. 确认 TEI 正常运行：`docker logs dragon-tei | grep Ready`
2. 使用 RAG 检索调试面板测试
3. 检查相似度阈值 `app.rag.similarity-threshold`（BGE-M3 推荐 0.2）

**如何升级 Embedding 模型？**
编辑 `agent/src/main/java/com/dragon/agent/config/RagConfig.java`，替换 TeiEmbeddingModel 中的模型名。或提供一个新的 EmbeddingModel Bean 并标注 @Primary。

**如何清空所有数据？**
`docker compose down -v && docker compose up -d`——删除所有数据卷后重建。
