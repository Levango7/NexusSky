import React from 'react'
import { render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import {
  fetchStreamToken,
  getDeviceStream,
  listSurveillanceDevices,
  listSurveillanceEvents,
  mjpegPreviewUrl,
} from '../src/api.js'
import SurveillancePanel from '../src/components/SurveillancePanel.jsx'

// mjpegPreviewUrl 的真函数口径在 src/api.test.js —— 本文件整体 mock 了 api.js，
// 拿这里的 mock 实现去测 URL 拼装等于自己测自己。

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
  registerSurveillanceDevice: vi.fn(),
  unregisterSurveillanceDevice: vi.fn(),
  fetchStreamToken: vi.fn(),
  mjpegPreviewUrl: vi.fn(),
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
    // 默认没有帧源 -> 不可播。若某例要测预览，显式把 browserPlayable 打开。
    fetchStreamToken.mockResolvedValue('st-abc')
    mjpegPreviewUrl.mockImplementation(
      (id, token, fps) => `https://gcs.test/api/v1/surveillance/devices/${id}/mjpeg?streamToken=${token}&fps=${fps}`,
    )
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

// ══════════════════════════════════════════════════════════════════════════
// MJPEG 预览：把"安防视频完全看不了"变成"能看"的那一步。
//
// 与上面那组的关键区别：那组的后端只产 rtsp://，浏览器不能播，所以只能验证
// "没有谎称能播"。这里有一条真能播的路径，因此断言必须落在**真的挂了 <img>**
// 上，而不是落在"某段说明文字出现了"上 —— 后者改个文案就能骗过。
// ══════════════════════════════════════════════════════════════════════════
describe('#110 SurveillancePanel MJPEG 预览', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    listSurveillanceEvents.mockResolvedValue({ items: [] })
    fetchStreamToken.mockResolvedValue('st-abc')
    mjpegPreviewUrl.mockImplementation(
      (id, token, fps) => `https://gcs.test/api/v1/surveillance/devices/${id}/mjpeg?streamToken=${token}&fps=${fps}`,
    )
    getDeviceStream.mockResolvedValue({
      deviceId: 'cam-live',
      channel: 1,
      rtspUrl: 'rtsp://admin:***@192.168.1.64:554/Streaming/Channels/1',
      streamUrlOrigin: 'VENDOR_STANDARD_FORMAT',
      playable: true,
      deviceVerified: false,
    })
  })

  /**
   * 主线断言：配了帧源的设备，面板必须挂出 <img src={mjpeg...}>。
   * 这条在实现之前不可能绿 —— 此前组件里根本没有 mjpeg 这条分支。
   */
  it('browserPlayable 时挂出预览 <img>（此前完全没有这条路径）', async () => {
    listSurveillanceDevices.mockResolvedValue([{ ...DEVICE, browserPlayable: true }])

    render(<SurveillancePanel />)
    await assignDevices()

    const img = await screen.findByTestId('mjpeg-preview')
    expect(img.tagName).toBe('IMG')
    expect(img.getAttribute('src')).toBe(
      'https://gcs.test/api/v1/surveillance/devices/cam-1/mjpeg?streamToken=st-abc&fps=5',
    )
  })

  /** <img> 带不了认证头，所以必须先换短令牌；没换就挂 = 生产链上必然 401。 */
  it('挂预览前先换短令牌，并把令牌交给 URL 构造函数', async () => {
    listSurveillanceDevices.mockResolvedValue([{ ...DEVICE, browserPlayable: true }])

    render(<SurveillancePanel />)
    await assignDevices()
    await screen.findByTestId('mjpeg-preview')

    expect(fetchStreamToken).toHaveBeenCalledTimes(1)
    expect(mjpegPreviewUrl).toHaveBeenCalledWith('cam-1', 'st-abc', 5)
  })

  /** 没有帧源就不许挂预览 —— 否则是一个永远转圈的格子。 */
  it('没有 browserPlayable 时不挂预览，且仍如实说明 RTSP 需转码', async () => {
    listSurveillanceDevices.mockResolvedValue([{ ...DEVICE, browserPlayable: false }])

    render(<SurveillancePanel />)
    await assignDevices()

    await waitFor(() => expect(screen.getByText(/RTSP 流需后端转码/)).toBeTruthy())
    expect(screen.queryByTestId('mjpeg-preview')).toBeNull()
    expect(fetchStreamToken).not.toHaveBeenCalled()
  })

  /**
   * 有预览时不得再叠加"RTSP 流需后端转码" —— 那句话描述的是"没有别的办法"，
   * 现在有办法了，两句同时出现就是自相矛盾。
   */
  it('有预览时不再叠加「RTSP 流需后端转码」提示', async () => {
    listSurveillanceDevices.mockResolvedValue([{ ...DEVICE, browserPlayable: true }])

    render(<SurveillancePanel />)
    await assignDevices()
    await screen.findByTestId('mjpeg-preview')

    expect(screen.queryByText(/RTSP 流需后端转码/)).toBeNull()
  })

  /**
   * 换不到令牌就说清楚。绝不能把一个必然 401 的地址挂上去 ——
   * 那正是"看起来在播、实际什么都没有"的一种。
   */
  it('换不到预览令牌时报错，不挂预览', async () => {
    listSurveillanceDevices.mockResolvedValue([{ ...DEVICE, browserPlayable: true }])
    fetchStreamToken.mockRejectedValue(new Error('401'))

    render(<SurveillancePanel />)
    await assignDevices()

    await waitFor(() => expect(screen.getByText(/无法获取预览令牌/)).toBeTruthy())
    expect(screen.queryByTestId('mjpeg-preview')).toBeNull()
  })

  /**
 * 令牌是**单次用**的，所以每台设备必须各拿各的一枚。
 * 若两台设备共用一枚令牌，第二台必然 401 —— 而界面上只表现为"一台有画面一台没有"，
 * 很容易被当成设备问题排查半天。
 */
  it('多设备各自换令牌，不跨设备复用（单次令牌复用会串号）', async () => {
    listSurveillanceDevices.mockResolvedValue([
      { ...DEVICE, id: 'cam-a', browserPlayable: true },
      { ...DEVICE, id: 'cam-b', browserPlayable: true },
    ])
    let n = 0
    fetchStreamToken.mockImplementation(async () => `st-${++n}`)

    render(<SurveillancePanel />)
    await assignDevices()

    await waitFor(() => expect(screen.getAllByTestId('mjpeg-preview')).toHaveLength(2))
    const srcs = screen.getAllByTestId('mjpeg-preview').map((el) => el.getAttribute('src'))
    expect(srcs.some((s) => s.includes('/devices/cam-a/mjpeg') && s.includes('st-1'))).toBe(true)
    expect(srcs.some((s) => s.includes('/devices/cam-b/mjpeg') && s.includes('st-2'))).toBe(true)
    expect(fetchStreamToken).toHaveBeenCalledTimes(2)
  })
})


