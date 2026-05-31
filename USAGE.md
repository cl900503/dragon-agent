# Dragon Agent — 使用说明

## 项目简介

Dragon Agent 是一个企业级 AI 对话平台，后端基于 **Spring Boot 4 + Spring AI 2**，
前端基于 **React 19 + TypeScript + Vite**，通过标准 SSE 事件协议与 AI 模型交互，
支持流式对话、推理过程展示、Markdown 实时渲染和多会话管理。

核心特性：

- 流式 SSE 对话，三种标准事件类型（thinking / content / done）
- 基于 Spring AI Chat Memory 的多轮对话记忆，会话自动持久化
- 左侧会话列表（新建、切换、删除），右侧问题导航（快速跳转到历史提问）
- Markdown 实时渲染（GFM、LaTeX 数学公式、Mermaid 图表、语法高亮）
- 严格前后端分离，RESTful API + SSE，无状态通信

## 环境要求

- JDK 21 或更高
- Maven 3.9+（可使用项目自带的 `mvnw`，无需手动安装）
- Node.js 20+ 和 npm

## 快速启动

### 1. 配置 API Key

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

### 2. 启动后端

```bash
cd agent
./mvnw spring-boot:run
```

后端运行在 `http://localhost:8080`，健康检查端点：`/actuator/health`。

### 3. 启动前端

```bash
cd agent-ui
npm install
npm run dev
```

前端开发服务器运行在 `http://localhost:5173`，Vite 自动代理 `/api` 请求到后端。

### 4. 开始对话

浏览器打开 `http://localhost:5173`，输入消息即可开始对话。

开发模式下，顶部 "Markdown 测试" 按钮可查看所有支持的 Markdown 语法和 Mermaid 图表。

## API 接口

### POST /api/chat — 同步对话

等待 AI 完整回复后一次性返回。

**请求：**

```
POST /api/chat
Content-Type: application/json

{ "message": "你好，请介绍一下自己", "conversationId": "可选" }
```

**响应：**

```
HTTP 200 OK
Content-Type: text/plain
X-Conversation-Id: uuid-xxxx

你好！我是 Dragon Agent...
```

### POST /api/stream — 流式对话（SSE）

通过 Server-Sent Events 逐 token 推送，实现打字机效果。

**请求：**

```
POST /api/stream
Content-Type: application/json

{ "message": "请用 Markdown 写一份报告", "conversationId": "可选" }
```

**SSE 事件类型：**

| 事件 | 含义 | 必选 |
|------|------|------|
| `event:thinking` | 推理模型的思考过程 token | 否（仅推理模型产生） |
| `event:content` | 正文回复 token | 是（所有模型） |
| `event:done` | 流结束信号，固定为最后一个事件 | 是 |

**典型时序：**

```
# 推理模型（如 DeepSeek R1）
event:thinking → event:thinking → ... → event:content → event:content → ... → event:done

# 普通模型（如 DeepSeek V3）
event:content → event:content → ... → event:done
```

### 会话管理 API

**GET /api/conversations — 获取所有会话列表**

```
HTTP 200 OK
[
  { "id": "uuid-1", "title": "解释相对论" },
  { "id": "uuid-2", "title": "你好" }
]
```

按创建时间倒序排列，最新会话在前。

**GET /api/conversations/{id} — 获取会话详情**

```
HTTP 200 OK
{
  "conversationId": "uuid-1",
  "messages": [
    { "messageType": "USER", "text": "你好" },
    { "messageType": "ASSISTANT", "text": "你好！有什么可以帮你的？" }
  ],
  "count": 2
}
```

**DELETE /api/conversations/{id} — 清除会话历史**

```
HTTP 200 OK
{ "conversationId": "uuid-1", "cleared": true, "timestamp": "2026-05-31T12:00:00Z" }
```

### 错误响应格式

所有接口异常统一返回以下 JSON 结构：

```json
{
  "status": 400,
  "error": "Bad Request",
  "message": "message: 消息内容不能为空",
  "timestamp": "2026-05-31T12:00:00Z"
}
```

| 状态码 | 场景 |
|--------|------|
| 400 | 请求参数校验失败（message 为空或超长等） |
| 500 | 服务端内部错误 |

### GET /actuator/health — 健康检查

```
HTTP 200 OK
{ "status": "UP" }
```

## 多模型兼容

前后端通过标准 SSE 事件协议解耦，接入新模型时：

