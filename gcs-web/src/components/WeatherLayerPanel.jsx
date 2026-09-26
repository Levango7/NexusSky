import React, { useState, useEffect, useRef, useCallback } from 'react'
import { api } from '../api.js'

const POLL_MS = 5000

const WEATHER_ICONS = {
  sunny: '☀',
  cloudy: '☁',
  rainy: '🌧',
  storm: '⛈',
  fog: '🌫',
  snowy: '❄',
  windy: '💨',
}

const WIND_DIR_LABELS = ['北', '东北', '东', '东南', '南', '西南', '西', '西北']

function getWindDirLabel(deg) {
  const idx = Math.round(deg / 45) % 8
  return WIND_DIR_LABELS[idx]
}

export default function WeatherLayerPanel() {
  const [envStatus, setEnvStatus] = useState(null)
  const [envAlerts, setEnvAlerts] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const [showWind, setShowWind] = useState(true)
  const [showTemp, setShowTemp] = useState(true)
  const [showTurbulence, setShowTurbulence] = useState(true)
  const canvasRef = useRef(null)
  const pollRef = useRef(null)

  const fetchData = useCallback(async () => {
    try {
      const [status, alerts] = await Promise.all([
        api.getEnvStatus(),
        api.getEnvAlerts(),
      ])
      setEnvStatus(status)
      setEnvAlerts(alerts.alerts || alerts || [])
      setError(null)
    } catch (e) {
      setError(e.message || '获取气象数据失败')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    fetchData()
    pollRef.current = setInterval(fetchData, POLL_MS)
    return () => clearInterval(pollRef.current)
  }, [fetchData])

  useEffect(() => {
    const canvas = canvasRef.current
    if (!canvas || !envStatus) return
    const ctx = canvas.getContext('2d')
    const w = canvas.width
    const h = canvas.height

    ctx.clearRect(0, 0, w, h)
    ctx.fillStyle = '#0b0e14'
    ctx.fillRect(0, 0, w, h)

    ctx.strokeStyle = '#232c40'
    ctx.lineWidth = 0.5
    for (let x = 0; x < w; x += 50) {
      ctx.beginPath(); ctx.moveTo(x, 0); ctx.lineTo(x, h); ctx.stroke()
    }
    for (let y = 0; y < h; y += 50) {
      ctx.beginPath(); ctx.moveTo(0, y); ctx.lineTo(w, y); ctx.stroke()
    }

    const stations = envStatus.stations || envStatus.points || []
    stations.forEach((st) => {
      const cx = ((st.lon - 113.93) * 800 + w / 2) % w
      const cy = ((22.59 - st.lat) * 800 + h / 2) % h

      if (showWind && st.windSpeed != null && st.windDir != null) {
        const arrowLen = Math.max(15, Math.min(50, st.windSpeed * 2))
        const rad = (st.windDir * Math.PI) / 180
        const dx = Math.sin(rad) * arrowLen
        const dy = -Math.cos(rad) * arrowLen

        ctx.strokeStyle = '#00d4ff'
        ctx.lineWidth = 2
        ctx.beginPath()
        ctx.moveTo(cx, cy)
        ctx.lineTo(cx + dx, cy + dy)
        ctx.stroke()

        const headLen = 6
        const headAng = Math.PI / 6
        ctx.beginPath()
        ctx.moveTo(cx + dx, cy + dy)
        ctx.lineTo(
          cx + dx - headLen * Math.sin(rad - headAng),
          cy + dy + headLen * Math.cos(rad - headAng)
        )
        ctx.moveTo(cx + dx, cy + dy)
        ctx.lineTo(
          cx + dx - headLen * Math.sin(rad + headAng),
          cy + dy + headLen * Math.cos(rad + headAng)
        )
        ctx.stroke()

        ctx.fillStyle = '#00d4ff'
        ctx.font = '9px monospace'
        ctx.fillText(`${st.windSpeed.toFixed(1)}m/s`, cx + dx + 4, cy + dy + 3)
      }

      if (showTemp && st.temp != null) {
        ctx.fillStyle = '#e8edf5'
        ctx.font = '10px monospace'
        ctx.fillText(`${st.temp.toFixed(1)}°C`, cx - 16, cy - 8)
        if (st.humidity != null) {
          ctx.fillStyle = '#2de2a5'
          ctx.fillText(`${st.humidity.toFixed(0)}%`, cx - 12, cy + 14)
        }
      }

      if (st.weather) {
        const icon = WEATHER_ICONS[st.weather] || '•'
        ctx.font = '14px sans-serif'
        ctx.fillStyle = '#ffc857'
        ctx.fillText(icon, cx - 7, cy + 4)
      }

      if (showTurbulence && st.turbulence) {
        ctx.strokeStyle = 'rgba(255,178,36,0.4)'
        ctx.lineWidth = 1.5
        ctx.setLineDash([4, 3])
        ctx.beginPath()
        ctx.arc(cx, cy, 25, 0, Math.PI * 2)
        ctx.stroke()
        ctx.setLineDash([])
        ctx.fillStyle = 'rgba(255,178,36,0.8)'
        ctx.font = '8px monospace'
        ctx.fillText('湍流', cx + 28, cy + 3)
      }
    })

    if (envStatus.turbulenceZones) {
      envStatus.turbulenceZones.forEach((zone) => {
        const zx = ((zone.lon - 113.93) * 800 + w / 2) % w
        const zy = ((22.59 - zone.lat) * 800 + h / 2) % h
        ctx.strokeStyle = 'rgba(255,178,36,0.3)'
        ctx.lineWidth = 2
        ctx.setLineDash([6, 4])
        ctx.beginPath()
        ctx.arc(zx, zy, Math.max(30, zone.radius || 40), 0, Math.PI * 2)
        ctx.stroke()
        ctx.setLineDash([])
      })
    }
  }, [envStatus, showWind, showTemp, showTurbulence])

  return (
    <div style={{ padding: 16, height: '100%', overflow: 'auto' }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 12 }}>
        <h2 style={{ margin: 0, fontSize: 18, color: 'var(--cyan)' }}>气象图层面板</h2>
        <div style={{ display: 'flex', gap: 6 }}>
          <button
            className={`btn ${showWind ? 'primary' : ''}`}
            style={{ padding: '3px 10px', fontSize: 10 }}
            onClick={() => setShowWind((v) => !v)}
          >
            风速风向
          </button>
          <button
            className={`btn ${showTemp ? 'primary' : ''}`}
            style={{ padding: '3px 10px', fontSize: 10 }}
            onClick={() => setShowTemp((v) => !v)}
          >
            温度湿度
          </button>
          <button
            className={`btn ${showTurbulence ? 'primary' : ''}`}
            style={{ padding: '3px 10px', fontSize: 10 }}
            onClick={() => setShowTurbulence((v) => !v)}
          >
            湍流区域
          </button>
        </div>
      </div>

      <canvas
        ref={canvasRef}
        width={600}
        height={400}
        style={{ width: '100%', height: 'auto', background: '#0b0e14', borderRadius: 8, border: '1px solid var(--border)', marginBottom: 12 }}
      />

      {envStatus && (
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(180px, 1fr))', gap: 8, marginBottom: 12 }}>
          <div style={{ padding: 8, borderRadius: 6, background: 'var(--card)', border: '1px solid var(--border)' }}>
            <div style={{ fontSize: 10, color: 'var(--dim)' }}>区域温度</div>
            <div style={{ fontSize: 16, color: 'var(--text)', fontWeight: 600 }}>
              {envStatus.temp != null ? `${envStatus.temp.toFixed(1)}°C` : '--'}
            </div>
          </div>
          <div style={{ padding: 8, borderRadius: 6, background: 'var(--card)', border: '1px solid var(--border)' }}>
            <div style={{ fontSize: 10, color: 'var(--dim)' }}>区域湿度</div>
            <div style={{ fontSize: 16, color: 'var(--ok)', fontWeight: 600 }}>
              {envStatus.humidity != null ? `${envStatus.humidity.toFixed(0)}%` : '--'}
            </div>
          </div>
          <div style={{ padding: 8, borderRadius: 6, background: 'var(--card)', border: '1px solid var(--border)' }}>
            <div style={{ fontSize: 10, color: 'var(--dim)' }}>风速</div>
            <div style={{ fontSize: 16, color: 'var(--cyan)', fontWeight: 600 }}>
              {envStatus.windSpeed != null ? `${envStatus.windSpeed.toFixed(1)} m/s` : '--'}
            </div>
          </div>
          <div style={{ padding: 8, borderRadius: 6, background: 'var(--card)', border: '1px solid var(--border)' }}>
            <div style={{ fontSize: 10, color: 'var(--dim)' }}>风向</div>
            <div style={{ fontSize: 16, color: 'var(--cyan)', fontWeight: 600 }}>
              {envStatus.windDir != null ? `${getWindDirLabel(envStatus.windDir)} ${envStatus.windDir.toFixed(0)}°` : '--'}
            </div>
          </div>
          <div style={{ padding: 8, borderRadius: 6, background: 'var(--card)', border: '1px solid var(--border)' }}>
            <div style={{ fontSize: 10, color: 'var(--dim)' }}>天气状态</div>
            <div style={{ fontSize: 16, fontWeight: 600 }}>
              {envStatus.weather ? `${WEATHER_ICONS[envStatus.weather] || ''} ${envStatus.weather}` : '--'}
            </div>
          </div>
        </div>
      )}

      {envAlerts.length > 0 && (
        <div>
          <h3 style={{ fontSize: 14, color: 'var(--warn)', marginBottom: 8 }}>气象告警</h3>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
            {envAlerts.map((alert, i) => (
              <div
                key={i}
                style={{
                  padding: 8,
                  borderRadius: 6,
                  background: alert.severity === 'critical' ? 'rgba(255,93,93,0.1)' : 'rgba(255,178,36,0.1)',
                  border: `1px solid ${alert.severity === 'critical' ? 'rgba(255,93,93,0.3)' : 'rgba(255,178,36,0.3)'}`,
                }}
              >
                <div style={{ fontSize: 12, fontWeight: 600, color: alert.severity === 'critical' ? 'var(--crit)' : 'var(--warn)' }}>
                  {alert.type || alert.title || '气象告警'}
                </div>
                <div style={{ fontSize: 10, color: 'var(--dim)', marginTop: 2 }}>
                  {alert.description || alert.message || ''}
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      {loading && <div style={{ textAlign: 'center', padding: 20, color: 'var(--dim)' }}>加载气象数据…</div>}
      {error && !loading && (
        <div style={{ textAlign: 'center', padding: 20, color: 'var(--crit)' }}>⚠ {error}</div>
      )}
    </div>
  )
}