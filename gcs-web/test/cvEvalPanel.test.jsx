/**
 * CvEvalPanel 的 byKind 拆分表（P1-6）。
 *
 * 这张表是整块「识别率/误检比/处理时间评测面板」里唯一能拆到人员/车辆粒度的数据源；
 * panelSmoke 只保证不白屏，这里专盯 byKind 行为：两行 kind + 对应数字真落到了 DOM。
 */
import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, screen, waitFor, cleanup } from '@testing-library/react'
import CvEvalPanel from '../src/components/CvEvalPanel.jsx'

afterEach(() => {
  vi.unstubAllGlobals()
  cleanup()
})

describe('CvEvalPanel byKind 拆分', () => {
  it('人员/车辆两行渲染，识别率/误检比来自 payload', async () => {
    vi.stubGlobal('fetch', vi.fn().mockImplementation((url) => {
      if (String(url).includes('/metrics')) {
        return Promise.resolve({
          ok: true,
          json: async () => ({
            frames: 41,
            recall: 0.875,
            falseDetectionRatio: 0.12,
            latencyAvgMs: 120.5,
            latencyP95Ms: 230.1,
            windowSize: 500,
            truePositives: 14,
            falsePositives: 4,
            truthTotal: 16,
            reference: { recall: 0.85, falseDetectionRatio: 0.15 },
            perFrame: [
              { source: 'pixels', frameSeq: 1, truthCount: 1, tp: 1, fp: 0, latencyMs: 80.0 },
            ],
            byKind: [
              { kind: 'person', truth: 10, truePositives: 9, falsePositives: 2,
                recall: 0.9, falseDetectionRatio: 0.18 },
              { kind: 'vehicle', truth: 6, truePositives: 5, falsePositives: 2,
                recall: 0.83, falseDetectionRatio: 0.29 },
            ],
          }),
        })
      }
      return Promise.resolve({ ok: false, status: 404, json: async () => ({}) })
    }))

    render(<CvEvalPanel />)
    await waitFor(() => expect(screen.getByText('person')).toBeTruthy())
    expect(screen.getByText('vehicle')).toBeTruthy()
    expect(screen.getByText('90.0%')).toBeTruthy()
    expect(screen.getByText('83.0%')).toBeTruthy()
  })

  it('truth=0 的 kind 也渲染自己的一行，recall 显示 --', async () => {
    vi.stubGlobal('fetch', vi.fn().mockImplementation((url) => {
      if (String(url).includes('/metrics')) {
        return Promise.resolve({
          ok: true,
          json: async () => ({
            frames: 3,
            recall: null,
            falseDetectionRatio: null,
            latencyAvgMs: null,
            latencyP95Ms: null,
            windowSize: 500,
            truePositives: 0,
            falsePositives: 1,
            truthTotal: 0,
            reference: { recall: 0.85, falseDetectionRatio: 0.15 },
            perFrame: [],
            byKind: [
              { kind: 'blob', truth: 0, truePositives: 0, falsePositives: 1,
                recall: null, falseDetectionRatio: 1.0 },
            ],
          }),
        })
      }
      return Promise.resolve({ ok: false, status: 404, json: async () => ({}) })
    }))

    render(<CvEvalPanel />)
    await waitFor(() => expect(screen.getByText('blob')).toBeTruthy())
    // '--' 出现在多个空指标里（识别率/误检比/处理时间），getAllByText 查存在即可
    expect(screen.getAllByText('--').length).toBeGreaterThan(0)
  })
})
