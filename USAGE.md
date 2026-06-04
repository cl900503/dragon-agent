# Dragon Agent 使用说明

## 系统概述

Dragon Agent 是企业级 AI 知识库平台，基于 Spring AI + DeepSeek + BGE-M3 构建，支持 RAG 文档检索增强对话、知识库权限管理、组织架构管理。

## 技术栈

| 组件 | 技术 |
|------|------|
| 后端 | Spring Boot 4.0.6 + WebFlux (Netty) |
| AI | Spring AI 2.0.0-M8 + DeepSeek |
| 向量库 | Milvus 2.5.4 |
| 对象存储 | MinIO (S3 兼容) |
| 数据库 | MySQL 8.0 |
| 文档解析 | Apache Tika 3.1.0 |
| Embedding | BGE-M3 via TEI (Text Embeddings Inference) |
| 前端 | React 19 + TypeScript + Vite |

## 环境要求

- JDK 21+, Maven 3.9+, Node.js 20+, Docker 24+
- 内存 16GB+（TEI 约 4GB，Milvus 约 2GB）

## 快速启动

### 1. 配置 AI 密钥

创建 `agent/src/main/resources/config/ai.properties`：

```properties
AI_API_KEY=sk-your-key
AI_BASE_URL=https://api.deepseek.com
AI_MODEL=deepseek-chat
```

### 2. 启动基础设施

```bash
docker compose up -d
```

### 3. 启动后端

```bash
cd agent
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

### 4. 启动前端

```bash
cd agent-ui
npm install
npm run dev
```

访问 http://localhost:5173。数据库为空时，首个注册用户自动成为系统管理员。

## 系统角色

### 系统管理员 (ADMIN)
- 管理所有部门和人员
- 创建任意可见性的知识库（PRIVATE / DEPARTMENT / COMPANY）
- 管理所有非私有知识库和文档
- 可向任意知识库上传文档

### 部门管理员 (DEPT_ADMIN)
- 管理本部门人员（创建 / 删除 / 改角色，不可提升为 ADMIN）
- 创建私有和部门可见的知识库
- 管理部门内的知识库和文档
- 不可向全公司知识库上传文档
- 不可管理全公司知识库下的文档
- 仅可见本部门

### 普通用户 (USER)
- 查看本部门成员
- 仅可编辑自己的个人信息（姓名 / 邮箱）
- 仅能创建私有知识库
- 仅能向自己的私有知识库上传文档
- 对部门知识库和全公司知识库仅有查看权限

## 知识库权限

| 可见性 | 可查看 | 可上传 | 可管理 |
|--------|--------|--------|--------|
| PRIVATE | 仅创建者 | 创建者 | 创建者 |
| DEPARTMENT | 同部门 + ADMIN | 同部门 + ADMIN | 部门管理员 + ADMIN |
| COMPANY | 所有人 | ADMIN | ADMIN |

## Docker 服务端口

| 服务 | 端口 | 管理界面 |
|------|------|---------|
| MySQL | 3306 | - |
| MinIO S3 | 9000 | Console: 9001 |
| Milvus | 19530 | Attu: 8000 |
| Etcd | 2379 | - |
| TEI BGE-M3 | 8081 | - |
| Backend | 8080 | Actuator: /actuator/health |
| Frontend | 5173 | - |

## 项目结构

```
agent/
  src/main/java/com/dragon/agent/
    config/          - SecurityConfig, CorsConfig, MinioConfig, AuthTokenWebFilter
    controller/      - REST API 控制器（Auth, Stream, Conversation, Document, KnowledgeBase, Admin）
    dto/             - 数据传输对象
    entity/          - JPA 实体
    exception/       - 全局异常处理
    repository/      - JPA 仓库
    service/         - 业务逻辑层（AiService, DocumentService, KnowledgeBaseService, AdminService）
      storage/       - 文件存储（MinIO）
      parser/        - 文档解析（Tika）
    support/         - SecurityHelper
  src/main/resources/
    config/          - ai.properties（不纳入版本控制）
    application*.yaml

agent-ui/
  src/
    components/      - React 组件
    hooks/           - useAuth, useConversation
    api.ts           - API 客户端
    auth.ts          - 认证模块
    types.ts         - 类型定义
```

## REST API

### 待上线清单
- 密码修改、操作审计日志、接口限流
- 结构化日志、健康检查完善、文档替换
- 大文件分片上传、文档处理异步化
- CI/CD 流水线、Flyway 数据库迁移

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
| POST | /api/stream | SSE 流式对话 |
| GET | /api/conversations | 会话列表 |
| GET | /api/conversations/:id | 会话详情 |
| DELETE | /api/conversations/:id | 删除会话 |

### 文档
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/documents/upload | 上传文档 |
| GET | /api/documents | 文档列表（支持 kbId 参数） |
| DELETE | /api/documents/:id | 删除文档 |
| POST | /api/documents/:id/retry | 重试失败文档 |
| GET | /api/documents/:id/download | 下载文档 |
| POST | /api/documents/test-retrieval | RAG 检索测试 |

### 知识库
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/kb | 知识库列表 |
| POST | /api/kb | 创建知识库 |
| DELETE | /api/kb/:id | 删除知识库 |

### RAG 检索质量
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/rag/feedback | 提交检索反馈 |
| GET | /api/rag/feedback/batch | 批量查询反馈状态 |
| GET | /api/rag/stats | 30 天检索统计 |
| GET | /api/rag/recent | 最近检索记录 |

### 管理
| 方法 | 路径 | 说明 |
|------|------|------|
| GET/POST/PUT/DELETE | /api/admin/departments | 部门管理 |
| GET/POST/DELETE | /api/admin/users | 人员管理 |
| PUT | /api/admin/users/:id/role | 修改角色 |
| PUT | /api/admin/users/:id/profile | 编辑个人信息 |

## 环境变量

| 变量 | 说明 | 默认值 |
|------|------|--------|
| AI_API_KEY | AI API 密钥 | - |
| AI_BASE_URL | AI API 地址 | - |
| AI_MODEL | AI 模型名称 | - |
| MYSQL_PASSWORD | MySQL 密码 | root |
| AUTH_TOKEN_SECRET | Token 签名密钥（生产必设） | - |
| CORS_ORIGINS | 跨域域名 | http://localhost:5173 |

## 数据库表

| 表 | 说明 |
|----|------|
| users | 用户 |
| departments | 部门 |
| conversations | 会话 |
| chat_messages | 聊天消息 |
| reasoning_traces | 推理追溯（DeepSeek R1） |
| retrieval_traces | 检索追溯（RAG） |
| tool_traces | 工具调用追溯（预留） |
| documents | 知识库文档 |
| knowledge_bases | 知识库 |
| rag_feedback | 检索反馈 |
| rag_search_logs | 检索日志 |

## 注意事项

1. 生产环境必须设置 AUTH_TOKEN_SECRET（32 位以上随机字符串）
2. ai.properties 不纳入版本控制
3. 首次启动 TEI 需下载 BGE-M3 模型（约 2.2GB，等待 3-5 分钟）
4. 清空所有数据：`docker compose down -v && docker compose up -d`
5. 保留模型仅清业务数据：手动 DROP DATABASE + 删除 MinIO bucket + 删除 Milvus collection
