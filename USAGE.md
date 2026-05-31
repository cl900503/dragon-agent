# Dragon Agent — 使用说明

## 项目简介

Dragon Agent 是一个 AI 对话平台，后端基于 **Spring Boot 4 + Spring AI 2**，
前端基于 **React 19 + Vite**，通过标准 SSE 事件协议与 AI 模型交互，
支持流式对话、推理过程展示和 Markdown 实时渲染。

## 环境要求

- **JDK 21** 或更高
- **Maven** 3.9+（可使用项目自带的 `mvnw`，无需手动安装）
- **Node.js** 20+ 和 npm

## 快速启动

### 1. 配置 API Key

```bash
cd agent
cp src/main/resources/config/ai.properties.example \
   src/main/resources/config/ai.properties
```

编辑 `ai.properties`，填入你的 DeepSeek API Key：

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

后端运行在 `http://localhost:8080`，Actuator 健康检查端点：`/actuator/health`。

### 3. 启动前端

```bash
cd agent-ui
npm install
npm run dev
```

前端开发服务器运行在 `http://localhost:5173`，Vite 会自动代理 `/api` 请求到后端。

### 4. 开始对话

浏览器打开 `http://localhost:5173`，输入消息即可开始对话。

顶部 "Markdown 测试" 按钮可查看所有支持的 Markdown 语法和 Mermaid 图表。

## API 接口

### POST /api/chat — 同步对话

等待 AI 完整回复后一次性返回。

**请求：**

```json
POST /api/chat
Content-Type: application/json

{ "msg": "你好，请介绍一下自己" }
```

**响应：**

```
HTTP 200 OK
Content-Type: text/plain

你好！我是 Dragon Agent...
```

### POST /api/stream — 流式对话（SSE）

通过 Server-Sent Events 逐 token 推送，实现打字机效果。支持三种标准事件类型。

**请求：**

```json
POST /api/stream
Content-Type: application/json

{ "msg": "请用 Markdown 写一份报告" }
```

**SSE 事件类型：**

| 事件 | 含义 | 必选 |
|------|------|------|
| `event:thinking` | 推理模型的思考过程 token | 否（仅推理模型产生） |
| `event:content` | 正文回复 token | 是（所有模型） |
| `event:done` | 流结束信号，固定为最后一个事件 | 是 |

**典型时序：**

```
# 推理模型（如 DeepSeek R1 / deepseek-reasoner）
event:thinking → event:thinking → ... → event:content → event:content → ... → event:done

# 普通模型（如 DeepSeek V3 / deepseek-chat）
event:content → event:content → ... → event:done
```

**实际 SSE 响应示例：**

```
event:thinking
data:用户要求用 Markdown 写报告，需要包含...

event:content
data:好的，我来

event:content
data:写一份报告

event:done
data:
```

### 错误响应格式

所有接口异常统一返回以下 JSON 结构：

```json
{
  "status": 400,
  "error": "Bad Request",
  "message": "msg: 消息内容不能为空",
  "timestamp": "2026-05-31T12:00:00Z"
}
```

| 状态码 | 场景 |
|--------|------|
| 400 | 请求参数校验失败（msg 为空等） |
| 500 | 服务端内部错误 |

### GET /actuator/health — 健康检查

```
HTTP 200 OK
{ "status": "UP" }
```

## 多模型兼容

前后端通过标准 SSE 事件协议解耦，接入新模型时：

- **推理模型**（有思考过程）：后端在 `StreamController.extractReasoningContent()` 中追加对应 `instanceof` 分支即可，前端无需改动
- **普通模型**（无思考过程）：直接使用现有 `event:content` 通道，零改动接入
- **未来新事件类型**：前端自动将未识别事件视为正文内容，保持向前兼容

## 项目结构

```
dragon-agent/
├── agent/                              # 后端 Spring Boot
│   ├── pom.xml
│   ├── .gitignore
│   └── src/main/
│       ├── java/com/dragon/agent/
│       │   ├── AgentApplication.java   # 启动入口
│       │   ├── config/
│       │   │   └── CorsConfig.java     # CORS 配置（WebFlux）
│       │   ├── controller/
│       │   │   ├── ChatController.java
│       │   │   ├── StreamController.java
│       │   │   └── GlobalExceptionHandler.java
│       │   ├── model/
│       │   │   ├── ChatRequest.java    # 请求 DTO
│       │   │   └── ErrorResponse.java  # 错误响应体
│       │   └── service/
│       │       └── AiService.java      # AI 对话服务
│       └── resources/
│           ├── application.yaml
│           └── config/
│               ├── ai.properties.example  # 配置模板（可提交）
│               └── ai.properties          # 实际配置（gitignore）
├── agent-ui/                           # 前端 React
│   ├── package.json
│   ├── vite.config.ts
│   └── src/
│       ├── main.tsx
│       ├── App.tsx
│       ├── App.css / index.css
│       ├── types.ts
│       ├── api.ts                      # SSE 客户端封装
│       └── components/
│           ├── ChatInput.tsx / .css
│           ├── MessageBubble.tsx / .css
│           ├── CodeBlock.tsx
│           └── MarkdownTest.tsx        # 开发用测试面板
├── README.md
├── USAGE.md                            # 本文件
└── LICENSE                             # Apache 2.0
```

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

## 开发提示

- `MarkdownTest` 组件通过 React.lazy 懒加载，不影响主 bundle 体积
- 在对话中发送 ` ```mermaid ` 代码块即可渲染流程图、时序图、类图等
- CORS 默认仅允许 `http://localhost:5173`，生产环境需在 `CorsConfig` 中调整
