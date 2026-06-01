# Dragon Agent — 使用说明

## 项目简介

Dragon Agent 是一个企业级 AI 对话平台，后端基于 **Spring Boot 4 + Spring AI 2**，
前端基于 **React 19 + TypeScript + Vite**，通过标准 SSE 事件协议与 AI 模型交互，
支持流式对话、推理过程展示、Markdown 实时渲染和多会话管理。

核心特性：

- 流式 SSE 对话，三种标准事件类型（thinking / content / done）
- 用户注册/登录认证（Spring Security + BCrypt），多用户会话隔离
- 基于 Spring AI JdbcChatMemory 的消息持久化，数据存入 MySQL，重启不丢失
- 左侧会话列表（新建、切换、删除），右侧问题导航（快速跳转到历史提问）
- Markdown 实时渲染（GFM、LaTeX 数学公式、Mermaid 图表、语法高亮）
- 严格前后端分离，RESTful API + SSE，WebSession 认证

## 环境要求

| 组件 | 要求 | 说明 |
|------|------|------|
| JDK | 21+ | 编译和运行后端 |
| Maven | 3.9+ | 可使用项目自带的 `mvnw`，无需手动安装 |
| Node.js | 20+ | 编译和运行前端 |
| MySQL | 8.x | 用户数据、会话元数据和消息持久化 |
| Docker | 可选 | 快速启动 MySQL |

## 快速启动

### 1. 启动 MySQL

使用 Docker 快速启动：

```bash
docker run -d --name mysql-dragon \
  -e MYSQL_ROOT_PASSWORD=root \
  -e MYSQL_DATABASE=dragon_agent \
  -p 3306:3306 \
  mysql:8.0
```

或使用已有的 MySQL 实例，确保 `dragon_agent` 数据库存在（可通过 `createDatabaseIfNotExist=true` 自动创建）。

### 2. 配置 API Key

```bash
cd agent
cp src/main/resources/config/ai.properties.example \
   src/main/resources/config/ai.properties
```

编辑 `ai.properties`，填入 DeepSeek API Key：

```properties
AI_API_KEY=sk-your-deepseek-api-key
AI_BASE_URL=https://api.deepseek.com
AI_MODEL=deepseek-chat
```

`ai.properties` 已加入 `.gitignore`，不会被提交到版本库。

### 3. 启动后端

```bash
cd agent

# 开发环境（默认 dev profile）
./mvnw spring-boot:run

# 生产环境
./mvnw spring-boot:run -Dspring-boot.run.profiles=prod
```

后端运行在 `http://localhost:8080`，健康检查端点：`/actuator/health`。

### 4. 启动前端

```bash
cd agent-ui
npm install
npm run dev
```

前端开发服务器运行在 `http://localhost:5173`，Vite 自动代理 `/api` 请求到后端。

### 5. 开始使用

浏览器打开 `http://localhost:5173`：
1. 注册新账号（用户名 + 密码）
2. 自动登录，开始对话
3. 左侧会话列表管理多个会话
4. 退出登录后重新登录，历史会话和消息仍存在

开发模式下，顶部 "Markdown 测试" 按钮可查看所有支持的 Markdown 语法和 Mermaid 图表。

## 环境变量与配置

### 必需环境变量

| 变量 | 说明 | 示例 |
|------|------|------|
| `AI_API_KEY` | DeepSeek API Key | `sk-xxxx` |
| `AI_BASE_URL` | API 基础地址 | `https://api.deepseek.com` |
| `AI_MODEL` | 模型名称 | `deepseek-chat` |

### 可选环境变量

| 变量 | 说明 | 默认值 |
|------|------|--------|
| `MYSQL_PASSWORD` | MySQL 密码 | `root`（仅 dev profile） |
| `MYSQL_URL` | MySQL JDBC URL（prod profile 必需） | — |
| `MYSQL_USER` | MySQL 用户名（prod profile 必需） | — |
| `CORS_ORIGINS` | 允许的前端域名，逗号分隔 | `http://localhost:5173` |
| `PORT` | 服务端口（prod profile） | `8080` |

### Spring Profile

项目支持两个 profile：

**dev（默认）**：
- 使用 `application-dev.yaml`，包含本地 MySQL 连接信息
- `ddl-auto: update` 自动同步数据库 schema
- DEBUG 级日志，方便开发调试

**prod**：
- 使用 `application-prod.yaml`，所有数据库连接信息通过环境变量注入
- `ddl-auto: validate` 禁止自动建表
- INFO 级日志
- `PORT` 环境变量控制服务端口

