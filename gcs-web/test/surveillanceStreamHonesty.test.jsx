import React from 'react'
import { render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import {
  getDeviceStream,
  listSurveillanceDevices,
  listSurveillanceEvents,
} from '../src/api.js'
import SurveillancePanel from '../src/components/SurveillancePanel.jsx'

// panelSmoke.test.jsx 把 SurveillancePanel 整个跳过了，理由是「依赖 window.Hls
// 与真实 video 播放，jsdom 无法播放」。但本文件要测的**与播放无关**：
//   - 后端返回 rtspUrl，组件此前读 url —— 键名不符会让取流分支恒不执行
//   - 后端声明 playable=false 时不得挂播放器
//   - 认不出的协议不得被谎称成 HLS
// 这些都是纯数据/条件分支，jsdom 完全能测。把它们一起跳过，正是那个键名
// 缺陷能长期存活的原因。

vi.mock('../src/api.js', () => ({
  getDeviceStream: vi.fn(),
  listSurveillanceDevices: vi.fn(),
  listSurveillanceEvents: vi.fn(),
  ptzControl: vi.fn(),
  discoverDevices: vi.fn(),
  registerDevice: vi.fn(),
  deleteDevice: vi.fn(),
  getDeviceStatus: vi.fn(),
  setDeviceOnlineCameras: vi.fn(),
  subscribeDeviceEvents: vi.fn(),
}))

const DEVICE = {
  id: 'cam-1',
  name: '西门口球机',
  vendor: 'HIKVISION',
  status: 'ONLINE',
  totalCameras: 1,
  onlineCameras: 1,
}

// 画面格子只在「自动排布」按钮被点过之后才有设备（autoAssign 仅由该按钮触发），
// 否则所有格子都是 device=null，取流逻辑根本不会执行。
const assignDevices = async () => {
  const btn = await screen.findByText('自动排布')
  btn.click()
}

describe('#109 SurveillancePanel 取流：键名与诚实度', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    listSurveillanceDevices.mockResolvedValue([DEVICE])
    listSurveillanceEvents.mockResolvedValue({ items: [] })
    // RTSP 需要后端转码，因此只需确认"读到了地址"与"没有谎称可播"，
    // 不需要真的播放（jsdom 也播不了）
    getDeviceStream.mockResolvedValue({
      deviceId: 'cam-1',
      channel: 1,
      rtspUrl: 'rtsp://admin:***@192.168.1.64:554/Streaming/Channels/1',
      streamUrlOrigin: 'VENDOR_STANDARD_FORMAT',
      playable: true,
      deviceVerified: false,
    })
  })

  /**
   * 核心用例：后端返回的键是 `rtspUrl`。组件此前读 `stream.url` ⇒ undefined ⇒
   * isImg/isHls/isRtsp 三分支全假 ⇒ 格子空着，且 `if (!url) return` 让挂载逻辑
   * 从未执行。这条断言必须在**读取 `rtspUrl`** 时才可能绿。
   */
  it('读后端的 rtspUrl 键（此前读 url，恒为空导致分支全假）', async () => {
    render(<SurveillancePanel />)
    await assignDevices()

    // RTSP 分支：读到地址后会显示「需后端转码」
    await waitFor(() => expect(screen.getByText(/RTSP 流需后端转码/)).toBeTruthy())
  })

  it('未向设备校验过的地址必须如实标注，不能当成已确认可用', async () => {
    render(<SurveillancePanel />)
    await assignDevices()

    await waitFor(() => expect(screen.getByText(/未向设备校验/)).toBeTruthy())
  })

  /** 后端声明不可播（GB28181 媒体面未接入）时不得挂播放器，须说明原因。 */
  it('playable=false 时说明「放不出画面」，而不是去挂播放器', async () => {
    getDeviceStream.mockResolvedValue({
      deviceId: 'cam-gb',
      channel: 1,
      rtspUrl: 'gb28181-invite://34020000001321320001?channel=1',
      streamUrlOrigin: 'DESCRIPTIVE_ONLY',
      playable: false,
      deviceVerified: false,
    })
    listSurveillanceDevices.mockResolvedValue([
      { ...DEVICE, id: 'cam-gb', name: '国标设备' },
    ])

    render(<SurveillancePanel />)
    await assignDevices()

    await waitFor(() => expect(screen.getByText(/放不出画面/)).toBeTruthy())
    // 不可播 ⇒ 不得出现「需后端转码」这种把地址当可播的表述
    expect(screen.queryByText(/RTSP 流需后端转码/)).toBeNull()
  })

  /**
   * `guessProtocol` 此前对认不出的 scheme 一律返回 'hls' ——
   * 于是后端"这个地址不可播"的诚实声明被当成 HLS 去播，
   * 用者只看到一个语焉不详的播放失败。
   */
  it('认不出的协议不谎称 HLS，如实说明无法识别', async () => {
    getDeviceStream.mockResolvedValue({
      deviceId: 'cam-x',
      channel: 1,
      rtspUrl: 'weird-scheme://host/stream',
      playable: true,
      deviceVerified: true,
    })
    listSurveillanceDevices.mockResolvedValue([
      { ...DEVICE, id: 'cam-x', name: '怪协议设备' },
    ])

    render(<SurveillancePanel />)
    await assignDevices()

    await waitFor(() => expect(screen.getByText(/无法识别的流地址协议/)).toBeTruthy())
  })

  /** 兼容键：若将来后端改回 url 也不能坏。 */
  it('兼容 url 键（后端若改回旧键位仍可工作）', async () => {
    getDeviceStream.mockResolvedValue({
      deviceId: 'cam-1',
      channel: 1,
      url: 'rtsp://admin:***@10.0.0.9:554/live',
      playable: true,
      deviceVerified: false,
    })

    render(<SurveillancePanel />)
    await assignDevices()

    await waitFor(() => expect(screen.getByText(/RTSP 流需后端转码/)).toBeTruthy())
  })
})