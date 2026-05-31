# Dragon Agent

企业级 AI 对话平台，基于 **Spring Boot 4 + React 19 + DeepSeek**，支持流式对话（SSE）、推理过程展示和 Markdown 实时渲染。

## 特性

- **流式对话**：基于 SSE 协议的逐 token 推送，实现打字机效果
- **推理展示**：支持 DeepSeek R1 等推理模型的思考过程实时展示
- **Markdown 渲染**：GFM、KaTeX 数学公式、Mermaid 图表（流程图、时序图、类图）
- **代码高亮**：Shiki 语法高亮，支持一键复制
- **多模型兼容**：标准 SSE 事件协议（thinking / content / done），接入其他模型无需改动前端

## 技术栈

| 层级 | 技术 | 版本 |
|------|------|------|
| 后端框架 | Spring Boot (WebFlux + Netty) | 4.0.6 |
| AI 框架 | Spring AI DeepSeek Starter | 2.0.0-M8 |
| 前端框架 | React + Vite | 19.x / 8.x |
| 语言 | TypeScript | 6.x |
| Markdown | react-markdown + Shiki + KaTeX + Mermaid | — |

## 快速开始

### 环境要求

- JDK 21+、Node.js 20+

### 启动

```bash
# 1. 配置 API Key
cp agent/src/main/resources/config/ai.properties.example \
   agent/src/main/resources/config/ai.properties
# 编辑 ai.properties，填入你的 DeepSeek API Key

# 2. 启动后端（默认 :8080）
cd agent && ./mvnw spring-boot:run

# 3. 启动前端（默认 :5173）
cd agent-ui && npm install && npm run dev
```

浏览器打开 `http://localhost:5173` 即可使用。

## 项目结构

```
dragon-agent/
├── agent/                          # 后端 Spring Boot
│   └── src/main/java/com/dragon/agent/
│       ├── config/CorsConfig.java
│       ├── controller/
│       │   ├── ChatController.java           # POST /api/chat
│       │   ├── StreamController.java         # POST /api/stream (SSE)
│       │   └── GlobalExceptionHandler.java
│       ├── model/
│       │   ├── ChatRequest.java
│       │   └── ErrorResponse.java
│       └── service/AiService.java
├── agent-ui/                       # 前端 React
│   └── src/
│       ├── App.tsx                 # 主应用
│       ├── api.ts                  # SSE 客户端
│       ├── types.ts                # 类型定义
│       └── components/
│           ├── ChatInput.tsx       # 输入框
│           ├── MessageBubble.tsx   # 消息气泡（Markdown + Mermaid）
│           ├── CodeBlock.tsx       # 代码块（高亮 + 复制）
│           └── MarkdownTest.tsx    # Markdown 测试面板
├── USAGE.md                        # 详细使用文档
└── LICENSE                         # Apache 2.0
```

## 文档

详细的 API 接口文档、SSE 事件协议、构建部署说明请参见 [USAGE.md](./USAGE.md)。

## License

[Apache License 2.0](./LICENSE)
