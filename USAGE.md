# Dragon Agent 使用说明

## 环境要求

- JDK 21+、Maven 3.9+、Node.js 20+、Docker 24+
- Python 3.10+（BGE-M3 本地 Embedding 服务）

## 快速启动

### 1. 配置 AI 密钥

创建 `agent/src/main/resources/config/ai.properties`：

```properties
AI_API_KEY=你的DeepSeek API Key
AI_BASE_URL=https://api.deepseek.com
AI_MODEL=deepseek-chat
BGE_API_KEY=占位即可
```

此文件已在 .gitignore 中，不会被提交到版本控制。

### 2. 启动基础设施

```bash
docker compose up -d
```

### 3. 启动 BGE-M3 Embedding 服务

```bash
cd BGE-M3-server
pip install -r requirements.txt
python server.py
```

### 4. 启动后端

```bash
cd agent
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

### 5. 启动前端

```bash
cd agent-ui
npm install
npm run dev
```

访问 http://localhost:5173。数据库为空时首个注册用户自动成为系统管理员。

## Docker 服务

| 服务 | 端口 | 说明 |
|------|------|------|
| MySQL 8.0 | 3306 | 业务数据库 |
| Milvus 2.5.4 | 19530 | 向量数据库（Attu: 8000） |
| etcd | 2379 | Milvus 元数据 |
| MinIO | 9000/9001 | 对象存储 / 控制台 |
| TEI Reranker | 8082 | BGE-Reranker-v2-m3 重排序模型 |

BGE-M3 Embedding 服务由本地 Python 脚本提供，不依赖 Docker Compose（见下方 BGE-M3-server 说明）。

## BGE-M3-server

BGE-M3 Embedding 服务使用 FlagEmbedding 库本地运行，不依赖 Docker。

```bash
cd BGE-M3-server
pip install -r requirements.txt
python server.py
```

服务监听 `http://localhost:8081`，提供 `/embed` 端点，支持 dense 和 sparse 双路向量输出。

## 项目结构

```
├── docker-compose.yml             # 基础设施编排
├── agent/                         # Spring Boot 后端
│   └── src/main/java/com/dragon/agent/
│       ├── config/                # SecurityConfig, CorsConfig, AuthTokenWebFilter, MinioConfig
│       ├── controller/            # REST 控制器（薄层）
│       ├── dto/                   # 请求/响应 DTO
│       ├── entity/                # JPA 实体
│       ├── enums/                 # UserRole, KbVisibility, RagRating
│       ├── exception/             # 全局异常处理
│       ├── repository/            # JPA 仓库
│       ├── service/               # 业务服务
│       │   ├── rag/               # RAG 基础设施
│       │   │   ├── BgeM3Client.java           # 本地 BGE-M3 Embedding 客户端
│       │   │   ├── RewriteClient.java          # 查询改写专用 LLM 客户端（独立轻量模型）
│       │   │   ├── HybridSearchService.java   # Dense+Sparse+BM25 三路混合检索 + RRF 融合
│       │   │   ├── RagSearchService.java      # RAG 检索主流程（阈值过滤、Lost-in-Middle、内容去重）
│       │   │   ├── RerankService.java         # BGE-Reranker Cross-Encoder + MMR 多样性重排
│       │   │   ├── ChunkingService.java       # TokenTextSplitter + 滑动窗口 Overlap 分块
│       │   │   ├── SemanticChunker.java       # 语义结构感知分段（Markdown/段落边界自适应）
│       │   │   ├── QueryProcessor.java        # 查询意图分类 + LLM 改写 + 多路 RRF 融合
│       │   │   ├── QueryCacheService.java     # Embedding + 检索结果二级缓存
│       │   │   └── RagDebugService.java       # 管线调试服务（分步执行 + 轮询式逐步展示）
│       │   ├── storage/           # MinIO 文件存储
│       │   └── parser/            # Tika 文档解析
│       └── support/               # SecurityHelper
├── agent-ui/                      # React 前端
│   └── src/
│       ├── api/                   # 统一 API 层（client.ts, admin.ts, rag.ts）
│       ├── components/            # UI 组件
│       └── hooks/                 # useAuth, useConversation
└── BGE-M3-server/                 # BGE-M3 本地 Embedding 服务（Python + FlagEmbedding）
    ├── server.py
    └── requirements.txt
```

