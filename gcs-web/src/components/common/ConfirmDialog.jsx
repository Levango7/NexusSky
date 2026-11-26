import React from 'react'
import Modal from './Modal.jsx'

const btnBase = {
  fontSize: 11,
  padding: '5px 14px',
  borderRadius: 3,
  cursor: 'pointer',
  background: 'transparent',
  border: '1px solid var(--line-2)',
  color: 'var(--dim)',
}

// 二次确认弹窗：只负责回调，不发请求（调用方决定确认后做什么）
export default function ConfirmDialog({
  open,
  title,
  message,
  confirmText = '确认',
  cancelText = '取消',
  danger,
  onConfirm,
  onCancel,
}) {
  return (
    <Modal open={open} onClose={onCancel} title={title} width={360}>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 14, minWidth: 300 }}>
        <div style={{ fontSize: 12, color: 'var(--text)', lineHeight: 1.7 }}>{message}</div>
        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
          {/* 取消为默认焦点：破坏性操作先落在安全选项上 */}
          <button autoFocus onClick={onCancel} style={btnBase}>{cancelText}</button>
          <button
            onClick={onConfirm}
            style={{
              ...btnBase,
              border: `1px solid ${danger ? 'var(--crit)' : 'var(--cyan)'}`,
              color: danger ? 'var(--crit)' : 'var(--cyan)',
            }}
          >
            {confirmText}
          </button>
        </div>
      </div>
    </Modal>
  )
}