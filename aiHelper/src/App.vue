<template>
  <div class="chat-container">
    <!-- 聊天记录区域 -->
    <div class="chat-messages" ref="messagesContainer">
      <div 
        v-for="message in messages" 
        :key="message.id"
        :class="['message', message.type === 'user' ? 'user-message' : 'ai-message']"
      >
        <div class="message-content">
          <div class="message-avatar">
            {{ message.type === 'user' ? '👤' : '🤖' }}
          </div>
          <div class="message-text" v-html="formatMessage(message.content)"></div>
        </div>
      </div>
    </div>

    <!-- 输入区域 -->
    <div class="chat-input-area">
      <div class="input-container">
        <textarea
          v-model="inputMessage"
          @keydown.enter.prevent="handleEnterKey"
          placeholder="请输入您的问题..."
          :disabled="isLoading"
          rows="3"
        ></textarea>
        <button 
          @click="sendMessage" 
          :disabled="!inputMessage.trim() || isLoading"
          class="send-button"
        >
          {{ isLoading ? '发送中...' : '发送' }}
        </button>
      </div>
    </div>
  </div>
</template>

<script>
import { ref, onMounted, watch } from 'vue'

export default {
  name: 'App',
  setup() {
    // 状态管理
    const messages = ref([])
    const inputMessage = ref('')
    const isLoading = ref(false)
    const messagesContainer = ref(null)
    let memoryId = null
    let eventSource = null

    // 生成聊天室ID
    const generateMemoryId = () => {
      // 从本地存储获取，如果没有则生成新的
      const storedId = localStorage.getItem('aiHelperMemoryId')
      if (storedId) {
        memoryId = parseInt(storedId)
      } else {
        // 生成随机ID，实际项目中应该由后端生成
        memoryId = Math.floor(Math.random() * 1000000)
        localStorage.setItem('aiHelperMemoryId', memoryId.toString())
      }
    }

    // 发送消息
    const sendMessage = async () => {
      if (!inputMessage.value.trim() || isLoading.value) return

      const userMessage = inputMessage.value.trim()
      inputMessage.value = ''

      // 添加用户消息到聊天记录
      messages.value.push({
        id: Date.now(),
        type: 'user',
        content: userMessage
      })

      isLoading.value = true

      // 添加AI消息占位符
      const aiMessageId = Date.now() + 1
      messages.value.push({
        id: aiMessageId,
        type: 'ai',
        content: ''
      })

      scrollToBottom()

      try {
        // 使用SSE调用接口
        const url = `/api/ai/chat?memoryId=${memoryId}&message=${encodeURIComponent(userMessage)}`
        eventSource = new EventSource(url)

        eventSource.onmessage = (event) => {
          const aiMessage = messages.value.find(msg => msg.id === aiMessageId)
          if (aiMessage) {
            aiMessage.content += event.data
            scrollToBottom()
          }
        }

        eventSource.onopen = () => {
          console.log('SSE连接已建立')
        }

        eventSource.onerror = (error) => {
          console.error('SSE连接错误:', error)
          console.error('连接URL:', url)
          console.error('请检查后端服务器是否运行在 http://localhost:8081')
          eventSource.close()
          isLoading.value = false
          
          // 更新AI消息为错误信息
          const aiMessage = messages.value.find(msg => msg.id === aiMessageId)
          if (aiMessage && aiMessage.content === '') {
            aiMessage.content = '抱歉，连接服务器失败。请确认：\n1. 后端服务器是否正在运行\n2. 服务器地址是否为 http://localhost:8081\n3. 网络连接是否正常'
          }
        }

        eventSource.onclose = () => {
          console.log('SSE连接已关闭')
          isLoading.value = false
        }
      } catch (error) {
        console.error('发送消息失败:', error)
        isLoading.value = false
        
        // 更新AI消息为错误信息
        const aiMessage = messages.value.find(msg => msg.id === aiMessageId)
        if (aiMessage) {
          aiMessage.content = '抱歉，发送消息失败，请稍后重试。'
        }
      }
    }

    // 处理回车键发送消息
    const handleEnterKey = (event) => {
      if (event.shiftKey) {
        // Shift+Enter 换行
        return
      }
      sendMessage()
    }

    // 滚动到底部
    const scrollToBottom = () => {
      setTimeout(() => {
        if (messagesContainer.value) {
          messagesContainer.value.scrollTop = messagesContainer.value.scrollHeight
        }
      }, 100)
    }

    // 格式化消息内容（简单的代码高亮和换行处理）
    const formatMessage = (content) => {
      return content
        .replace(/\n/g, '<br>')
        .replace(/```([\s\S]*?)```/g, '<pre><code>$1</code></pre>')
        .replace(/`([^`]+)`/g, '<code>$1</code>')
    }

    // 监听消息变化，自动滚动到底部
    watch(messages, () => {
      scrollToBottom()
    })

    // 组件挂载时初始化
    onMounted(() => {
      generateMemoryId()
      
      // 添加欢迎消息
      messages.value.push({
        id: 1,
        type: 'ai',
        content: '你好！我是AI编程小助手，有什么编程问题或面试相关的问题可以问我哦！'
      })
    })

    // 组件卸载时关闭SSE连接
    const cleanup = () => {
      if (eventSource) {
        eventSource.close()
      }
    }

    // 监听页面关闭事件
    window.addEventListener('beforeunload', cleanup)

    return {
      messages,
      inputMessage,
      isLoading,
      messagesContainer,
      sendMessage,
      handleEnterKey,
      formatMessage
    }
  }
}
</script>

<style scoped>
.chat-container {
  display: flex;
  flex-direction: column;
  height: 100vh;
  background-color: #f5f5f5;
}

.chat-messages {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.message {
  display: flex;
  align-items: flex-start;
  max-width: 70%;
}

.user-message {
  align-self: flex-end;
  justify-content: flex-end;
}

.ai-message {
  align-self: flex-start;
}

.message-content {
  display: flex;
  gap: 8px;
  align-items: flex-start;
}

.user-message .message-content {
  flex-direction: row-reverse;
}

.message-avatar {
  font-size: 32px;
  flex-shrink: 0;
}

.message-text {
  background-color: white;
  padding: 12px 16px;
  border-radius: 16px;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.1);
  word-wrap: break-word;
  line-height: 1.5;
}

.user-message .message-text {
  background-color: #007aff;
  color: white;
}

.chat-input-area {
  padding: 20px;
  background-color: white;
  border-top: 1px solid #e0e0e0;
}

.input-container {
  display: flex;
  gap: 12px;
  align-items: flex-end;
}

textarea {
  flex: 1;
  padding: 12px;
  border: 1px solid #e0e0e0;
  border-radius: 8px;
  resize: none;
  font-family: inherit;
  font-size: 16px;
  line-height: 1.5;
}

textarea:focus {
  outline: none;
  border-color: #007aff;
}

.send-button {
  padding: 12px 24px;
  background-color: #007aff;
  color: white;
  border: none;
  border-radius: 8px;
  font-size: 16px;
  cursor: pointer;
  transition: background-color 0.2s;
}

.send-button:hover:not(:disabled) {
  background-color: #0056b3;
}

.send-button:disabled {
  background-color: #cccccc;
  cursor: not-allowed;
}

/* 代码块样式 */
:deep(pre) {
  background-color: #f6f8fa;
  padding: 16px;
  border-radius: 8px;
  overflow-x: auto;
  margin: 8px 0;
}

:deep(code) {
  background-color: #f6f8fa;
  padding: 2px 4px;
  border-radius: 4px;
  font-family: 'Monaco', 'Menlo', 'Ubuntu Mono', monospace;
}

:deep(pre code) {
  background-color: transparent;
  padding: 0;
}

/* 滚动条样式 */
.chat-messages::-webkit-scrollbar {
  width: 6px;
}

.chat-messages::-webkit-scrollbar-track {
  background: #f1f1f1;
}

.chat-messages::-webkit-scrollbar-thumb {
  background: #888;
  border-radius: 3px;
}

.chat-messages::-webkit-scrollbar-thumb:hover {
  background: #555;
}

/* 响应式设计 */
@media (max-width: 768px) {
  .message {
    max-width: 85%;
  }
  
  .chat-messages {
    padding: 16px 12px;
  }
  
  .chat-input-area {
    padding: 16px 12px;
  }
}
</style>