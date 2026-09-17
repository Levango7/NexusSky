import React from 'react'

/**
 * 组件级错误边界（P3-fix Minor）：捕获子组件渲染异常，
 * 显示降级 UI 而非整页白屏。支持 onError 回调上报错误。
 */
export default class ErrorBoundary extends React.Component {
  constructor(props) {
    super(props)
    this.state = { hasError: false, error: null }
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
            onClick={() => this.setState({ hasError: false, error: null })}
            aria-label="重试加载组件"
          >
            重试
          </button>
        </div>
      )
    }
    return this.props.children
  }
}