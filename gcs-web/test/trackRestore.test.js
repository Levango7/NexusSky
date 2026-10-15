/**
 * C4 前端接线：重启后的轨迹不应只剩一个点。
 *
 * 背景（本轮实测，不是推测）：
 *   - 后端**早有**重启安全的历史通路：`GET /api/v1/flightlog/track?day=&sysid=`
 *     从 flight_log 出 `{lat, lon, alt, ts}`（FlightLogController.java:47）。
 *   - 但前端**一条都没接**：`api.js` 只绑了 `/flightlog` 列表（:193）；
 *     地图折线吃的是 `useWebSocket` 实时追加的 `multiTracks`，
 *     `useDrones` 把它截到最近 1500 点，且**从不从后端播种**。
 *   - 后端侧 `FlightTrackStore.loadLastKnownPositions()` 重启时只塞 **1 个**点。
 *   ⇒ 症状：重启（或刷新页面）后地图上的历史轨迹消失，只剩最后位置一个点。
 *
 * 两侧点形状一致（都是 `{lat, lon, alt, ts}`），所以合并无需键名转换——
 * 这一点由下面的用例钉住，避免有人误以为要写 ts/timestampMs 适配层。
 */
import { describe, it, expect, vi, afterEach } from 'vitest'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
const SRC = join(HERE, '..', 'src')

// api.js 加载时会读 location（见 src/api.test.js 文件头说明），必须先打桩再动态 import。
// 注意取的是**命名导出 `api` 这个对象**（api.js:138 `export const api = {...}`），
// 不是模块命名空间——写成 `const api = await import(...)` 会拿到 namespace，
// 上面并没有 getDayTrack，测试会报一个和实现无关的错。
vi.stubGlobal('location', { protocol: 'https:', host: 'gcs.example.test' })
const { api } = await import('../src/api.js')

const { mergeTrack, MAX_TRACK_POINTS } = await import('../src/utils/trackMerge.js')

/** 造一个轨迹点；alt 用来区分"同 ts 时到底保留了哪一侧的对象"。 */
const p = (ts, alt = 10, extra = {}) => ({ lat: 30 + ts / 1e6, lon: 120 + ts / 1e6, alt, ts, ...extra })

const tsOf = (list) => list.map((x) => x.ts)

