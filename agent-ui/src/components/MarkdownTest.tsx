/**
 * Markdown 语法测试面板——开发阶段验证 Markdown 渲染效果。
 *
 * 涵盖 GFM、LaTeX 公式、Mermaid 图表等所有渲染能力。
 * 通过 React.lazy 按需加载，不影响主 bundle 体积。
 *
 * @author 陈龙
 * @since 2026-05-31
 */

import { defaultSchema } from 'rehype-sanitize'
import MarkdownRenderer from './MarkdownRenderer'
import './MarkdownTest.css'

/**
 * 测试面板专用 rehype-sanitize 白名单。
 * 相比对话面板，额外允许 font 标签的 color、size、face 属性。
 */
const testSchema = {
  ...defaultSchema,
  tagNames: [...(defaultSchema.tagNames || []), 'font'],
  attributes: {
    ...defaultSchema.attributes,
    span: [...(defaultSchema.attributes?.span || []), ['style']],
    font: [
      ...(defaultSchema.attributes?.font || []),
      ['color'],
      ['size'],
      ['face'],
    ],
  },
}

/**
 * Markdown 渲染测试内容。
 * 涵盖：标题、粗斜体、列表、任务列表、引用、分隔线、着色文本、转义符、
 * 表格、代码块、链接、LaTeX 数学公式、font 标签、HTML、Mermaid 图表、脚注。
 */
const testMarkdown = `# 一级标题 H1

## 二级标题 H2

### 三级标题 H3

#### 四级标题 H4

**粗体文本** 和 *斜体文本* 和 ~~删除线~~ 和 \`行内代码\`

## 列表

- 无序列表项 1
- 无序列表项 2
  - 嵌套列表项 2.1
  - 嵌套列表项 2.2
- 无序列表项 3

1. 有序列表项 1
2. 有序列表项 2
   1. 嵌套有序列表 2.1
   2. 嵌套有序列表 2.2
3. 有序列表项 3

- [x] 已完成任务
- [ ] 未完成任务
- [ ] 待办事项

## 引用

> 这是一级引用

> > 这是二级嵌套引用
> > 多行内容
>
> 回到一级

## 分隔线（三种语法）

上面内容

---

中间内容 1（---）

***

中间内容 2（***）

___

下面内容（___）

## 着色文本

HTML 红色：<span style="color:red">红色文字</span>

HTML 蓝色：<span style="color:blue">蓝色文字</span>

<span style="background-color:#fff3cd;color:#856404;padding:2px 6px;border-radius:3px">带背景色的标签样式</span>

## 转义符

星号转义：\\*这是一段包含星号的文本\\*

井号转义：\\# 这不是标题

反斜杠转义：\\\\ 这是一个反斜杠

下划线转义：\\_这不是斜体\\_

混合转义：\\*\\*这不是粗体\\*\\* 以及 \\#\\# 这不是 H2

Markdown 中直接使用 \\\` 反引号 \\\` 包裹代码

## 表格

| 左对齐 | 居中 | 右对齐 |
| :--- | :--: | ---: |
| 单元格 | 单元格 | 单元格 |
| **粗体** | \`代码\` | [链接](https://github.com) |

## 代码

行内 \`const x = 1 + 2\` 代码示例

\`\`\`typescript
interface User {
  name: string
  age: number
}

function greet(user: User): string {
  return \`Hello, \${user.name}! You are \${user.age} years old.\`
}
\`\`\`

\`\`\`python
def fibonacci(n: int) -> list[int]:
    """生成斐波那契数列"""
    result = [0, 1]
    for _ in range(n - 2):
        result.append(result[-1] + result[-2])
    return result[:n]

# 打印前20项
print(fibonacci(20))
\`\`\`

## 链接与图片

[GitHub 链接](https://github.com)

## LaTeX 数学公式

行内公式：$E = mc^2$、$a^2 + b^2 = c^2$、$\\pi \\approx 3.14159$

行内分数：$\\frac{1}{2} + \\frac{1}{3} = \\frac{5}{6}$

行内极限：$\\lim_{x \\to \\infty} \\frac{1}{x} = 0$

块级公式：

$$
\\frac{-b \\pm \\sqrt{b^2 - 4ac}}{2a}
$$

求和公式：

$$
\\sum_{i=1}^{n} i = \\frac{n(n+1)}{2}
$$

积分公式：

$$
\\int_{0}^{\\infty} e^{-x^2} dx = \\frac{\\sqrt{\\pi}}{2}
$$

矩阵：

$$
\\begin{pmatrix}
a & b \\\\
c & d
\\end{pmatrix}
$$

希腊字母：$\\alpha$ $\\beta$ $\\gamma$ $\\delta$ $\\epsilon$ $\\theta$ $\\lambda$ $\\mu$ $\\sigma$ $\\phi$ $\\omega$

集合论：$\\forall x \\in \\mathbb{R}, \\exists y > 0$

箭头：$A \\rightarrow B \\leftarrow C \\Rightarrow D \\Leftrightarrow E$

## font 标签

<font color="red">红色字体</font> 和 <font color="#2196f3">蓝色字体</font>

<font size="5">大号文字</font> 与 <font size="1">小号文字</font>

<font face="Comic Sans MS, cursive">Comic Sans 字体</font>

组合：<font color="#e91e63" size="4"><b>粉色大号加粗</b></font>

## HTML

<div align="center">
  <strong>居中文本</strong>
</div>

## Mermaid 流程图

\`\`\`mermaid
flowchart TD
    A[开始] --> B{是否登录?}
    B -->|是| C[进入主页]
    B -->|否| D[跳转登录页]
    D --> E[输入用户名密码]
    E --> F{验证通过?}
    F -->|是| C
    F -->|否| G[显示错误提示]
    G --> E
    C --> H[结束]
\`\`\`

## Mermaid 时序图

\`\`\`mermaid
sequenceDiagram
    participant U as 用户
    participant F as 前端
    participant B as 后端
    participant AI as DeepSeek

    U->>F: 输入消息
    F->>B: POST /api/stream
    B->>AI: Chat Completion API
    AI-->>B: SSE 流式响应
    B-->>F: SSE 事件流
    F-->>U: 实时渲染 Markdown
\`\`\`

## Mermaid 类图

\`\`\`mermaid
classDiagram
    class AgentApplication {
        +main(args: String[]): void
    }
    class ChatController {
        +chat(request: ChatRequest): String
    }
    class StreamController {
        +stream(request: ChatRequest): Flux~ServerSentEvent~
    }
    class AiService {
        +chat(message: String): String
        +stream(message: String): Flux~ServerSentEvent~
    }
    ChatController --> AiService
    StreamController --> AiService
\`\`\`

## 脚注

这是一个脚注引用[^1]

[^1]: 这是脚注内容
`

/**
 * Markdown 语法测试面板组件。
 *
 * 通过 App 头部按钮切换显示。
 * 此组件通过 React.lazy 按需加载，不影响主 bundle 体积。
 */
export default function MarkdownTest() {
  return (
    <div className="markdown-test-panel">
      <h1>Markdown 语法测试面板</h1>
      <MarkdownRenderer sanitizeSchema={testSchema} mermaidIdPrefix="mermaid-test">
        {testMarkdown}
      </MarkdownRenderer>
    </div>
  )
}
