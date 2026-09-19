// 预算模式标识徽章
// 在顶部导航栏展示当前丐版档位，完整版（mode=null）时不渲染。
// 经验来源：2026-09-17-react-mount-existing-components-export-signature-dialog-wrap（默认导出用法）
export default function BudgetBadge({ mode }) {
  if (!mode) return null
  const labels = { toy: '百元级', standard: '千元级', advanced: '进阶' }
  const colors = { toy: '#f50', standard: '#fa8c16', advanced: '#52c41a' }
  // aria-label 描述：用于屏幕阅读器朗读当前预算模式
  const ariaLabels = {
    toy: '预算模式: 丐版(toy)',
    standard: '预算模式: 标准(standard)',
    advanced: '预算模式: 高级(advanced)',
  }
  const ariaLabel = ariaLabels[mode] || `预算模式: ${mode}`
  return (
    <span
      role="status"
      aria-label={ariaLabel}
      title={ariaLabel}
      style={{
        display: 'inline-block',
        padding: '2px 8px',
        fontSize: '12px',
        fontWeight: 'bold',
        color: '#fff',
        backgroundColor: colors[mode] || '#999',
        borderRadius: '4px',
        marginLeft: '8px',
      }}
    >
      丐版·{labels[mode] || mode}
    </span>
  )
}