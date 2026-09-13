import React, { useEffect, useRef, useState, useCallback } from 'react'
import { api } from '../api.js'

// 视觉闭环面板（D2）：单拍定位 / 环绕任务（异步 job 轮询）/ 目标航迹。
// 环绕是 2 分钟量级的飞行任务：POST 立即拿 jobId，这里 2s 轮询进度，
// 每站的检出与误差实时上屏，完成后自动刷新航迹。

const JOB_POLL_MS = 2000

export default function VisionPanel({ drone, onOrbitActive }) {
  const [capture, setCapture] = useState(null)
  const [capBusy, setCapBusy] = useState(false)

  const [form, setForm] = useState({ lat: 22.5916, lon: 113.9345, radiusM: 25, altM: 60, photos: 4 })
  const [job, setJob] = useState(null)
  const [jobErr, setJobErr] = useState(null)
  const pollRef = useRef(null)

  const [tracks, setTracks] = useState([])
  const sysid = drone?.sysid

  const stopPolling = useCallback(() => {
    if (pollRef.current) {
      clearInterval(pollRef.current)
      pollRef.current = null
    }
  }, [])

  // 轮询生命周期：job 终态即停
  useEffect(() => {
    if (!job || !job.jobId) return
    stopPolling()
    pollRef.current = setInterval(async () => {
      try {
        const j = await api.getOrbitJob(job.jobId)
        setJob(j)
        if (j.state === 'DONE' || j.state === 'FAILED' || j.state === 'TIMEOUT') {
          stopPolling()
          if (j.state === 'DONE') refreshTracks()
          if (onOrbitActive) onOrbitActive(false)
        }
      } catch (e) {
        setJobErr('任务查询失败: ' + e.message)
        stopPolling()
      }
    }, JOB_POLL_MS)
    return stopPolling
  }, [job?.jobId])

  useEffect(() => () => stopPolling(), [stopPolling])

  const refreshTracks = useCallback(async () => {
    if (sysid == null) return
    try {
      const t = await api.getTracks(sysid)
      setTracks(Array.isArray(t.tracks) ? t.tracks : [])
    } catch (e) {
      setTracks([])
    }
  }, [sysid])

  // 切机时清面板状态
  useEffect(() => {
    stopPolling()
    setJob(null); setJobErr(null); setCapture(null); setTracks([])
  }, [sysid])

  const doCapture = async () => {
    if (sysid == null || capBusy) return
    setCapBusy(true)
    try {
      const r = await api.triggerCapture(sysid)
      setCapture(r)
      refreshTracks()
    } catch (e) {
      setCapture({ status: 'error', result: e.message })
    } finally {
      setCapBusy(false)
    }
  }

  const doOrbit = async () => {
    if (sysid == null) return
    setJobErr(null); setJob(null)
    try {
      const r = await api.startOrbit(sysid, form)
      setJob(r)
      if (onOrbitActive) onOrbitActive(true, { center: { lat: form.lat, lon: form.lon }, radiusM: form.radiusM })
    } catch (e) {
      setJobErr(e.message)
    }
  }

  const disabled = sysid == null
  const detections = capture?.detections || []
  const jobState = job?.state
  const progress = job?.progress || []
  const jobResult = job?.result

  return (
    <section className="panel vision-panel">
      <h3 className="panel-title">视觉闭环</h3>

      {/* ---- 单拍 ---- */}
      <div className="vision-row">
        <button className="btn" onClick={doCapture} disabled={disabled || capBusy}>
          {capBusy ? '拍摄中…' : '单拍定位'}
        </button>
        {capture && (
          <div className="vision-cap-result">
            {capture.status === 'error' ? (
              <span className="bad-text">{capture.result || '失败'}</span>
            ) : (
              <>
                <span className="ok-text">{detections.length} 个目标</span>
                {detections.map((d) => (
                  <span key={d.id} className="chip mono small">
                    #{d.id} {d.kind} Δ{d.truthErrorM}m
                  </span>
                ))}
              </>
            )}
          </div>
        )}
      </div>

      {/* ---- 环绕 ---- */}
      <div className="vision-form">
        <label>中心
          <input type="number" step="0.0001" value={form.lat}
            onChange={(e) => setForm({ ...form, lat: +e.target.value })} />
          <input type="number" step="0.0001" value={form.lon}
            onChange={(e) => setForm({ ...form, lon: +e.target.value })} />
        </label>
        <label>半径 m
          <input type="number" min="1" max="500" value={form.radiusM}
            onChange={(e) => setForm({ ...form, radiusM: +e.target.value })} />
        </label>
        <label>高度 m
          <input type="number" min="20" max="120" value={form.altM}
            onChange={(e) => setForm({ ...form, altM: +e.target.value })} />
        </label>
        <label>张数
          <input type="number" min="2" max="12" value={form.photos}
            onChange={(e) => setForm({ ...form, photos: +e.target.value })} />
        </label>
        <button className="btn primary" onClick={doOrbit} disabled={disabled}>
          环绕任务
        </button>
      </div>

      {jobErr && <div className="vision-err bad-text">{jobErr}</div>}

      {job?.jobId && (
        <div className="vision-job">
          <div className="vision-job-head">
            <span className={`badge st-${(jobState || '').toLowerCase()}`}>{jobState}</span>
            <span className="mono">
              {job.photosTaken}/{job.photosRequested} 站
            </span>
            {job.error && <span className="bad-text small">{job.error}</span>}
          </div>
          {progress.map((p, i) => (
            <div key={i} className="vision-station mono">
              站{i + 1}: {p.detections?.length || 0} 目标
              {(p.detections || []).map((d, k) => (
                <span key={k} className="dim"> #{d.id}Δ{d.truthErrorM}m</span>
              ))}
            </div>
          ))}
          {jobState === 'DONE' && jobResult && (
            <div className="vision-summary">
              航迹 {(jobResult.tracks || []).length} 条 ·
              {' '}{(jobResult.tracks || []).filter(t => t.state === 'ACTIVE').length} 条 ACTIVE
            </div>
          )}
        </div>
      )}

      {/* ---- 航迹 ---- */}
      <div className="vision-tracks">
        <div className="vision-tracks-head">
          <h4>目标航迹</h4>
          <button className="btn small" onClick={refreshTracks} disabled={disabled}>
            刷新
          </button>
        </div>
        {tracks.length === 0 && <div className="dim small">暂无航迹（先拍一张）</div>}
        {tracks.map((t) => (
          <div key={t.trackId} className="vision-track">
            <span className={`badge st-${(t.state || '').toLowerCase()}`}>{t.state}</span>
            <span className="mono">#{t.trackId}</span>
            <span>{t.kind}</span>
            <span className="chip small mono">{t.hits} hits</span>
            <span className="dim small mono">
              {t.lastSeen?.lat?.toFixed(5)}, {t.lastSeen?.lon?.toFixed(5)}
            </span>
          </div>
        ))}
      </div>
    </section>
  )
}
