import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import ScenarioRegistryPanel from '../src/components/ScenarioRegistryPanel.jsx'

const listBalconyDropPoints = vi.fn(() => Promise.resolve([]))
const listAgriSupply = vi.fn(() => Promise.resolve([]))
const listEmergencyRoles = vi.fn(() => Promise.resolve([]))

vi.mock('../src/api.js', () => ({
  listBalconyDropPoints: (...args) => listBalconyDropPoints(...args),
  listAgriSupply: (...args) => listAgriSupply(...args),
  listEmergencyRoles: (...args) => listEmergencyRoles(...args),
  createBalconyDropPoint: vi.fn(),
  updateBalconyDropPoint: vi.fn(),
  deleteBalconyDropPoint: vi.fn(),
  createAgriSupply: vi.fn(),
  updateAgriSupply: vi.fn(),
  deleteAgriSupply: vi.fn(),
  createEmergencyRole: vi.fn(),
  updateEmergencyRole: vi.fn(),
  deleteEmergencyRole: vi.fn(),
}))

describe('ScenarioRegistryPanel（V43 三表管理）', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    listBalconyDropPoints.mockResolvedValue([])
    listAgriSupply.mockResolvedValue([])
    listEmergencyRoles.mockResolvedValue([])
  })

  it('默认显示阳台落点，三张表各有分页签', async () => {
    render(<ScenarioRegistryPanel />)
    expect(screen.getByText('场景注册表')).toBeTruthy()
    for (const label of ['阳台落点', '农资流水', '应急节点']) {
      expect(screen.getByText(label)).toBeTruthy()
    }
    await waitFor(() => expect(listBalconyDropPoints).toHaveBeenCalled())
    expect(screen.getByText('暂无数据')).toBeTruthy()
  })

  it('切换页签会重新加载对应表，不复用上一张表的数据', async () => {
    render(<ScenarioRegistryPanel />)
    await waitFor(() => expect(listBalconyDropPoints).toHaveBeenCalled())
    fireEvent.click(screen.getByText('应急节点'))
    await waitFor(() => expect(listEmergencyRoles).toHaveBeenCalled())
    expect(screen.getByPlaceholderText('按预案ID过滤')).toBeTruthy()
  })
})
