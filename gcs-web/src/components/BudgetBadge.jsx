// 预算模式标识徽章
// 在顶部导航栏展示当前丐版档位，完整版（mode=null）时不渲染。
// 经验来源：2026-09-17-react-mount-existing-components-export-signature-dialog-wrap（默认导出用法）
export default function BudgetBadge({ mode }) {
  if (!mode) return null
  const labels = { toy: '百元级', standard: '千元级', advanced: '进阶' }
  const colors = { toy: '#f50', standard: '#fa8c16', advanced: '#52c41a' }
  return (
    <span
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