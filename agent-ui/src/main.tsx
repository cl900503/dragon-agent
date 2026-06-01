import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import mermaid from 'mermaid'
import './index.css'
import App from './App'

// Mermaid 全局初始化一次
mermaid.initialize({ startOnLoad: false, theme: 'default' })

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