describe('mergeTrack：内存 live 点 + 库内当日历史', () => {
  it('重启场景：内存只有 1 个恢复点，合并当天库内 5 点后拿到完整折线', () => {
    const live = [p(5000, 99)]               // loadLastKnownPositions 塞的那一个点
    const persisted = [p(1000), p(2000), p(3000), p(4000), p(5000, 99)]

    const merged = mergeTrack(live, persisted)

    expect(merged).toHaveLength(5)
    expect(tsOf(merged)).toEqual([1000, 2000, 3000, 4000, 5000])
  })

  it('同一 ts 保留**传入的那个内存对象本身**（不重建、不丢字段）', () => {
    // 注：两个后端端点目前都只出 {lat,lon,alt,ts}（DroneViews.java:114-122、
    // FlightLogController.java:52-55）。这里带上额外字段是钉住一条契约：
    // mergeTrack 不得自己 new 一个点丢字段——将来点形状加字段时才不会静默截肢。
    const live = [p(3000, 77, { heading: 45, batteryPct: 60 })]
    const persisted = [p(3000, 1)]

    const merged = mergeTrack(live, persisted)

    expect(merged).toHaveLength(1)
    expect(merged[0].alt).toBe(77)          // 来自 live，不是 persisted 的 1
    expect(merged[0].heading).toBe(45)      // 额外字段必须活着
    expect(merged[0].batteryPct).toBe(60)
  })

  it('库内点乱序也要产出升序折线（后端两分支不保证同一顺序）', () => {
    const merged = mergeTrack([p(4000)], [p(2000), p(1000), p(3000)])

    expect(tsOf(merged)).toEqual([1000, 2000, 3000, 4000])
  })

  it('结果里不出现重复 ts（重复点会让 LineString 退化出零长段）', () => {
    const merged = mergeTrack([p(1000), p(1000), p(2000)], [p(1000), p(2000), p(2000)])

    const ts = tsOf(merged)
    expect(new Set(ts).size).toBe(ts.length)
  })

  it('超过 limit 时保留**最新**的 N 个，不是最旧的 N 个', () => {
    const many = Array.from({ length: 20 }, (_, i) => p(i * 100))

    const merged = mergeTrack([], many, 5)

    expect(tsOf(merged)).toEqual([1500, 1600, 1700, 1800, 1900])
  })

  it('limit<=0 表示不截断（与后端 /track 的 limit<=0 口径一致）', () => {
    const many = Array.from({ length: MAX_TRACK_POINTS + 10 }, (_, i) => p(i))

    expect(mergeTrack([], many, 0)).toHaveLength(MAX_TRACK_POINTS + 10)
  })

  it('空库 + 有内存 ⇒ 原样返回，不退化成空（历史缺失不应抹掉实时轨迹）', () => {
    const live = [p(1000), p(2000)]

    expect(tsOf(mergeTrack(live, []))).toEqual([1000, 2000])
  })

  it('缺 ts / ts 非有限的点被丢弃，且不把 NaN 带进折线', () => {
    const merged = mergeTrack(
      [{ lat: 1, lon: 1 }, { lat: 1, lon: 1, ts: NaN }, p(3000)],
      [{ lat: 1, lon: 1, ts: 'oops' }, p(4000)],
    )

    expect(tsOf(merged)).toEqual([3000, 4000])
    expect(merged.every((x) => Number.isFinite(x.ts))).toBe(true)
  })

  it('不修改入参数组（调用方的 state 被就地改会触发 React 不重渲染）', () => {
    const live = [p(2000), p(1000)]
    const persisted = [p(500)]
    const liveBefore = [...live]
    const persistedBefore = [...persisted]

    mergeTrack(live, persisted)

    expect(live.map((x) => x.ts)).toEqual(liveBefore.map((x) => x.ts))
    expect(persisted.map((x) => x.ts)).toEqual(persistedBefore.map((x) => x.ts))
  })
})

describe('api.getDayTrack：接上已存在的历史通路', () => {
  afterEach(() => vi.unstubAllGlobals())

  const captureUrl = async () => {
    let seen = null
    vi.stubGlobal('fetch', vi.fn(async (url) => {
      seen = String(url)
      return {
        ok: true,
        status: 200,
        headers: { get: () => 'application/json' },
        json: async () => [],
      }
    }))
    return () => seen
  }

  it('走同源 /api/v1/flightlog/track，带 sysid 与 day', async () => {
    const seen = await captureUrl()

    await api.getDayTrack(7, '2026-10-14')

    expect(seen()).toContain('/api/v1/flightlog/track')
    const u = new URL(seen(), 'https://gcs.example.test')
    expect(u.searchParams.get('sysid')).toBe('7')
    expect(u.searchParams.get('day')).toBe('2026-10-14')
  })

  it('不传 day 时不写成 day=undefined（交给后端按当天解释）', async () => {
    const seen = await captureUrl()

    await api.getDayTrack(7)

    expect(seen()).not.toContain('day=undefined')
    expect(new URL(seen(), 'https://gcs.example.test').searchParams.has('day')).toBe(false)
  })
})

describe('单一真相源守卫', () => {
  it('MAX_TRACK_POINTS 只在共享模块里定义，useDrones 引用它而不是再写一份', () => {
    const hook = readFileSync(join(SRC, 'hooks', 'useDrones.js'), 'utf-8')

    expect(hook).toMatch(/import\s*\{[^}]*MAX_TRACK_POINTS[^}]*\}\s*from\s*['"][^'"]*utils\/trackMerge(?:\.js)?['"]/)
    // 反向：不允许在 hook 里重新定义同名常量（改一处不改另一处=同一个数显示两套窗口）
    expect(hook).not.toMatch(/const\s+MAX_TRACK_POINTS\s*=/)
  })

  it('共享模块导出的窗口值就是原先 hook 用的 1500（行为不应被搬移动过）', () => {
    expect(MAX_TRACK_POINTS).toBe(1500)
  })
})