## 角色权限

### 系统管理员 (ADMIN)
- 管理所有部门和人员
- 创建任意可见性的知识库（PRIVATE / DEPARTMENT / COMPANY）
- 管理所有非私有知识库和文档

### 部门管理员 (DEPT_ADMIN)
- 管理本部门人员（创建 / 删除 / 改角色，不可提升为 ADMIN）
- 创建私有和部门可见的知识库
- 管理部门内的知识库和文档
- 不可向全公司知识库上传文档

### 普通用户 (USER)
- 仅查看本部门成员
- 仅能创建私有知识库
- 仅能向自己的私有知识库上传文档
- 对部门知识库和全公司知识库仅有查看权限

权限控制在后端接口层面实现，不依赖前端 UI 隐藏。

## 知识库权限矩阵

| 可见性 | 可查看 | 可上传 | 可管理 |
|--------|--------|--------|--------|
| PRIVATE | 仅创建者 | 创建者 | 创建者 |
| DEPARTMENT | 同部门 + ADMIN | 同部门 + ADMIN | 部门管理员 + ADMIN |
| COMPANY | 所有人 | ADMIN | ADMIN |

## REST API

### 认证
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/auth/register | 注册 |
| POST | /api/auth/login | 登录 |
| POST | /api/auth/logout | 登出 |
| GET | /api/auth/me | 会话检查 |

### 对话
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/stream | SSE 流式对话（enableRag 参数开启知识库检索） |
| GET | /api/conversations | 会话列表 |
| GET | /api/conversations/{id} | 会话消息历史 |
| DELETE | /api/conversations/{id} | 删除会话 |

### 文档
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/documents/upload | 上传文档（kbId 参数指定知识库） |
| GET | /api/documents | 文档列表（kbId 参数过滤） |
| DELETE | /api/documents/{id} | 删除文档 |
| POST | /api/documents/{id}/retry | 重试失败文档 |
| GET | /api/documents/{id}/download | 下载文档 |
| POST | /api/documents/test-retrieval | RAG 检索调试 |

### 知识库
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/kb | 知识库列表（按权限过滤） |
| POST | /api/kb | 创建知识库 |
| DELETE | /api/kb/{id} | 删除知识库 |

### 管理（ADMIN / DEPT_ADMIN）
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/admin/departments | 部门列表 |
| POST | /api/admin/departments | 创建部门 |
| PUT | /api/admin/departments/{id} | 重命名部门 |
| DELETE | /api/admin/departments/{id} | 删除部门 |
| GET | /api/admin/users | 人员列表 |
| POST | /api/admin/users | 新增人员 |
| DELETE | /api/admin/users/{id} | 删除人员 |
| PUT | /api/admin/users/{id}/role | 修改角色 |
| PUT | /api/admin/users/{id}/profile | 编辑资料 |

### RAG 检索质量
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/rag/feedback | 提交检索反馈（有用/无用） |
| GET | /api/rag/feedback/batch | 批量查询反馈状态 |
| GET | /api/rag/stats | 30 天统计（用户隔离） |
| GET | /api/rag/recent | 最近检索记录（用户隔离） |
| POST | /api/rag/debug | RAG 管线调试（一次性返回全部步骤） |
| POST | /api/rag/debug/start | 启动分步调试卷（返回 sessionId） |
| GET | /api/rag/debug/poll?sid= | 轮询调试卷进度（每步完成即时可见） |

## RAG 检索管线

```
用户Query → ①查询改写 → ②多路检索 → ③MMR去重 → ④Cross-Encoder精排 → ⑤阈值过滤 → ⑥上下文构建 → LLM生成
```

| 步骤 | 组件 | 耗时(参考) | 说明 |
|------|------|-----------|------|
| 查询改写 | `QueryProcessor` + `RewriteClient` | ~1.3s | 意图分类，短查询/模糊查询触发 LLM 改写 |
| 多路检索 | `HybridSearchService` | ~300ms | Dense(BGE-M3) + Sparse + BM25(`bm25_vector`) → RRF 融合 |
| 去重 | `RerankService` | <1ms | MMR (λ=0.7, Jaccard 3-gram) 缩小候选集 |
| 精排 | `RerankService` | ~600ms | Cross-Encoder(BGE-Reranker) 对去重后的候选精排 |
| 阈值过滤 | `RagSearchService` | <1ms | 过滤 score < `similarity-threshold`(0.2) |
| 上下文构建 | `RagPipelineService` | <5ms | Lost-in-Middle 重排 + 结构化引用 |

