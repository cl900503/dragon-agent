# Dragon Agent

企业级 RAG 智能对话平台，基于 Spring Boot 4 + React 19 + DeepSeek + BGE-M3，支持知识库管理、语义检索增强生成、多轮对话和推理过程可视化。

## 特性

- **RAG 知识库**：文档上传 → Tika 解析 → TokenTextSplitter 分块 → BGE-M3 向量化 → Milvus 语义检索
- **流式对话**：SSE 逐 token 推送 + DeepSeek R1 推理过程实时展示
- **Markdown 渲染**：GFM、KaTeX 数学公式、Mermaid 图表（流程图、时序图、类图）、Shiki 代码高亮
- **四大 Trace 追溯**：ChatMemory / ReasoningTrace / RetrievalTrace / ToolTrace 完整审计链路
- **用户认证**：注册/登录/退出，BCrypt 加密，WebSession + AUTH_TOKEN HMAC 双重会话管理
- **企业级基础设施**：MinIO 对象存储 + Milvus 向量数据库 + MySQL 关系数据库 + Docker Compose 一键部署
- **开发工具**：RAG 检索调试面板、Markdown 渲染测试

## 技术栈

| 层级 | 技术 | 版本 |
|------|------|------|
| 后端框架 | Spring Boot (WebFlux + Netty) | 4.0.6 |
| AI 引擎 | Spring AI + DeepSeek | 2.0.0-M8 |
| Embedding | BGE-M3 via TEI | latest |
| 向量数据库 | Milvus | 2.5.4 |
| 对象存储 | MinIO (S3 兼容) | latest |
| 关系数据库 | MySQL | 8.0 |
| 文档解析 | Apache Tika | 3.1.0 |
| 安全 | Spring Security (WebFlux) | 4.0.6 |
| 前端框架 | React + Vite + TypeScript | 19 / 8 / 6 |
| Markdown | react-markdown + Shiki + KaTeX + Mermaid | — |

## 快速开始

### 环境要求

JDK 21+ · Node.js 20+ · Docker 24+ · 内存 16GB+

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

启动 MySQL、etcd、Milvus、MinIO、TEI (BGE-M3)。首次需下载 BGE-M3 模型 (~2.2GB)。

### 3. 启动应用

```bash
# 后端 (dev profile，自动建表)
cd agent && ./mvnw spring-boot:run

# 前端
cd agent-ui && npm install && npm run dev
```

访问 `http://localhost:5173`。

## 项目结构

```
dragon-agent/
├── docker-compose.yml          # 基础设施编排（MySQL + etcd + Milvus + MinIO + TEI + Attu）
├── USAGE.md                    # 详细使用文档
├── README.md                   # 本文档
├── agent/                      # Spring Boot 后端
│   └── src/main/java/com/dragon/agent/
│       ├── config/             # Security, CORS, MinIO, RAG
│       ├── controller/         # Auth, Chat, Stream, Conversation, Document
│       ├── dto/                # 数据传输对象
│       ├── entity/             # JPA 实体 (含四大 Trace)
│       ├── repository/         # JPA Repository
│       ├── service/            # AiService, DocumentService, ChunkingService
│       │   ├── storage/        # MinIO 文件服务
│       │   └── parser/         # Tika 文档解析
│       └── support/            # SecurityHelper
└── agent-ui/                   # React 前端
    └── src/
        ├── api.ts / types.ts
        ├── hooks/              # useAuth, useConversation
        └── components/         # ActivityBar, Sidebar, ChatInput, MessageBubble,
                                # KnowledgeBase, RagTest, MarkdownTest, etc.
```

## 文档

详细的 API 接口、SSE 协议、环境变量、数据库表结构、RAG 评测方案请参见 [USAGE.md](./USAGE.md)。

## License

[Apache License 2.0](./LICENSE)
