#!/usr/bin/env node
// Playwright E2E 的后端编排：拉起 cloud-backend（dev profile），等它健康，然后停掉。
//
// 为什么单独一个脚本而不是塞进 playwright.config 的 webServer：
//  ① webServer 只支持一条命令 + 一个 URL 判活；后端是 Java 进程，冷启动 30-50s、
//     日志要落盘便于失败取证、端口要挑空闲的——这些在 Node 编排里才写得清楚；
//  ② 后端生命周期独立于 vite preview，两者可分别重跑而互不牵连；
//  ③ 失败时把 backend 日志尾部打进 stdout，不用再去 %TEMP% 里翻。
//
// 端口：默认 18099（避开本机 8080——常被常驻容器占用；这是 NexusSky 之外的占用，
// 不要去 kill 它）。可用 AF_BACKEND_PORT 覆盖。
// profile：dev（H2 + dev-mode 白名单），否则 prod profile 会 fail-fast 要求 PG + JWT 环境变量。

import { spawn } from 'node:child_process'
import { existsSync, readFileSync, writeFileSync, mkdirSync } from 'node:fs'
import { join, dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { setTimeout as sleep } from 'node:timers/promises'

// 脚本名沿用历史（start-backend），职责是「拉起 E2E 需要的全部真进程」。
const here = dirname(fileURLToPath(import.meta.url))
const gcsRoot = resolve(here, '..')
const repoRoot = resolve(gcsRoot, '..')
const logDir = join(gcsRoot, '.e2e-logs')
const backendLog = join(logDir, 'backend.log')
const simLog = join(logDir, 'drone-sim.log')

const PORT = Number(process.env.AF_BACKEND_PORT || 18099)
const SIM_PORT = Number(process.env.AF_SIM_PORT || 14542)
const SIM_HTTP_PORT = Number(process.env.AF_SIM_HTTP_PORT || 18080)
const DRONE_SYSID = Number(process.env.AF_DRONE_SYSID || 9)
const JAR = join(repoRoot, 'cloud-backend', 'target', 'aerofleet-cloud-backend-0.1.0-SNAPSHOT.jar')
const SIM_JAR = join(repoRoot, 'drone-sim', 'target', 'aerofleet-drone-sim-0.1.0-SNAPSHOT-shaded.jar')

function javaExe() {
  if (process.env.AF_JAVA) return process.env.AF_JAVA
  const home = process.env.JAVA_HOME
  if (home && existsSync(join(home, 'bin', 'java.exe'))) return join(home, 'bin', 'java.exe')
  return 'java.exe' // JDK8 会报 class file 52.0——那正是"没显式指定 JDK17"的症状
}

/** 后端健康：设备列表能返回即视为就绪（dev profile 匿名放行）。 */
async function waitHealthy(timeoutMs = 150_000) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    try {
      const res = await fetch(`http://127.0.0.1:${PORT}/api/v1/drones`, {
        signal: AbortSignal.timeout(3000),
      })
      if (res.ok) return true
    } catch { /* 还没起来，继续等 */ }
    await sleep(1000)
  }
  return false
}

function tailLog(n = 25) {
  try {
    const lines = readFileSync(backendLog, 'utf8').split(/\r?\n/).filter(Boolean)
    return lines.slice(-n).join('\n')
  } catch {
    return '(无日志)'
  }
}

mkdirSync(logDir, { recursive: true })

for (const [jar, what] of [[JAR, 'cloud-backend'], [SIM_JAR, 'drone-sim']]) {
  if (!existsSync(jar)) {
    console.error(`[e2e] 找不到 ${what} jar：${jar}\n[e2e] 先构建：cd ${repoRoot} && mvn -pl cloud-backend,drone-sim -am package -DskipTests`)
    process.exit(1)
  }
}

