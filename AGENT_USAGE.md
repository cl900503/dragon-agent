# Dragon Agent 使用说明

企业级 RAG 知识库对话系统，基于 Spring Boot 4.x + WebFlux + Milvus + BGE-M3 构建。

## 快速开始

### 环境要求
- JDK 21+、Maven 3.9+
- Node.js 20+、npm 10+
- Docker & Docker Compose
- Python 3.10+（仅 BGE-M3 本地服务）

### 1. 启动基础设施

```bash
docker compose up -d
```

启动后服务列表：

| 服务 | 端口 | 说明 |
|------|------|------|
| MySQL 8.0 | 3306 | 业务数据库 |
| Milvus 2.5.4 | 19530 | 向量数据库 |
| etcd | 2379 | Milvus 元数据 |
| MinIO | 9000/9001 | 对象存储 |
| TEI Reranker | 8082 | BGE-Reranker 模型 |

### 2. 启动 BGE-M3 Embedding 服务

```bash
cd BGE-M3-server
pip install -r requirements.txt
python server.py
```

服务监听 `http://localhost:8081/embed`。

### 3. 配置密钥

创建 `agent/src/main/resources/config/ai.properties`：

```properties
AI_API_KEY=你的DeepSeek API Key
AI_BASE_URL=https://api.deepseek.com
AI_MODEL=deepseek-chat
BGE_API_KEY=（BGE 服务不需要此 key，保留占位即可）
```

**此文件已在 .gitignore 中，不会被提交到版本控制。**

### 4. 启动后端

```bash
cd agent
mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

后端监听 `http://localhost:8080`。

### 5. 启动前端

```bash
cd agent-ui
npm install
npm run dev
```

前端监听 `http://localhost:5173`。

## 项目结构

```
dragon-agent/
├── agent/                    # Spring Boot 后端
│   └── src/main/java/com/dragon/agent/
│       ├── config/           # 安全、CORS、MinIO 配置
│       ├── controller/       # REST 控制器（薄层，仅路由）
│       ├── dto/              # 请求/响应 DTO
│       ├── entity/           # JPA 实体
│       ├── enums/            # 枚举（UserRole, KbVisibility 等）
│       ├── exception/        # 全局异常处理
│       ├── repository/       # JPA 仓库
│       ├── service/          # 业务服务
│       │   ├── rag/          # RAG 基础设施（Embedding/Search/Rerank）
│       │   ├── parser/       # 文档解析（Tika）
│       │   └── storage/      # 文件存储（MinIO）
│       └── support/          # 工具类
├── agent-ui/                 # React 前端
│   └── src/
│       ├── api/              # 统一 API 层
│       ├── components/       # UI 组件
│       └── hooks/            # 自定义 Hook
├── BGE-M3-server/            # BGE-M3 本地 Embedding 服务
└── docker-compose.yml        # 基础设施编排
```

## API 接口

### 认证
- `POST /api/auth/login` — 登录
- `POST /api/auth/register` — 注册（首用户自动 ADMIN）
- `GET /api/auth/me` — 会话检查
- `POST /api/auth/logout` — 登出

### 对话
- `POST /api/stream` — SSE 流式对话（支持 `enableRag` 参数）
- `GET /api/conversations` — 会话列表
- `GET /api/conversations/{id}` — 会话消息历史
- `DELETE /api/conversations/{id}` — 删除会话

### 文档管理
- `POST /api/documents/upload` — 上传文档（`?kbId=` 指定知识库）
- `GET /api/documents` — 文档列表（`?kbId=` 过滤）
- `DELETE /api/documents/{id}` — 删除文档
- `POST /api/documents/{id}/retry` — 重试失败文档
- `GET /api/documents/{id}/download` — 下载文档
- `POST /api/documents/test-retrieval` — RAG 检索调试

### 知识库管理
- `GET /api/kb` — 知识库列表
- `POST /api/kb` — 创建知识库
- `DELETE /api/kb/{id}` — 删除知识库

### 组织架构（ADMIN 权限）
- `GET /api/admin/departments` — 部门列表
- `POST /api/admin/departments` — 创建部门
- `PUT /api/admin/departments/{id}` — 重命名部门
- `DELETE /api/admin/departments/{id}` — 删除部门
- `GET /api/admin/users` — 人员列表
- `POST /api/admin/users` — 新增人员
- `DELETE /api/admin/users/{id}` — 删除人员
- `PUT /api/admin/users/{id}/role` — 修改角色
- `PUT /api/admin/users/{id}/profile` — 修改资料

### RAG 质量
- `POST /api/rag/feedback` — 提交反馈
- `GET /api/rag/feedback/batch?ids=` — 批量查询反馈
- `GET /api/rag/stats` — 检索统计（30 天，用户隔离）
- `GET /api/rag/recent` — 最近检索（用户隔离）

## 角色权限模型

| 角色 | 权限范围 |
|------|----------|
| **ADMIN**（系统管理员） | 全部权限：管理组织架构、人员、知识库、文档 |
| **DEPT_ADMIN**（部门管理员） | 管理本部门人员、知识库和文档 |
| **USER**（普通用户） | 管理自己的文档、访问有权限的知识库 |

所有权限控制在后端接口层面实现，不依赖前端 UI 隐藏。

## 环境变量

| 变量 | 说明 | 默认值 |
|------|------|--------|
| `AI_API_KEY` | DeepSeek API 密钥 | 必填 |
| `AI_BASE_URL` | DeepSeek API 地址 | 必填 |
| `AI_MODEL` | 模型名称 | 必填 |
| `AUTH_TOKEN_SECRET` | Token 签名密钥 | 生产环境必填（≥16 字符） |
| `MYSQL_PASSWORD` | MySQL 密码 | root |
| `MILVUS_HOST` | Milvus 地址 | localhost |
| `MILVUS_PORT` | Milvus 端口 | 19530 |
| `MINIO_ACCESS_KEY` | MinIO 访问密钥 | minioadmin |
| `MINIO_SECRET_KEY` | MinIO 密钥 | minioadmin |
| `CORS_ORIGINS` | 允许的前端域名 | http://localhost:5173 |

## 安全配置

1. **AUTH_TOKEN_SECRET**：生产环境必须设置为 16 字符以上随机字符串
2. **Cookie Secure 标志**：生产环境设置 `app.auth.cookie-secure=true`
3. **Milvus 密码**：通过 `MILVUS_USERNAME` / `MILVUS_PASSWORD` 从环境变量读取
4. **HTTPS**：生产部署建议使用反向代理（Nginx/Caddy）配置 TLS

## 技术栈

- Spring Boot 4.0.6 / WebFlux / Security / Data JPA
- Spring AI 2.0.0-M8（DeepSeek / OpenAI Embedding）
- Milvus 2.5.4（Hybrid Search: Dense + Sparse + RRF）
- BGE-M3（Embedding）+ BGE-Reranker-v2-m3（重排序）
- MySQL 8.0 / MinIO / Apache Tika
- React 19 + TypeScript + Vite + Shiki + Mermaid + KaTeX
