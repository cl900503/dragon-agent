/**
 * 统一类型定义。
 *
 * @author 陈龙
 * @since 2026-05-31
 */

/** 单条聊天消息 */
export interface Message {
  /** 消息唯一 ID（crypto.randomUUID()），用作 React key */
  id: string
  /** 角色：user = 用户，assistant = AI */
  role: 'user' | 'assistant'
  /** 正文内容，Markdown 格式 */
  content: string
  /** DeepSeek R1 思考过程文本，流式累积 */
  reasoning: string
  /** 是否正在流式生成思考内容（用于显示加载动画） */
  thinking: boolean
}

