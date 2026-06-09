# Dragon Agent

企业级 AI 知识库平台，基于 Spring AI + DeepSeek + BGE-M3，支持 RAG 文档检索增强对话、三级角色权限管理、组织架构管理。

## 特性

- RAG 知识库：上传 → Tika 解析 → 分块 → BGE-M3 向量化 → Milvus 语义检索
- 流式对话：SSE 逐 token 推送 + DeepSeek R1 推理过程实时展示
- 三级权限：ADMIN / DEPT_ADMIN / USER，后端接口层强制鉴权
- KB 可见性：PRIVATE / DEPARTMENT / COMPANY，部门归属创建时冻结
- 组织架构：部门管理 + 人员管理 + 表格展示 + 分页 + 弹窗编辑
- RAG 质量：检索反馈 + 检索日志 + 质量分析仪表盘 + 分块策略可配
- 检索追溯：ChatMemory / ReasoningTrace / RetrievalTrace 全链路记录
- 检索来源：片段详情展示 + 相似度百分比 + 按文档分组
- 开发工具：语义检索调试 / 检索质量分析 / 渲染效果预览

## 技术栈

| 层级 | 技术 |
|------|------|
| 后端框架 | Spring Boot 4.0.6 (WebFlux + Netty) |
| AI | Spring AI 2.0.0-M8 + DeepSeek |
| Embedding | BGE-M3 本地服务 |
| 向量数据库 | Milvus 2.5.4 |
| 对象存储 | MinIO (S3 兼容) |
| 数据库 | MySQL 8.0 |
| 文档解析 | Apache Tika 3.1.0 |
| 安全 | Spring Security (WebFlux) + Cookie + AuthToken |
| 前端 | React 19 + TypeScript + Vite |

## RAG 检索管线

```
用户Query → ①查询改写 → ②多路检索 → ③MMR去重 → ④Cross-Encoder精排 → ⑤阈值过滤 → ⑥上下文构建 → LLM生成
```

| 阶段 | 组件 | 说明 |
|------|------|------|
| 查询处理 | `QueryProcessor` + `RewriteClient` | 意图分类 + 独立轻量模型改写（`deepseek-v4-flash` 非思考模式） |
| 文档分块 | `SemanticChunker` + `ChunkingService` | 语义结构感知分段 → TokenTextSplitter + 滑动窗口重叠 |
| 混合检索 | `HybridSearchService` | Dense(BGE-M3) + Sparse(BGE-M3) + BM25 三路召回，RRF 融合 |
| 去重重排 | `RerankService` | MMR 多样性去重 → Cross-Encoder(BGE-Reranker) 精排 |
| 阈值过滤 | `RagSearchService` | `similarity-threshold` 过滤低分文档 |
| 上下文构建 | `RagPipelineService` | Lost-in-Middle 重排 + 结构化引用 `[N] 文档名 (相关度: XX%)` |
| 统一管线 | `RagPipelineService` | 会话和调试共享同一入口，确保结果一致 |
| 检索缓存 | `QueryCacheService` | Embedding 缓存(30min) + 改写缓存(10min) + 检索缓存(5min) |

## 快速开始

```bash
# 1. 配置 AI 密钥
# 编辑 agent/src/main/resources/config/ai.properties

# 2. 启动基础设施
docker compose up -d

# 3. 启动 BGE-M3 Embedding
cd BGE-M3-server && pip install -r requirements.txt && python server.py

# 4. 启动后端
cd agent && mvn spring-boot:run -Dspring-boot.run.profiles=dev

# 5. 启动前端
cd agent-ui && npm install && npm run dev
```

访问 http://localhost:5173，首次注册自动成为系统管理员。

## 系统角色

| 角色 | 权限概要 |
|------|---------|
| ADMIN | 管理所有部门和人员，创建任意可见性 KB，管理所有非私有 KB |
| DEPT_ADMIN | 管理本部门人员，创建 PRIVATE/DEPARTMENT KB，不可操作 COMPANY KB |
| USER | 查看本部门，仅创建 PRIVATE KB，对 DEPARTMENT/COMPANY KB 只读 |

## 项目结构

```
├── docker-compose.yml
├── agent/                          # Spring Boot 后端
│   └── src/main/java/com/dragon/agent/
│       ├── config/                 # 安全配置、CORS、MinIO
│       ├── controller/             # REST 控制器（薄层）
│       ├── dto/                    # 请求/响应 DTO
│       ├── entity/                 # JPA 实体
│       ├── enums/                  # UserRole, KbVisibility, RagRating
│       ├── exception/              # 全局异常处理
│       ├── repository/             # JPA 仓库
│       ├── service/                # 业务服务
│       │   ├── rag/                # RAG：ChunkingService, SemanticChunker, HybridSearchService
│       │   │                       #      RagSearchService, RerankService, BgeM3Client
│       │   │                       #      QueryProcessor(查询改写), QueryCacheService(检索缓存)
│       │   ├── storage/            # MinIO 文件存储
│       │   └── parser/             # Tika 文档解析
│       └── support/                # 工具类
├── agent-ui/                       # React 前端
│   └── src/
│       ├── api/                    # 统一 API 层
│       ├── components/             # UI 组件
│       └── hooks/                  # useAuth, useConversation
└── BGE-M3-server/                  # BGE-M3 本地 Embedding 服务
    ├── server.py
    └── requirements.txt
```

详细文档见 [USAGE.md](./USAGE.md)。
