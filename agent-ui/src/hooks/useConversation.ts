/**
 * 会话管理 hook——多会话状态、流式接收、消息缓存。
 *
 * 封装 handleSend 的完整 SSE 流式接收逻辑和会话切换/删除的缓存策略。
 *
 * @author 陈龙
 * @since 2026-06-01
 */

import { useState, useRef, useCallback, useEffect } from 'react'
import {
  streamChat,
  fetchConversations,
  fetchConversationMessages,
  deleteConversation,
} from '../api'
import type { ConversationSummary } from '../api'
import type { Message } from '../types'

const msgId = () => crypto.randomUUID()

export function useConversation(isLoggedIn: boolean) {
  const [messages, setMessages] = useState<Message[]>([])
  const [streaming, setStreaming] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [activeConversationId, setActiveConversationId] = useState<string>(
    () => crypto.randomUUID(),
  )
  const [conversations, setConversations] = useState<ConversationSummary[]>([])
  const [allMessages, setAllMessages] = useState<Record<string, Message[]>>({})

  const abortRef = useRef<AbortController | null>(null)
  const conversationIdRef = useRef(activeConversationId)
  useEffect(() => {
    conversationIdRef.current = activeConversationId
  }, [activeConversationId])

  // 登录后加载会话列表
  useEffect(() => {
    if (isLoggedIn) {
      fetchConversations()
        .then(setConversations)
        .catch(() => {})
    }
  }, [isLoggedIn])

  const refreshConversations = useCallback(() => {
    fetchConversations()
      .then(setConversations)
      .catch(() => {})
  }, [])

  /** 发送消息，开始流式接收 */
  const handleSend = useCallback(
    (msg: string, enableRag: boolean = true) => {
      const userMsg: Message = {
        id: msgId(),
        role: 'user',
        content: msg,
        reasoning: '',
        thinking: false,
      }
      const aiMsg: Message = {
        id: msgId(),
        role: 'assistant',
        content: '',
        reasoning: '',
        thinking: true,
      }
      setMessages(prev => [...prev, userMsg, aiMsg])
      setStreaming(true)
      setError(null)

      const controller = streamChat(msg, conversationIdRef.current, userMsg.id, aiMsg.id, enableRag, {
        onToken(text) {
          setMessages(prev => {
            const updated = [...prev]
            const last = updated[updated.length - 1]
            if (last.role !== 'assistant') return prev
            updated[updated.length - 1] = {
              ...last,
              content: last.content + text,
              thinking: false,
            }
            return updated
          })
        },
        onThinking(text) {
          setMessages(prev => {
            const updated = [...prev]
            const last = updated[updated.length - 1]
            if (last.role !== 'assistant') return prev
            updated[updated.length - 1] = {
              ...last,
              reasoning: last.reasoning + text,
            }
            return updated
          })
        },
        onDone(docs: string[]) {
          setStreaming(false)
          abortRef.current = null
          setMessages(prev => {
            const updated = [...prev]
            const last = updated[updated.length - 1]
            if (last.role === 'assistant') {
              // 从 done 事件中解析检索结果：每行 "文件名|片段"
              const traces = docs.length > 0
                ? docs.map(line => {
                    const [name, snippet] = line.split('|')
                    return { documentName: name || line, chunkIndex: 0, contentSnippet: snippet || '' }
                  })
                : undefined
              updated[updated.length - 1] = {
                ...last,
                thinking: false,
                retrievalTraces: traces as any,
              }
            }
            return updated
          })
          refreshConversations()
        },
        onError(err) {
          setStreaming(false)
          abortRef.current = null
          setError(err.message)
          setMessages(prev => {
            const updated = [...prev]
            const last = updated[updated.length - 1]
            if (last.role === 'assistant') {
              updated[updated.length - 1] = {
                ...last,
                thinking: false,
                content: last.content || `请求失败：${err.message}`,
              }
            }
            return updated
          })
          refreshConversations()
        },
      })
      abortRef.current = controller
    },
    [refreshConversations],
  )

  /** 新建会话 */
  const handleNewChat = useCallback(() => {
    abortRef.current?.abort()
    abortRef.current = null
    if (messages.length > 0) {
      setAllMessages(prev => ({ ...prev, [activeConversationId]: messages }))
    }
    setStreaming(false)
    setMessages([])
    setError(null)
    const newId = crypto.randomUUID()
    setActiveConversationId(newId)
  }, [messages, activeConversationId])

  /** 切换会话 */
  const handleSelectConversation = useCallback(
    async (id: string) => {
      if (id === activeConversationId) return

      abortRef.current?.abort()
      abortRef.current = null
      setStreaming(false)
      setError(null)

      if (messages.length > 0) {
        setAllMessages(prev => ({ ...prev, [activeConversationId]: messages }))
      }

      // 优先使用本地缓存
      if (allMessages[id]) {
        setMessages(allMessages[id])
        setActiveConversationId(id)
        return
      }

      try {
        const data = await fetchConversationMessages(id)
        const mapped: Message[] = data.messages.map(bm => ({
          id: msgId(),
          role: bm.messageType === 'USER' ? ('user' as const) : ('assistant' as const),
          content: bm.text,
          reasoning: bm.reasoning || '',
          thinking: false,
          retrievalTraces: bm.retrievalTraces,
        }))
        // 将 USER 消息的检索来源复制到下一条 ASSISTANT 消息（用于展示）
        for (let i = 1; i < mapped.length; i++) {
          if (mapped[i].role === 'assistant' && !mapped[i].retrievalTraces) {
            mapped[i].retrievalTraces = mapped[i - 1].retrievalTraces
          }
        }
        setAllMessages(prev => ({ ...prev, [id]: mapped }))
        setMessages(mapped)
        setActiveConversationId(id)
      } catch {
        setError('加载对话失败')
      }
    },
    [activeConversationId, messages, allMessages],
  )

  /** 删除会话 */
  const handleDeleteConversation = useCallback(
    async (id: string) => {
      try {
        await deleteConversation(id)
      } catch {
        // API 失败也继续清理本地状态
      }

      setConversations(prev => prev.filter(c => c.id !== id))
      setAllMessages(prev => {
        const next = { ...prev }
        delete next[id]
        return next
      })

      if (id === activeConversationId) {
        setMessages([])
        setActiveConversationId(crypto.randomUUID())
      }
    },
    [activeConversationId],
  )

  /** 停止当前生成 */
  const handleStop = useCallback(() => {
    abortRef.current?.abort()
    abortRef.current = null
    setStreaming(false)
    setMessages(prev => {
      const updated = [...prev]
      const last = updated[updated.length - 1]
      if (last.role === 'assistant') {
        updated[updated.length - 1] = { ...last, thinking: false }
      }
      return updated
    })
  }, [])

  /** 重置所有会话状态（退出登录时调用） */
  const reset = useCallback(() => {
    abortRef.current?.abort()
    abortRef.current = null
    setStreaming(false)
    setMessages([])
    setConversations([])
    setAllMessages({})
    setError(null)
  }, [])

  return {
    messages,
    streaming,
    error,
    activeConversationId,
    conversations,
    setError,
    handleSend,
    handleNewChat,
    handleSelectConversation,
    handleDeleteConversation,
    handleStop,
    reset,
  }
}
