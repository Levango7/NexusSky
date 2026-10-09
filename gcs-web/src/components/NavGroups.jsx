import React, { useEffect, useRef, useState } from 'react'
import { isPanelAvailable } from '../api.js'
import { VIEW_PANEL_MAP } from '../hooks/useUI.js'
import { VIEW_GROUPS, VIEW_LABELS } from '../nav/viewGroups.js'

/**
 * 顶栏分组导航。
 *
 * 45 个视图收进 8 个业务域：组按钮常驻一行（当前域高亮），组内视图进下拉菜单。
 * - 单视图组（总览）直接切换，不出菜单；
 * - 预算档位过滤沿用 VIEW_PANEL_MAP + isPanelAvailable：组内全被隐藏则整组隐藏；
 * - 交互可达性：组按钮带 aria-haspopup/aria-expanded，菜单项 role=menuitem，
 *   Esc 与点击外部关闭，Tab 可依次聚焦。
 */
export default function NavGroups({ view, onSelect, budgetMode, isAdmin }) {
  const [open, setOpen] = useState(null) // 展开的组 key | null
  const ref = useRef(null)

  useEffect(() => {
    if (!open) return undefined
    const onDocDown = (e) => {
      if (ref.current && !ref.current.contains(e.target)) setOpen(null)
    }
    const onKeyDown = (e) => {
      if (e.key === 'Escape') setOpen(null)
    }
    document.addEventListener('mousedown', onDocDown)
    document.addEventListener('keydown', onKeyDown)
    return () => {
      document.removeEventListener('mousedown', onDocDown)
      document.removeEventListener('keydown', onKeyDown)
    }
  }, [open])

  const groups = VIEW_GROUPS
    .filter((g) => !g.adminOnly || isAdmin)
    .map((g) => ({
      ...g,
      views: g.views.filter((v) => isPanelAvailable(VIEW_PANEL_MAP[v], budgetMode)),
    }))
    .filter((g) => g.views.length > 0)

  const activeKey = groups.find((g) => g.views.includes(view))?.key

  return (
    <nav className="nav-groups" ref={ref} aria-label="功能导航">
      {groups.map((g) => {
        const single = g.views.length === 1
        const isOpen = open === g.key
        return (
          <div className="nav-group" key={g.key}>
            <button
              type="button"
              className={`nav-group-btn ${activeKey === g.key ? 'active' : ''}`}
              aria-haspopup={single ? undefined : 'menu'}
              aria-expanded={single ? undefined : isOpen}
              title={g.views.map((v) => VIEW_LABELS[v]).join(' / ')}
              onClick={() => {
                if (single) {
                  onSelect(g.views[0])
                  setOpen(null)
                } else {
                  setOpen(isOpen ? null : g.key)
                }
              }}
            >
              {g.label}
              {!single && <span className="caret" aria-hidden="true" />}
            </button>
            {isOpen && (
              <div className="nav-menu" role="menu" aria-label={`${g.label}视图`}>
                {g.views.map((v) => (
                  <button
                    type="button"
                    key={v}
                    role="menuitem"
                    className={`nav-item ${view === v ? 'current' : ''}`}
                    onClick={() => {
                      onSelect(v)
                      setOpen(null)
                    }}
                  >
                    {VIEW_LABELS[v]}
                  </button>
                ))}
              </div>
            )}
          </div>
        )
      })}
    </nav>
  )
}