// 模拟器先起：它只等 GCS 心跳，不需要后端先就绪；反之则要等后端 UDP 网关
// 起来才连得上，顺序反了会白等一轮。
console.log(`[e2e] 启动 drone-sim（UDP ${SIM_PORT}，真值 HTTP ${SIM_HTTP_PORT}，sysid ${DRONE_SYSID}）...`)
const sim = spawn(javaExe(), [
  '-jar', SIM_JAR,
  '--port', String(SIM_PORT),
  '--sysid', String(DRONE_SYSID),
  '--name', 'AF-E2E-01',
  '--http-port', String(SIM_HTTP_PORT),
  '--targets', 'static:22.5916,113.9345;static:22.5916,113.93479',
], { stdio: ['ignore', 'pipe', 'pipe'] })

writeFileSync(simLog, '')
const simLogLine = (buf) => {
  try { writeFileSync(simLog, buf, { flag: 'a' }) } catch { /* 日志失败不杀进程 */ }
}
sim.stdout.on('data', simLogLine)
sim.stderr.on('data', simLogLine)

console.log(`[e2e] 启动 cloud-backend（dev profile，端口 ${PORT}）...`)
const child = spawn(javaExe(), [
  '-jar', JAR,
  `--server.port=${PORT}`,
  '--spring.profiles.active=dev',
  '--aerofleet.security.jwt-secret=e2e-test-secret-0123456789abcdef0123456789abcdef',
  `--aerofleet.drone-port=${SIM_PORT}`,
], { stdio: ['ignore', 'pipe', 'pipe'] })

writeFileSync(backendLog, '')
const logLine = (buf) => {
  try { writeFileSync(backendLog, buf, { flag: 'a' }) } catch { /* 日志失败不杀进程 */ }
}
child.stdout.on('data', logLine)
child.stderr.on('data', logLine)

/** 停一个子进程：SIGTERM → 最多等 5s（让 JVM 走 @PreDestroy）→ SIGKILL。 */
async function stop(childProc, label) {
  if (!childProc || childProc.exitCode !== null) return
  childProc.kill('SIGTERM')
  for (let i = 0; i < 20; i++) {
    if (childProc.exitCode !== null) return
    await sleep(250)
  }
  console.log(`[e2e] ${label} 未在 5s 内退出，强杀`)
  childProc.kill('SIGKILL')
}

let stopping = false
async function shutdown(code) {
  if (stopping) return
  stopping = true
  console.log('[e2e] 停止后端与模拟器...')
  await stop(child, 'cloud-backend')
  await stop(sim, 'drone-sim')
  process.exit(code)
}

process.on('SIGINT', () => shutdown(130))
process.on('SIGTERM', () => shutdown(143))

if (!(await waitHealthy())) {
  console.error(`[e2e] 后端 150s 内未就绪，日志尾部：\n${tailLog(30)}`)
  await shutdown(1)
}

/** 设备上线：遥测 WS 只在有在线设备时才有帧推送（TelemetryPusher 的既有语义）。 */
async function waitDroneOnline(timeoutMs = 60_000) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    try {
      const res = await fetch(`http://127.0.0.1:${PORT}/api/v1/drones`, { signal: AbortSignal.timeout(3000) })
      if (res.ok) {
        const drones = await res.json()
        const list = Array.isArray(drones) ? drones : (drones?.content ?? [])
        if (list.some((d) => d.sysid === DRONE_SYSID && d.online)) return true
      }
    } catch { /* 继续等 */ }
    await sleep(1000)
  }
  return false
}

if (!(await waitDroneOnline())) {
  console.error(`[e2e] 模拟器 sysid=${DRONE_SYSID} 60s 内未上线，日志尾部：\n${tailLog(20)}`)
  await shutdown(1)
}

console.log(`[e2e] 后端就绪 http://127.0.0.1:${PORT}（sysid=${DRONE_SYSID} 已上线）`)
// 常驻：等调用方（playwright）跑完或本进程被杀掉
child.on('exit', (code) => {
  console.log(`[e2e] 后端退出（code=${code}）`)
  shutdown(code === 0 ? 0 : (code || 1))
})
sim.on('exit', (code) => {
  console.log(`[e2e] 模拟器退出（code=${code}）`)
})