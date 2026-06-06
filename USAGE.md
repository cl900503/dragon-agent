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
│       │   ├── rag/               # RAG 基础设施（BgeM3Client, HybridSearchService, RerankService 等）
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
| rag_search_logs | 检索日志 |

## 安全配置

1. **AUTH_TOKEN_SECRET**：生产环境必须设置 16 字符以上随机字符串
2. **Cookie Secure**：生产环境设置 `app.auth.cookie-secure=true`
3. **Milvus 凭证**：通过环境变量 `MILVUS_USERNAME` / `MILVUS_PASSWORD` 注入
4. **ai.properties**：不纳入版本控制，已在 .gitignore 中排除

## 待上线

- 密码修改、操作审计日志、接口限流
- 结构化日志、大文件分片上传、文档处理异步化
- CI/CD 流水线、Flyway 数据库迁移