// =============================================================================
// #112：guessProtocol 的 hls / mjpeg / image 三个分支 —— 今天没有任何生产者
//
// 现状（实测核对过，不是凭记忆）：后端产出"流地址"的地方只有 rtspUrl 一处，
// 值域是 `rtsp://…`（私有协议/ONVIF 推导）或 `gb28181-invite://…`（描述性标识）。
// 全仓没有任何地方产 `.m3u8` 或图片地址，所以 guessProtocol 实际只会返回
// 'rtsp' 或 null —— 这三个分支今天是死分支。
//
// 那为什么不删？因为它们是**已有能力**，不是错误的实现：接上 HLS 转封装或
// 快照生产者时应当立刻可用。删了要重新写一遍，能力就真没了。所以按"不做减法"
// 的口径用测试钉住它们 —— 下面的用例在当前后端下**全部通过**，一旦将来有生产者
// 接上而分支已经腐坏（比如把 hls.js 加载删了、或者判断顺序被改动），它们会先红。
//
// 最后一条（顺序不变式）尤其重要：guessProtocol 先判 rtsp:// 再判 mjpeg，
// 所以路径里带 "mjpeg" 的 RTSP 地址仍走 rtsp 分支。若顺序被调换，
// RTSP 会被当成图片挂上 <img> —— 而浏览器根本播不了 rtsp://，结果是一张死图。
// =============================================================================
describe('#112 guessProtocol 的 hls / mjpeg / image 三个分支（当前无生产者，但必须保持可用）', () => {
  let hlsLoadSource
  let hlsDestroy

  beforeEach(() => {
    vi.clearAllMocks()
    listSurveillanceEvents.mockResolvedValue({ items: [] })
    fetchStreamToken.mockResolvedValue('st-abc')
    mjpegPreviewUrl.mockImplementation(
      (id, token, fps) => `https://gcs.test/api/v1/surveillance/devices/${id}/mjpeg?streamToken=${token}&fps=${fps}`,
    )
    hlsLoadSource = vi.fn()
    hlsDestroy = vi.fn()
    // 装一个 window.Hls，组件就不会去注入 <script>，而直接 new 它
    window.Hls = class {
      constructor() { this.Events = { MANIFEST_PARSED: 'manifestParsed', ERROR: 'error' } }
      loadSource(u) { hlsLoadSource(u) }
      attachMedia() {}
      on() {}
      destroy() { hlsDestroy() }
    }
  })

  afterEach(() => {
    delete window.Hls
  })

  const withStream = (rtspUrl, extra = {}) => {
    listSurveillanceDevices.mockResolvedValue([{ ...DEVICE, browserPlayable: false }])
    getDeviceStream.mockResolvedValue({
      deviceId: 'cam-1',
      channel: 1,
      rtspUrl,
      playable: true,
      deviceVerified: false,
      ...extra,
    })
  }

  it('.m3u8 走 HLS 分支：交给 hls.js 加载，而不是当成未知协议', async () => {
    withStream('https://cdn.test/live/cam1/index.m3u8')

    render(<SurveillancePanel />)
    await assignDevices()

    await waitFor(() => expect(hlsLoadSource).toHaveBeenCalled())
    expect(hlsLoadSource).toHaveBeenCalledWith('https://cdn.test/live/cam1/index.m3u8')
    // 认出来了就不能再显示"无法识别"
    expect(screen.queryByText(/无法识别的流地址协议/)).toBeNull()
  })

  it('.jpg 快照地址走 <img> 分支', async () => {
    withStream('http://192.168.1.64/snapshot.jpg')

    render(<SurveillancePanel />)
    await assignDevices()

    const img = await screen.findByAltText(/西门口球机/)
    expect(img.tagName).toBe('IMG')
    expect(img.getAttribute('src')).toBe('http://192.168.1.64/snapshot.jpg')
    expect(screen.queryByText(/无法识别的流地址协议/)).toBeNull()
  })

  it('非 rtsp 且含 mjpeg 的地址走 <img> 分支', async () => {
    withStream('http://192.168.1.64/mjpeg/stream')

    render(<SurveillancePanel />)
    await assignDevices()

    const img = await screen.findByAltText(/西门口球机/)
    expect(img.tagName).toBe('IMG')
    expect(screen.queryByText(/无法识别的流地址协议/)).toBeNull()
  })

  /**
   * 顺序不变式：rtsp:// 的判定必须**先于** mjpeg。
   * 路径里带 "mjpeg" 的 RTSP 地址若被当成图片，会挂一个 <img src="rtsp://…"> ——
   * 浏览器播不了 rtsp://，界面上就是一张永远不更新的死图。
   */
  it('RTSP 地址即使路径含 mjpeg 仍走 rtsp 分支（不得误判成图片）', async () => {
    withStream('rtsp://admin:***@192.168.1.64:554/mjpeg/Streaming/Channels/1')

    render(<SurveillancePanel />)
    await assignDevices()

    await waitFor(() => expect(screen.getByText(/RTSP 流需后端转码/)).toBeTruthy())
    // 没有把 rtsp:// 挂到 <img> 上
    expect(screen.queryByAltText(/西门口球机/)).toBeNull()
    expect(hlsLoadSource).not.toHaveBeenCalled()
  })
})