切换 profile：

```bash
# 开发（默认，无需指定）
./mvnw spring-boot:run

# 生产
export MYSQL_URL=jdbc:mysql://prod-host:3306/dragon_agent
export MYSQL_USER=app_user
export MYSQL_PASSWORD=secret
./mvnw spring-boot:run -Dspring-boot.run.profiles=prod
```

## API 接口

### 认证接口

**POST /api/auth/register — 注册**

```
POST /api/auth/register
Content-Type: application/json

{ "username": "alice", "password": "123456" }

HTTP 201 Created
{ "username": "alice", "message": "注册成功" }
```

- 用户名已存在返回 409
- 注册成功自动登录，Set-Cookie 下发 SESSION

**POST /api/auth/login — 登录**

```
POST /api/auth/login
Content-Type: application/json

{ "username": "alice", "password": "123456" }

HTTP 200 OK
{ "username": "alice", "message": "登录成功" }
```

**POST /api/auth/logout — 退出登录**

```
POST /api/auth/logout

HTTP 200 OK
```

**GET /api/auth/me — 检查当前会话**

```
GET /api/auth/me

HTTP 200 OK
{ "username": "alice", "message": "已登录" }

HTTP 401 Unauthorized
{ "username": null, "message": "未登录" }
```

### AI 对话接口

**POST /api/chat — 同步对话**

等待 AI 完整回复后一次性返回。

```
POST /api/chat
Content-Type: application/json

{ "message": "你好，请介绍一下自己", "conversationId": "可选" }

HTTP 200 OK
Content-Type: text/plain
X-Conversation-Id: uuid-xxxx

你好！我是 Dragon Agent...
```

**POST /api/stream — 流式对话（SSE）**

通过 Server-Sent Events 逐 token 推送。

```
POST /api/stream
Content-Type: application/json

{ "message": "请用 Markdown 写一份报告", "conversationId": "可选" }
```

SSE 事件类型：

| 事件 | 含义 | 必选 |
|------|------|------|
| `event:thinking` | 推理模型的思考过程 token | 否 |
| `event:content` | 正文回复 token | 是 |
| `event:done` | 流结束信号 | 是 |

### 会话管理接口

所有会话操作按当前登录用户隔离，非属主返回 403。

**GET /api/conversations — 会话列表**

```
HTTP 200 OK
[
  { "id": "uuid-1", "title": "解释相对论" },
  { "id": "uuid-2", "title": "你好" }
]
```

**GET /api/conversations/{id} — 会话详情**

```
HTTP 200 OK
{
  "conversationId": "uuid-1",
  "messages": [...],
  "count": 2
}
```

**DELETE /api/conversations/{id} — 删除会话**

```
HTTP 200 OK
{ "conversationId": "uuid-1", "cleared": true, "timestamp": "..." }
```

### 健康检查

```
GET /actuator/health

HTTP 200 OK
{ "status": "UP" }
```

### 错误响应格式

```json
{
  "status": 400,
  "error": "Bad Request",
  "message": "message: 消息内容不能为空"
}
```

| 状态码 | 场景 |
|--------|------|
| 400 | 请求参数校验失败 |
| 401 | 未登录或认证失败 |
| 403 | 无权访问此会话 |
| 409 | 用户名已存在 |
| 500 | 服务端内部错误 |

## 数据库

启动后端后，以下数据表自动创建：

| 表 | 说明 | 管理方式 |
|----|------|----------|
| `users` | 用户账号信息，BCrypt 密码哈希 | JPA Entity |
| `conversations` | 会话元数据（归属用户、标题、创建时间） | JPA Entity |
| `SPRING_AI_CHAT_MEMORY` | 对话消息，Spring AI 自动管理 | JdbcChatMemory |

## 项目结构