- 推理模型（有思考过程）：在 `AiService.extractReasoningContent()` 中追加对应的 `instanceof` 分支即可，前端无需改动
- 普通模型（无思考过程）：直接使用现有 `event:content` 通道，零改动接入
- 模型特有的类型判断封装在 Service 层，Controller 层只依赖 Spring AI 标准 API（`AssistantMessage`），不接触任何模型特有实现类

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
│       │   │   └── CorsConfig.java         # CORS 配置（WebFlux）
│       │   ├── controller/
│       │   │   ├── ChatController.java     # 同步对话接口
│       │   │   ├── StreamController.java   # SSE 流式接口
│       │   │   └── ConversationController.java  # 会话管理接口
│       │   ├── dto/
│       │   │   ├── ChatRequest.java        # 请求 DTO（含校验）
│       │   │   └── ErrorResponse.java      # 错误响应体
│       │   ├── exception/
│       │   │   └── GlobalExceptionHandler.java  # 全局异常处理
│       │   └── service/
│       │       ├── AiService.java          # AI 对话服务（封装 ChatClient）
│       │       └── ConversationService.java  # 会话管理服务（ChatMemory 操作）
│       └── resources/
│           ├── application.yaml
│           └── config/
│               ├── ai.properties.example   # 配置模板（可提交到版本库）
│               └── ai.properties           # 实际配置（gitignore 保护）
├── agent-ui/                               # 前端 React
│   ├── package.json
│   ├── vite.config.ts
│   └── src/
│       ├── main.tsx                        # 应用入口
│       ├── App.tsx / App.css               # 主应用组件 + 布局
│       ├── index.css                       # 全局样式 + CSS 变量
│       ├── types.ts                        # TypeScript 类型定义
│       ├── api.ts                          # SSE 客户端 + 会话管理 API 封装
│       └── components/
│           ├── ChatInput.tsx / .css        # 聊天输入框
│           ├── MessageBubble.tsx / .css    # 消息气泡（Markdown + 思考过程）
│           ├── CodeBlock.tsx               # 代码高亮组件
│           ├── MermaidBlock.tsx / .css     # Mermaid 图表渲染
│           ├── Sidebar.tsx / .css          # 左侧会话列表
│           ├── QuestionNav.tsx / .css      # 右侧问题导航
│           └── MarkdownTest.tsx / .css     # 开发测试面板（仅 DEV 模式可见）
├── README.md
├── USAGE.md                                # 本文件
└── LICENSE                                 # Apache 2.0
```

## 架构关键设计

### 后端分层

```
Controller（接收请求、参数校验）
  → AiService（AI 对话、模型适配）
  → ConversationService（会话管理、ID 解析）
    → Spring AI ChatClient（标准 API）
      → MessageChatMemoryAdvisor（自动管理对话记忆）
        → ChatMemory（内存存储，可替换为持久化实现）
```

- Controller 层不依赖任何模型特有类，所有模型适配逻辑封装在 AiService
- conversationId 解析逻辑的唯一来源是 ConversationService.resolveConversationId()
- 推理内容提取使用 AiService 私有方法 extractReasoningContent()，对 Controller 透明
- 会话生命周期（列表、详情、清除）由 ConversationService 统一管理

### 前端组件树

```
App
├── Sidebar（会话管理：新建、切换、删除）
├── .app（主区域）
│   ├── Header
│   ├── WelcomeLayout（无消息时：欢迎内容 + 输入框整体居中）
│   ├── ChatScroll（有消息时：消息列表）
│   └── ChatInput（输入框）
└── QuestionNav（右侧问题导航：快速跳转到历史提问）
```

### SSE 流处理

- 前端 `api.ts` 封装完整 SSE 解析逻辑，App 组件不处理原始协议
- done 检测采用双重机制：`event:done` + ReadableStream 关闭兜底
- `safeDone()` 防止重复触发 onDone 回调

## 构建与部署

### 后端

```bash
cd agent
./mvnw clean package -DskipTests
java -jar target/agent-0.0.1-SNAPSHOT.jar
```

启动前设置环境变量：

```bash
export AI_API_KEY=sk-your-key
export AI_BASE_URL=https://api.deepseek.com
export AI_MODEL=deepseek-chat
```

### 前端

```bash
cd agent-ui
npm run build
# 静态文件输出到 dist/，可部署到 Nginx 或 CDN
```

生产环境部署时，建议使用 Nginx 反向代理统一前后端域名，避免 CORS 问题。
CORS 默认仅允许 `http://localhost:5173`，生产环境需在 `CorsConfig` 中调整为实际域名。

## 开发提示

- `MarkdownTest` 仅在开发模式（`import.meta.env.DEV`）下可见，生产构建自动隐藏
- 在对话中发送 ` ```mermaid ` 代码块即可渲染流程图、时序图、类图等
- Mermaid 图表渲染组件已抽取为独立共享组件（`MermaidBlock`），避免代码重复
- 前端 `api.ts` 中的 SSE 客户端同时兼容 `event:done` 事件和 ReadableStream 关闭两种结束信号
