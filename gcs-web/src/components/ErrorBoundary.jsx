import React, { Fragment } from 'react'

/**
 * 组件级错误边界（P3-fix Minor）：捕获子组件渲染异常，
 * 显示降级 UI 而非整页白屏。支持 onError 回调上报错误。
 */
export default class ErrorBoundary extends React.Component {
  constructor(props) {
    super(props)
    this.state = { hasError: false, error: null, attempt: 0 }
  }

  static getDerivedStateFromError(error) {
    return { hasError: true, error }
  }

  componentDidCatch(error, errorInfo) {
    // 上报错误到回调（不使用 console.log，避免生产日志噪声）
    if (this.props.onError) {
      this.props.onError(error, errorInfo)
    }
  }

  componentDidUpdate(prevProps) {
    // 子组件被替换（如数据源切换）时旧错误已不适用，重置错误状态让新子树正常渲染
    if (prevProps.children !== this.props.children && this.state.hasError) {
      this.setState({ hasError: false, error: null })
    }
  }

  render() {
    if (this.state.hasError) {
      // 自定义降级 UI
      if (this.props.fallback) {
        return this.props.fallback(this.state.error)
      }
      return (
        <div className="error-boundary-fallback" role="alert">
          <div className="error-boundary-icon">⚠</div>
          <div className="error-boundary-text">
            <b>组件渲染异常</b>
            <span className="error-boundary-detail">
              {this.state.error?.message || '未知错误'}
            </span>
          </div>
          <button
            className="btn"
            onClick={() => this.setState((s) => ({ hasError: false, error: null, attempt: s.attempt + 1 }))}
            aria-label="重试加载组件"
          >
            重试
          </button>
        </div>
      )
    }
    // attempt 计数作为 key：重试时 key 变化强制 React 卸载并重建整个子树，
    // 避免仅清除错误标志后子组件带着脏 state 复用导致立即再次崩溃
    return <Fragment key={this.state.attempt}>{this.props.children}</Fragment>
  }
}