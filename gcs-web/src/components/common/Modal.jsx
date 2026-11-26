import React, { useEffect, useRef } from 'react'

// 可聚焦元素选择器：Tab 陷阱的循环范围
const FOCUSABLE = 'a[href],button:not([disabled]),input,select,textarea,[tabindex]:not([tabindex="-1"])'

// 打开中的弹窗栈（后进先出）：叠层时 Esc 只关闭最上层，避免一次按键连关多个弹窗
const modalStack = []

const overlayStyle = {
  position: 'fixed', inset: 0, background: 'rgba(0,0,0,.55)', zIndex: 'var(--z-modal)',
  display: 'flex', alignItems: 'center', justifyContent: 'center',
}

export default function Modal({ open, onClose, title, children, width }) {
  const containerRef = useRef(null)
  const restoreRef = useRef(null)

  // 记录触发元素 → 打开时聚焦 → 关闭时还焦；顺带维护弹窗栈
  useEffect(() => {
    if (!open) return undefined
    const node = containerRef.current
    if (!node) return undefined
    restoreRef.current = document.activeElement
    modalStack.push(node)
    // 子元素已有 autoFocus（如 ConfirmDialog 的「取消」）时尊重它，否则聚焦容器
    if (!node.contains(document.activeElement)) node.focus()
    return () => {
      const idx = modalStack.indexOf(node)
      if (idx >= 0) modalStack.splice(idx, 1)
      const prev = restoreRef.current
      if (prev && typeof prev.focus === 'function') {
        try {
          prev.focus()
        } catch {
          // 触发元素可能已随列表刷新卸载，还焦失败可忽略
        }
      }
    }
  }, [open])

  // Tab / Shift+Tab 在容器内循环，焦点不允许逃到背后的页面
  useEffect(() => {
    if (!open) return undefined
    const onKeyDown = (e) => {
      if (e.key !== 'Tab') return
      const node = containerRef.current
      if (!node) return
      const items = Array.from(node.querySelectorAll(FOCUSABLE))
      if (items.length === 0) {
        e.preventDefault()
        node.focus()
        return
      }
      const first = items[0]
      const last = items[items.length - 1]
      const active = document.activeElement
      if (e.shiftKey) {
        if (active === first || active === node || !node.contains(active)) {
          e.preventDefault()
          last.focus()
        }
      } else if (active === last || active === node || !node.contains(active)) {
        e.preventDefault()
        first.focus()
      }
    }
    document.addEventListener('keydown', onKeyDown)
    return () => document.removeEventListener('keydown', onKeyDown)
  }, [open])

  if (!open) return null

  const handleKeyDown = (e) => {
    // 只在最上层响应
    if (modalStack[modalStack.length - 1] !== containerRef.current) return
    if (e.key === 'Escape') {
      e.stopPropagation()
      if (onClose) onClose()
    }
  }

  return (
    <div
      onClick={(e) => { if (e.target === e.currentTarget && onClose) onClose() }}
      style={overlayStyle}
    >
      <div
        ref={containerRef}
        role="dialog"
        aria-modal="true"
        aria-label={title}
        tabIndex={-1}
        onKeyDown={handleKeyDown}
        style={{
          background: 'var(--bg-1)', border: '1px solid var(--line-2)', borderRadius: 6,
          padding: 16, minWidth: width || 300, maxHeight: '80vh', overflowY: 'auto',
        }}
      >
        {children}
      </div>
    </div>
  )
}