**统一管线**：`RagPipelineService` 是会话和调试的**唯一入口**，确保两端结果一致。调试页通过 polling 逐步骤展示中间数据，会话只取最终结果。

### 查询改写

改写使用独立的轻量模型（`RewriteClient` 直调 DeepSeek API），不占用对话推理模型：

- 模型：`deepseek-v4-flash`（可配置 `app.rag.rewrite-model`）
- 思考模式：已关闭（`thinking: disabled`）
- 超时：`HttpURLConnection` 直连，connect=3s + read=5s

### 查询改写触发条件

| 查询类型 | 字数 | 是否触发改写 |
|----------|------|-------------|
| SHORT_KEYWORD | ≤15 字或含"这个/上次/之前"等模糊指代 | ✅ 触发 |
| COMPARATIVE | 含"对比/区别/ vs "等 | ✅ 触发 |
| FACTUAL | 16-80 字，无模糊指代 | ❌ 跳过 |
| REASONING | >10 字，含"为什么/如何/分析" | ❌ 跳过 |

## 分块策略

上传的文档经过两级分块：

1. **SemanticChunker**：按文档结构（Markdown 标题、段落边界）做语义感知分段，根据文档类型自适应大小（PDF 长文 1024 token、短文本 256 token）
2. **ChunkingService**：TokenTextSplitter 按 token 数切分（默认 512 token），相邻块滑动窗口重叠 50 token

配置项：
```yaml
app.rag:
  chunk-size: 512              # 分块 token 数
  chunk-overlap: 50            # 重叠 token 数
  semantic-chunking: true      # 语义分块开关
  similarity-threshold: 0.2    # 相似度阈值
  rewrite-model: deepseek-v4-flash  # 改写专用模型
  search-limit: 20             # 检索候选数上限
```

## 环境变量

| 变量 | 说明 | 默认值 |
|------|------|--------|
| AI_API_KEY | DeepSeek API 密钥 | 必填 |
| AI_BASE_URL | DeepSeek API 地址 | 必填 |
| AI_MODEL | 模型名称 | 必填 |
| AUTH_TOKEN_SECRET | Token 签名密钥 | 生产必设（≥16 字符） |
| MYSQL_PASSWORD | MySQL 密码 | root |
| CORS_ORIGINS | 跨域域名 | http://localhost:5173 |
| MILVUS_USERNAME | Milvus 用户名 | root |
| MILVUS_PASSWORD | Milvus 密码 | Milvus |
| app.rag.rewrite-model | 查询改写模型 | deepseek-v4-flash |
| app.rerank.mmr-lambda | MMR 多样性参数 | 0.7 |
| app.rerank.mmr-enabled | MMR 去重开关 | true |
| app.cache.embedding.ttl-minutes | Embedding 缓存 TTL | 30 |
| app.cache.search.ttl-minutes | 检索结果缓存 TTL | 5 |

## 数据库表

| 表 | 说明 |
|----|------|
| users | 用户 |
| departments | 部门 |
| knowledge_bases | 知识库 |
| documents | 知识库文档 |
| conversations | 会话 |
| chat_messages | 聊天消息 |
| reasoning_traces | 推理追溯 |
| retrieval_traces | 检索追溯 |
| rag_feedback | 检索反馈 |
| rag_search_logs | 检索日志（每次检索记录 score/耗时/hit） |
| tool_traces | 工具调用追溯（MCP/Function Calling 预留） |

## 安全配置

1. **AUTH_TOKEN_SECRET**：生产环境必须设置 16 字符以上随机字符串
2. **Cookie Secure**：生产环境设置 `app.auth.cookie-secure=true`
3. **Milvus 凭证**：通过环境变量 `MILVUS_USERNAME` / `MILVUS_PASSWORD` 注入
4. **ai.properties**：不纳入版本控制，已在 .gitignore 中排除

## 待上线

- 密码修改、操作审计日志、接口限流
- 结构化日志、大文件分片上传、文档处理异步化
- CI/CD 流水线、Flyway 数据库迁移