```
dragon-agent/
├── agent/                                  # 后端 Spring Boot
│   ├── pom.xml
│   ├── .gitignore
│   └── src/main/
│       ├── java/com/dragon/agent/
│       │   ├── AgentApplication.java       # 启动入口
│       │   ├── config/
│       │   │   ├── CorsConfig.java         # CORS 配置
│       │   │   ├── CustomReactiveAuthenticationManager.java
│       │   │   └── SecurityConfig.java     # WebFlux Security 配置
│       │   ├── controller/
│       │   │   ├── AuthController.java     # 认证接口（注册/登录/退出）
│       │   │   ├── ChatController.java     # 同步对话接口
│       │   │   ├── ConversationController.java  # 会话管理接口
│       │   │   └── StreamController.java   # SSE 流式接口
│       │   ├── dto/
│       │   │   ├── AuthResponse.java       # 认证响应体
│       │   │   ├── ChatRequest.java        # 请求 DTO
│       │   │   ├── ErrorResponse.java      # 错误响应体
│       │   │   ├── LoginRequest.java       # 登录请求
│       │   │   └── RegisterRequest.java    # 注册请求
│       │   ├── entity/
│       │   │   ├── ConversationEntity.java # 会话实体
│       │   │   └── UserEntity.java         # 用户实体
│       │   ├── exception/
│       │   │   ├── GlobalExceptionHandler.java
│       │   │   └── UsernameAlreadyExistsException.java
│       │   ├── repository/
│       │   │   ├── ConversationRepository.java
│       │   │   └── UserRepository.java
│       │   ├── service/
│       │   │   ├── AiService.java          # AI 对话服务
│       │   │   ├── ConversationService.java  # 会话管理
│       │   │   └── UserService.java        # 用户服务
│       │   └── support/
│       │       └── SecurityHelper.java     # Security 工具组件
│       └── resources/
│           ├── application.yaml            # 基础配置（所有 profile 共享）
│           ├── application-dev.yaml        # 开发环境配置
│           ├── application-prod.yaml       # 生产环境配置模板
│           └── config/
│               ├── ai.properties.example   # 配置模板
│               └── ai.properties           # 实际配置（gitignore）
├── agent-ui/                               # 前端 React
│   ├── package.json
│   ├── vite.config.ts
│   ├── index.html
│   └── src/
│       ├── main.tsx                        # 应用入口
│       ├── App.tsx / App.css               # 主应用 + 布局
│       ├── index.css                       # 全局样式 + CSS 变量
│       ├── types.ts                        # TypeScript 类型定义
│       ├── api.ts                          # SSE 客户端 + 会话 API
│       ├── auth.ts                         # 认证 API 封装
│       ├── hooks/
│       │   ├── useAuth.ts                  # 认证状态 hook
│       │   └── useConversation.ts          # 会话管理 hook
│       └── components/
│           ├── ChatInput.tsx / .css        # 聊天输入框
│           ├── ChevronIcon.tsx             # 折叠/展开箭头图标（Sidebar/QuestionNav 共用）
│           ├── CodeBlock.tsx / .css        # 代码高亮（Shiki）
│           ├── LoginPage.tsx / .css        # 登录/注册页
│           ├── MarkdownRenderer.tsx        # 公共 Markdown 渲染组件
│           ├── MarkdownTest.tsx / .css     # 开发测试面板（DEV 模式）
│           ├── MermaidBlock.tsx / .css     # Mermaid 图表渲染
│           ├── MessageBubble.tsx / .css    # 消息气泡
│           ├── QuestionNav.tsx / .css      # 右侧问题导航
│           └── Sidebar.tsx / .css          # 左侧会话列表
├── .gitignore                              # 仓库级忽略规则
├── README.md
├── USAGE.md                                # 本文件
└── LICENSE                                 # Apache 2.0
```

## 多模型兼容

前后端通过标准 SSE 事件协议解耦，接入新模型时：

- 推理模型（有思考过程）：在 `AiService.extractReasoningContent()` 中追加对应的 `instanceof` 分支，前端无需改动
- 普通模型（无思考过程）：直接使用现有 `event:content` 通道
- 模型特有的类型判断封装在 Service 层，Controller 层只依赖 Spring AI 标准 API

## 构建与部署

### 后端

```bash
cd agent
./mvnw clean package -DskipTests
java -jar target/agent-0.0.1-SNAPSHOT.jar --spring.profiles.active=prod
```

### 前端

```bash
cd agent-ui
npm run build
# 静态文件输出到 dist/，可部署到 Nginx 或 CDN
```

生产环境建议使用 Nginx 反向代理统一前后端域名，通过 `CORS_ORIGINS` 环境变量配置允许的域名。

### 生产环境检查清单

- [ ] 设置 `spring.profiles.active=prod`
- [ ] 通过环境变量注入 `MYSQL_URL`、`MYSQL_USER`、`MYSQL_PASSWORD`
- [ ] 通过环境变量注入 `AI_API_KEY`、`AI_BASE_URL`、`AI_MODEL`
- [ ] 设置 `CORS_ORIGINS` 为生产前端域名
- [ ] 确保 `ddl-auto=validate`（禁止自动建表）
- [ ] 使用反向代理（Nginx）统一前后端域名
- [ ] 配置 HTTPS
