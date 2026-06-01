# Dragon Agent

企业级 AI 对话平台，基于 **Spring Boot 4 + React 19 + DeepSeek**，支持流式对话（SSE）、推理过程展示、Markdown 实时渲染、用户认证和多会话隔离。

## 特性

- **用户认证**：注册/登录/退出，Spring Security + BCrypt 密码加密，WebSession 会话管理
- **数据持久化**：MySQL 存储用户、会话和消息，JdbcChatMemory 自动管理对话历史
- **多用户隔离**：每个用户的会话独立存储，A 用户看不到 B 用户的会话
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
| 安全框架 | Spring Security (WebFlux) | — |
| 持久化 | Spring Data JPA + MySQL + JdbcChatMemory | — |
| 前端框架 | React + Vite | 19.x / 8.x |
| 语言 | TypeScript | 6.x |
| Markdown | react-markdown + Shiki + KaTeX + Mermaid | — |

## 快速开始

### 环境要求

- JDK 21+、Node.js 20+、MySQL 8.x

### 启动 MySQL

```bash
docker run -d --name mysql-dragon \
  -e MYSQL_ROOT_PASSWORD=root \
  -e MYSQL_DATABASE=dragon_agent \
  -p 3306:3306 \
  mysql:8.0
```

### 启动应用

```bash
# 1. 配置 API Key
cp agent/src/main/resources/config/ai.properties.example \
   agent/src/main/resources/config/ai.properties
# 编辑 ai.properties 填入 DeepSeek API Key

# 2. 启动后端（默认 :8080，dev profile）
cd agent && ./mvnw spring-boot:run

# 3. 启动前端（默认 :5173）
cd agent-ui && npm install && npm run dev
```

浏览器打开 `http://localhost:5173`，注册账号后即可使用。

## 项目结构

```
dragon-agent/
├── agent/                          # 后端 Spring Boot
│   └── src/main/java/com/dragon/agent/
│       ├── config/                 # CORS、Security 配置
│       ├── controller/             # RESTful 接口层
│       ├── dto/                    # 数据传输对象
│       ├── entity/                 # JPA 实体
│       ├── exception/              # 异常定义和全局处理
│       ├── repository/             # JPA Repository
│       ├── service/                # 业务逻辑层
│       └── support/                # 工具组件
├── agent-ui/                       # 前端 React
│   └── src/
│       ├── hooks/                  # 自定义 Hook（useAuth、useConversation）
│       └── components/             # UI 组件（ChatInput、CodeBlock、ChevronIcon 等）
├── USAGE.md                        # 详细使用文档
└── LICENSE                         # Apache 2.0
```

## 文档

详细的 API 接口文档、SSE 事件协议、环境变量配置、构建部署说明请参见 [USAGE.md](./USAGE.md)。

## License

[Apache License 2.0](./LICENSE)
