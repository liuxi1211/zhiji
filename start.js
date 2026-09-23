#!/usr/bin/env node
'use strict'

/**
 * 知己 · 本地开发启动脚本（跨平台：Windows / macOS / Linux）
 *
 * 用法：
 *   node start.js            交互菜单（不传参时）
 *   node start.js all        同时启动前端 + 后端
 *   node start.js backend    只启动后端
 *   node start.js frontend   只启动前端
 *   node start.js build      编译后端（mvn clean package -DskipTests）
 *   node start.js help       查看帮助
 *
 * 常用参数：
 *   --java-home=<路径>   指定 JDK（覆盖配置文件）
 *   --backend-port=<端口> / --frontend-port=<端口>
 *   --no-kill            端口被占用时不杀进程，直接报错退出
 *   --install            强制重装前端依赖（npm install）
 *
 * 为什么用 Node.js 写而不是 .sh/.bat：
 *   前后端跑起来本来就都需要 Node，用它写一份脚本就能同时覆盖三平台，
 *   不必维护两套 shell 语法（Windows 的 cmd/PowerShell 与 POSIX 差异很大）。
 *
 * JDK 是怎么定位的（换电脑时最重要的部分）：
 *   按下面的优先级逐个尝试，取第一个可用的：
 *     1. 命令行 --java-home
 *     2. 环境变量 ZHIJI_JAVA_HOME
 *     3. start.config.json 里的 javaHome
 *     4. 环境变量 JAVA_HOME
 *     5. 各平台常见安装目录自动探测（Windows / macOS / Linux 都扫）
 *     6. PATH 上的 java 反查其真实 home
 *   所以：换电脑后，要么把新机器的 JDK 写进 start.config.json，
 *   要么干脆留空让它自动探测。配置里的路径失效时会打警告并继续往下找，
 *   不会直接崩掉。
 */

const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const net = require('node:net')
const readline = require('node:readline')
const { spawn, spawnSync } = require('node:child_process')

// ---------------------------------------------------------------------------
// 常量与基础工具
// ---------------------------------------------------------------------------

const ROOT = __dirname
const FRONTEND_DIR = path.join(ROOT, 'frontend')
const IS_WIN = process.platform === 'win32'
const MIN_JAVA_MAJOR = 21 // 与 pom.xml 的 <java.version>21</java.version> 对齐

const C = {
  reset: '\u001b[0m',
  dim: '\u001b[2m',
  red: '\u001b[31m',
  green: '\u001b[32m',
  yellow: '\u001b[33m',
  blue: '\u001b[34m',
  magenta: '\u001b[35m',
  cyan: '\u001b[36m'
}
const useColor = !process.env.NO_COLOR
function paint(color, text) {
  return useColor ? `${C[color]}${text}${C.reset}` : text
}

function log(msg) {
  console.log(msg)
}
function step(msg) {
  console.log(`${paint('cyan', '▶')} ${msg}`)
}
function ok(msg) {
  console.log(`${paint('green', '✓')} ${msg}`)
}
function warn(msg) {
  console.log(`${paint('yellow', '!')} ${msg}`)
}
function fail(msg) {
  console.error(`${paint('red', '✗')} ${msg}`)
}
function hint(msg) {
  console.log(`${paint('dim', `  ${msg}`)}`)
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

/**
 * 同步执行命令并取回输出；失败返回 null 而不是抛异常。
 *
 * 用 spawnSync 而非 execFileSync：后者在成功时只返回 stdout，
 * 会丢掉 stderr（java -version 等命令的结果就在 stderr 里）。
 */
function tryExec(cmd, args, options = {}) {
  const r = spawnSync(cmd, args, {
    encoding: 'utf8',
    windowsHide: true,
    ...options
  })
  if (r.error) return null
  const out = `${r.stdout ?? ''}${r.stderr ?? ''}`
  return out.length > 0 ? out : null
}

/**
 * 同时收集 stdout+stderr。
 *
 * 为什么不能用 execFileSync：它在成功时只返回 stdout，会把 stderr 丢掉。
 * 而 java -version / java -XshowSettings / mvn -v 这些命令恰恰把结果写到 stderr，
 * 于是拿到空串、版本号解析成 null（这个坑实测踩过，会导致 Java 8 被误判为可用）。
 * spawnSync 能同时拿到两个流，这里显式拼起来。
 */
function tryExecBoth(cmd, args, options = {}) {
  const r = spawnSync(cmd, args, {
    encoding: 'utf8',
    windowsHide: true,
    ...options
  })
  if (r.error) return null
  const out = `${r.stdout ?? ''}${r.stderr ?? ''}`
  return out.length > 0 ? out : null
}

// ---------------------------------------------------------------------------
// 端口检测与清理
// ---------------------------------------------------------------------------

/**
 * 能否连上该端口。
 *
 * 必须同时探 IPv4(127.0.0.1) 与 IPv6(::1)：实测 Vite 只监听 [::1]，
 * 若只探 127.0.0.1 会把"已被占用"误判为空闲，于是新进程悄悄换到别的端口
 * （这个坑实测踩过：前端跑到了 5174，而脚本以为它还在 5173）。
 */
function isPortOpenOn(port, host) {
  return new Promise((resolve) => {
    const socket = net.createConnection({ host, port })
    const done = (result) => {
      socket.destroy()
      resolve(result)
    }
    socket.setTimeout(1200)
    socket.once('connect', () => done(true))
    socket.once('timeout', () => done(false))
    socket.once('error', () => done(false))
  })
}

async function isPortOpen(port) {
  const [v4, v6] = await Promise.all([
    isPortOpenOn(port, '127.0.0.1'),
    isPortOpenOn(port, '::1')
  ])
  if (v4 || v6) return true
  // 兜底：端口可能绑定在非 loopback 地址（如 0.0.0.0），
  // 此时两种 loopback 都连不上，但确实被占用，用系统监听列表确认。
  return findListenerPids(port).length > 0
}

/** 找出监听指定端口的进程 PID 列表（按平台选命令）。 */
function findListenerPids(port) {
  const pids = new Set()

  if (IS_WIN) {
    // 注意不能用 `-p TCP`：实测该参数只输出 IPv4，
    // 会把 IPv6 的监听行（如 [::1]:5173）整个漏掉，导致找不到 PID。
    // 因此取全部连接，再靠下面的 LISTENING 正则筛出监听项。
    const out = tryExec('netstat', ['-ano'])
    if (!out) return []
    for (const line of out.split(/\r?\n/)) {
      // netstat 的本地地址列 IPv4 与 IPv6 写法不同，要都能匹配：
      //   TCP    0.0.0.0:8080      0.0.0.0:0       LISTENING    16908
      //   TCP    [::1]:5173        [::]:0          LISTENING    32584
      const m = line.match(
        /^\s*TCP\s+(?:\[[^\]]*\]:|\S+:)(\d+)\s+\S+\s+LISTENING\s+(\d+)\s*$/i
      )
      if (m && Number(m[1]) === port) pids.add(Number(m[2]))
    }
    return [...pids]
  }

  // macOS / Linux：优先 lsof，退化到 ss
  const lsof = tryExec('lsof', ['-nP', `-iTCP:${port}`, '-sTCP:LISTEN', '-t'])
  if (lsof !== null) {
    lsof.split(/\s+/).filter(Boolean).forEach((p) => pids.add(Number(p)))
    return [...pids]
  }
  const ss = tryExec('ss', ['-lntp', `sport = :${port}`])
  if (ss) {
    for (const m of ss.matchAll(/pid=(\d+)/g)) pids.add(Number(m[1]))
  }
  return [...pids]
}

/** 取进程名，仅用于日志可读性。 */
function processName(pid) {
  if (IS_WIN) {
    const out = tryExec('tasklist', ['/FI', `PID eq ${pid}`, '/FO', 'CSV', '/NH'])
    const m = out && out.match(/^"([^"]+)"/)
    return m ? m[1] : 'unknown'
  }
  const out = tryExec('ps', ['-p', String(pid), '-o', 'comm='])
  return out ? out.trim() : 'unknown'
}

function killProcessTree(pid) {
  if (IS_WIN) {
    // /T 连子进程一起杀：mvn / npm 都会派生子进程，只杀父进程会留残留
    return tryExec('taskkill', ['/PID', String(pid), '/F', '/T']) !== null
  }
  try {
    process.kill(pid, 'SIGKILL')
    return true
  } catch {
    return false
  }
}

/**
 * 端口被占用则清理。返回 true 表示端口最终可用。
 * autoKill=false 时不杀，直接返回 false 让调用方报错。
 */
async function ensurePortFree(port, label, autoKill = true) {
  if (!(await isPortOpen(port))) {
    ok(`${label} 端口 ${port} 空闲`)
    return true
  }

  const pids = findListenerPids(port)
  if (pids.length === 0) {
    warn(`端口 ${port} 已被占用，但未能识别出占用进程（可能需要管理员/root 权限）`)
    return false
  }

  if (!autoKill) {
    fail(`端口 ${port} 被占用（PID: ${pids.join(', ')}），已按 --no-kill 要求终止`)
    hint(`可手动结束：${IS_WIN ? `taskkill /PID ${pids[0]} /F /T` : `kill -9 ${pids[0]}`}`)
    return false
  }

  for (const pid of pids) {
    warn(`端口 ${port} 被 ${processName(pid)}（PID ${pid}）占用，正在结束…`)
    killProcessTree(pid)
  }

  // 给系统一点回收时间，最多等 5 秒
  for (let i = 0; i < 10; i++) {
    await sleep(500)
    if (!(await isPortOpen(port))) {
      ok(`端口 ${port} 已释放`)
      return true
    }
  }

  fail(`端口 ${port} 清理失败，仍被占用`)
  return false
}

/** 轮询等待端口就绪（用于"等后端起来再起前端"）。 */
async function waitForPort(port, timeoutMs, intervalMs = 1000) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    if (await isPortOpen(port)) return true
    await sleep(intervalMs)
  }
  return false
}

// ---------------------------------------------------------------------------
// 配置文件
// ---------------------------------------------------------------------------

const CONFIG_PATH = path.join(ROOT, 'start.config.json')
const CONFIG_EXAMPLE_PATH = path.join(ROOT, 'start.config.example.json')

const DEFAULT_CONFIG = {
  // 留空 = 自动探测。换电脑后若探测不到，把该机的 JDK 路径填这里。
  javaHome: '',
  // 留空 = 自动（有 mvnw 用 mvnw，否则用 mvn）
  mavenCommand: '',
  npmCommand: 'npm',
  backendPort: 8080,
  frontendPort: 5173,
  // 端口被占用时是否自动结束占用进程
  autoKillPort: true
}

function loadConfig() {
  if (!fs.existsSync(CONFIG_PATH)) {
    const template = fs.existsSync(CONFIG_EXAMPLE_PATH)
      ? fs.readFileSync(CONFIG_EXAMPLE_PATH, 'utf8')
      : JSON.stringify(DEFAULT_CONFIG, null, 2) + '\n'
    fs.writeFileSync(CONFIG_PATH, template, 'utf8')
    warn('未找到 start.config.json，已按模板自动生成')
    hint(`请按当前电脑情况修改：${CONFIG_PATH}`)
  }

  let userConfig = {}
  try {
    // 去掉可能存在的 UTF-8 BOM：Windows 记事本默认会写 BOM，
    // JSON.parse 遇到 BOM 会直接报 "Unexpected token"，这是换电脑后
    // 手改配置最容易踩的坑，这里主动兜住。
    const raw = fs.readFileSync(CONFIG_PATH, 'utf8').replace(/^\uFEFF/, '')
    userConfig = JSON.parse(raw)
  } catch (e) {
    fail(`start.config.json 解析失败：${e.message}`)
    hint('请检查 JSON 格式（多余逗号、中文引号是常见原因）')
    process.exit(1)
  }

  const config = { ...DEFAULT_CONFIG, ...userConfig }

  // 环境变量可覆盖端口，便于临时改端口而不动文件
  if (process.env.ZHIJI_BACKEND_PORT) config.backendPort = Number(process.env.ZHIJI_BACKEND_PORT)
  if (process.env.ZHIJI_FRONTEND_PORT) config.frontendPort = Number(process.env.ZHIJI_FRONTEND_PORT)

  return config
}

// ---------------------------------------------------------------------------
// JDK 定位
// ---------------------------------------------------------------------------

function javaBin(home, name) {
  return path.join(home, 'bin', IS_WIN ? `${name}.exe` : name)
}

/** 是不是一个可用的 JDK：必须有 bin/java 与 bin/javac（Maven 编译需要 javac）。 */
function isValidJdkHome(home) {
  if (!home) return false
  try {
    if (!fs.statSync(home).isDirectory()) return false
  } catch {
    return false
  }
  return fs.existsSync(javaBin(home, 'java')) && fs.existsSync(javaBin(home, 'javac'))
}

/** 读 JDK 主版本号；读不到返回 null。 */
function readJavaMajor(home) {
  // java -version 把版本写到 stderr，必须两个流都收
  const out = tryExecBoth(javaBin(home, 'java'), ['-version'])
  if (!out) return null
  // openjdk version "21.0.4" / java version "1.8.0_381"
  const m = out.match(/version\s+"([^"]+)"/)
  if (!m) return null
  const parts = m[1].split('.')
  if (parts[0] === '1') return Number(parts[1]) // Java 8 及更早
  return Number(parts[0])
}

/**
 * 用 java -XshowSettings:properties 反查某个 java 可执行文件真正的 home。
 * 这是最可靠的方式：能穿透 javapath 一类的符号链接/转发器，
 * 拿到 JDK 的真实安装目录（比 realpath 推导稳，Windows 上尤其明显）。
 */
function javaHomeViaShowSettings(javaExe) {
  const out = tryExecBoth(javaExe, ['-XshowSettings:properties', '-version'])
  if (!out) return null
  const m = out.match(/^\s*java\.home\s*=\s*(.+?)\s*$/m)
  return m ? m[1] : null
}

/** 从 mvn -v 的输出里抠出 runtime 路径——Maven 自己知道它在用哪个 JDK。 */
function javaHomeFromMaven(mavenCmd) {
  const out = tryExecBoth(mavenCmd, ['-v'])
  if (!out) return null
  const m = out.match(/runtime:\s*(.+?)\s*$/m)
  return m ? m[1] : null
}

/** 扫描各平台常见 JDK 安装目录，按版本从高到低返回。 */
function autoDetectJavaHomes() {
  const roots = []
  if (IS_WIN) {
    roots.push(
      'C:\\Program Files\\Java',
      'C:\\Program Files\\Eclipse Adoptium',
      'C:\\Program Files\\Amazon Corretto',
      'C:\\Program Files\\Microsoft',
      'C:\\Program Files\\Zulu',
      'C:\\Program Files\\BellSoft',
      'C:\\Program Files\\RedHat',
      path.join(os.homedir(), '.jdks')
    )
  } else if (process.platform === 'darwin') {
    roots.push(
      '/Library/Java/JavaVirtualMachines',
      path.join(os.homedir(), 'Library/Java/JavaVirtualMachines'),
      '/opt/homebrew/opt',
      '/usr/local/opt'
    )
  } else {
    roots.push('/usr/lib/jvm', '/usr/java', '/opt/java')
  }

  const found = []
  for (const root of roots) {
    let entries
    try {
      entries = fs.readdirSync(root, { withFileTypes: true })
    } catch {
      continue
    }
    for (const entry of entries) {
      if (!entry.isDirectory() && !entry.isSymbolicLink()) continue
      const base = path.join(root, entry.name)
      // macOS 的 JDK home 藏在 Contents/Home 下，两处都试
      for (const candidate of [base, path.join(base, 'Contents', 'Home')]) {
        if (isValidJdkHome(candidate)) found.push(candidate)
      }
    }
  }

  // 去重后按版本降序，保证优先挑到高版本
  const unique = [...new Set(found)]
  return unique
    .map((home) => ({ home, major: readJavaMajor(home) ?? 0 }))
    .sort((a, b) => b.major - a.major)
    .map((x) => x.home)
}

/** 反查 PATH 上 java 的真实 home（能兜住非标准安装路径）。 */
function javaHomeFromPath() {
  const probe = IS_WIN ? 'where' : 'which'
  const out = tryExec(probe, ['java'])
  if (!out) return null
  const first = out
    .split(/\r?\n/)
    .map((s) => s.trim())
    .filter(Boolean)[0]
  if (!first) return null

  // 先用 -XshowSettings 问它自己的 home（最准），失败再退回 realpath 推导
  const viaShow = javaHomeViaShowSettings(first)
  if (viaShow && isValidJdkHome(viaShow)) return viaShow

  try {
    const real = fs.realpathSync(first)
    const home = path.dirname(path.dirname(real)) // bin/java -> home
    return isValidJdkHome(home) ? home : null
  } catch {
    return null
  }
}

/**
 * 按优先级解析 JAVA_HOME。
 * 关键点：配置里写错的路径只会告警并跳过，继续往下找，不直接退出。
 */
function resolveJavaHome(config, cliJavaHome) {
  const attempts = []

  if (cliJavaHome) attempts.push({ src: '命令行 --java-home', home: cliJavaHome, strict: true })
  if (getEnv('ZHIJI_JAVA_HOME'))
    attempts.push({ src: '环境变量 ZHIJI_JAVA_HOME', home: getEnv('ZHIJI_JAVA_HOME'), strict: true })
  if (config.javaHome)
    attempts.push({ src: 'start.config.json', home: config.javaHome, strict: false })
  if (getEnv('JAVA_HOME'))
    attempts.push({ src: '环境变量 JAVA_HOME', home: getEnv('JAVA_HOME'), strict: false })

  for (const home of autoDetectJavaHomes())
    attempts.push({ src: '自动探测', home, strict: false })

  // Maven 自己知道它在用哪个 JDK（mvn -v 的 runtime 行），
  // 这对"D:\javaApp\jdk"这类非标准路径特别有用——实测本机就是靠这条兜住的。
  const fromMaven = javaHomeFromMaven(config.mavenCommand || 'mvn')
  if (fromMaven) attempts.push({ src: 'Maven runtime', home: fromMaven, strict: false })

  attempts.push({ src: 'PATH 上的 java', home: javaHomeFromPath(), strict: false })

  for (const attempt of attempts) {
    if (!attempt.home) continue
    const home = path.resolve(attempt.home)

    if (!isValidJdkHome(home)) {
      if (attempt.strict) {
        fail(`${attempt.src} 指定的 JDK 不可用：${home}`)
        hint('需要是一个包含 bin/java 与 bin/javac 的 JDK 目录（不是 JRE）')
        process.exit(1)
      }
      warn(`${attempt.src} 的 JDK 路径无效，已跳过：${home}`)
      continue
    }

    const major = readJavaMajor(home)

    // 版本读不出来时：显式指定的放行（用户自己负责），自动探测的跳过。
    // 不能"读不到就当作合格"——实测本机就因为这一点让 Java 8 混了进来。
    if (major === null) {
      if (attempt.strict) {
        warn(`${attempt.src} 的 JDK 版本无法识别，按你显式指定继续：${home}`)
        return { home, major: '未知', src: attempt.src }
      }
      warn(`${attempt.src} 的 JDK 版本无法识别，已跳过：${home}`)
      continue
    }

    if (major < MIN_JAVA_MAJOR) {
      if (attempt.strict) {
        fail(`${attempt.src} 的 JDK 版本过低（Java ${major}），本项目需要 Java ${MIN_JAVA_MAJOR}+`)
        process.exit(1)
      }
      warn(`${attempt.src} 的 JDK 版本偏低（Java ${major}），已跳过：${home}`)
      continue
    }

    return { home, major, src: attempt.src }
  }

  return null
}

// ---------------------------------------------------------------------------
// Maven / npm 定位
// ---------------------------------------------------------------------------

/** 有 mvnw 优先用 mvnw（版本可控，换电脑不用装 Maven）。 */
function resolveMavenCommand(config) {
  if (config.mavenCommand) {
    return { cmd: config.mavenCommand, src: 'start.config.json' }
  }
  const wrapper = IS_WIN ? 'mvnw.cmd' : 'mvnw'
  if (fs.existsSync(path.join(ROOT, wrapper))) {
    return { cmd: path.join(ROOT, wrapper), src: '项目内 mvnw' }
  }
  return { cmd: 'mvn', src: 'PATH' }
}

function assertCommandAvailable(cmd, label) {
  const probe = IS_WIN ? 'where' : 'which'
  const out = tryExec(probe, [cmd])
  if (!out) {
    fail(`找不到 ${label}（${cmd}）`)
    hint(IS_WIN ? '请确认已安装并加入 PATH' : '请确认已安装并在 PATH 中，例如 brew install maven')
    process.exit(1)
  }
}

// ---------------------------------------------------------------------------
// 子进程管理
// ---------------------------------------------------------------------------

/** 正在运行的子进程，用于退出时统一清理。 */
const children = []
let shuttingDown = false

/** 取环境变量，忽略大小写（Windows 上常见 Path/PATH、Java_Home 等写法）。 */
function getEnv(name) {
  if (process.env[name] !== undefined) return process.env[name]
  const lower = name.toLowerCase()
  for (const key of Object.keys(process.env)) {
    if (key.toLowerCase() === lower) return process.env[key]
  }
  return undefined
}

/**
 * 往 PATH 前面插入一个目录。
 *
 * 必须走 getEnv 定位真实键名：Windows 的 PATH 键名通常是 "Path"，
 * 直接写 env.PATH 会新建一个全新的 PATH 变量（值为 undefined+目录），
 * 把系统原有的 PATH 整个覆盖掉，导致 mvn/java 全部找不到（这个坑实测踩过）。
 */
function prependToPath(env, dir) {
  const key = Object.keys(env).find((k) => k.toLowerCase() === 'path') ?? 'PATH'
  const current = env[key] ?? ''
  const parts = current.split(path.delimiter).filter(Boolean)
  if (parts.some((p) => path.resolve(p) === path.resolve(dir))) return env
  env[key] = `${dir}${path.delimiter}${current}`
  return env
}

/** 后端启动要带上解析出的 JDK；Maven 靠 JAVA_HOME 选编译器。 */
function backendEnv(javaHome) {
  const env = { ...process.env }
  if (javaHome) {
    env.JAVA_HOME = javaHome
    prependToPath(env, path.join(javaHome, 'bin'))
  }
  return env
}

/** 按行加前缀输出，避免前后端日志混在一起分不清来源。 */
function pipeWithPrefix(stream, prefix, color) {
  let buffer = ''
  stream.on('data', (chunk) => {
    buffer += chunk.toString()
    const lines = buffer.split(/\r?\n/)
    buffer = lines.pop() ?? ''
    for (const line of lines) {
      if (line.trim() === '') continue
      console.log(`${paint(color, prefix)} ${line}`)
    }
  })
}

/**
 * 启动一个长驻子进程。
 * Windows 用 shell 解析 .cmd；POSIX 用 detached 建进程组，便于整组结束。
 */
function launch({ name, cmd, args, cwd, env, color }) {
  const child = spawn(cmd, args, {
    cwd,
    env,
    shell: IS_WIN,
    detached: !IS_WIN,
    stdio: ['ignore', 'pipe', 'pipe'],
    windowsHide: true
  })

  children.push({ name, child })

  pipeWithPrefix(child.stdout, `[${name}]`, color)
  pipeWithPrefix(child.stderr, `[${name}]`, color)

  child.on('error', (err) => {
    fail(`${name} 启动失败：${err.message}`)
  })
  child.on('exit', (code, signal) => {
    if (shuttingDown) return
    const reason = signal ? `信号 ${signal}` : `退出码 ${code}`
    warn(`${name} 已退出（${reason}）`)
    if (code !== 0 && code !== null) {
      // 一个挂了就把另一个也收拾掉，避免留下半截服务
      shutdown(1)
    }
  })

  return child
}

function shutdown(exitCode = 0) {
  if (shuttingDown) return
  shuttingDown = true
  log('')
  step('正在停止所有服务…')

  for (const { name, child } of children) {
    if (child.exitCode !== null || child.signalCode !== null) continue
    if (IS_WIN) {
      tryExec('taskkill', ['/PID', String(child.pid), '/F', '/T'])
    } else {
      try {
        process.kill(-child.pid, 'SIGTERM') // 负 PID = 整个进程组
      } catch {
        try {
          child.kill('SIGTERM')
        } catch {
          /* 已退出 */
        }
      }
    }
    log(`  已停止 ${name}`)
  }

  setTimeout(() => process.exit(exitCode), 300)
}

// ---------------------------------------------------------------------------
// 各命令实现
// ---------------------------------------------------------------------------

function printEnvironment(config, java, maven, needsBackend) {
  log('')
  log(paint('magenta', '  知己 · 本地开发'))
  log(`  JDK    ${java.home}  ${paint('dim', `(Java ${java.major}, 来源: ${java.src})`)}`)
  log(`  Maven  ${maven.cmd}  ${paint('dim', `(来源: ${maven.src})`)}`)
  log(`  端口   后端 ${config.backendPort} / 前端 ${config.frontendPort}`)
  if (needsBackend) {
    log(`  密钥   ${localConfigStatus()}`)
  }
  log('')
}

/**
 * 涉密配置只放本地（config/application-local.properties，不入库也不进 jar）：
 * 起后端前先告诉开发者它在不在，避免"密钥没配"被误当成"代码坏了"。
 */
function localConfigStatus() {
  const local = path.join(ROOT, 'config', 'application-local.properties')
  if (fs.existsSync(local)) {
    return `${paint('green', 'config/application-local.properties 已就绪')}`
  }
  return (
    `${paint('yellow', 'config/application-local.properties 缺失')}` +
    `  ${paint('dim', '（复制 config/application-local.properties.example 并填入密钥）')}`
  )
}

async function cmdBuild(config, java, maven) {
  step('编译后端…')
  hint('mvn clean package -DskipTests')

  const child = spawn(maven.cmd, ['clean', 'package', '-DskipTests'], {
    cwd: ROOT,
    env: backendEnv(java.home),
    shell: IS_WIN,
    stdio: 'inherit',
    windowsHide: true
  })

  await new Promise((resolve) => {
    child.on('exit', (code) => resolve(code ?? 1))
    child.on('error', () => resolve(1))
  }).then((code) => {
    if (code === 0) {
      ok('编译完成')
      hint(`产物：${path.join(ROOT, 'target')}`)
      process.exit(0)
    } else {
      fail(`编译失败（退出码 ${code}）`)
      process.exit(code || 1)
    }
  })
}

async function startBackend(config, java, maven, { kill = true } = {}) {
  if (!(await ensurePortFree(config.backendPort, '后端', kill))) {
    process.exit(1)
  }
  step(`启动后端…  ${paint('dim', `(端口 ${config.backendPort})`)}`)
  launch({
    name: '后端',
    cmd: maven.cmd,
    args: ['spring-boot:run'],
    cwd: ROOT,
    env: backendEnv(java.home),
    color: 'blue'
  })
}

async function startFrontend(config, { kill = true, install = false } = {}) {
  if (!fs.existsSync(path.join(FRONTEND_DIR, 'package.json'))) {
    fail('找不到 frontend/package.json')
    process.exit(1)
  }

  const needInstall = install || !fs.existsSync(path.join(FRONTEND_DIR, 'node_modules'))
  if (needInstall) {
    step('安装前端依赖…')
    hint('npm install')
    const code = await runToCompletion(config.npmCommand, ['install'], FRONTEND_DIR)
    if (code !== 0) {
      fail(`前端依赖安装失败（退出码 ${code}）`)
      process.exit(code || 1)
    }
    ok('前端依赖就绪')
  }

  if (!(await ensurePortFree(config.frontendPort, '前端', kill))) {
    process.exit(1)
  }

  step(`启动前端…  ${paint('dim', `(端口 ${config.frontendPort})`)}`)
  launch({
    name: '前端',
    cmd: config.npmCommand,
    args: ['run', 'dev', '--', '--port', String(config.frontendPort)],
    cwd: FRONTEND_DIR,
    env: process.env,
    color: 'green'
  })
}

/** 跑一个会结束的命令并等它退出，返回退出码。 */
function runToCompletion(cmd, args, cwd) {
  return new Promise((resolve) => {
    const child = spawn(cmd, args, {
      cwd,
      shell: IS_WIN,
      stdio: 'inherit',
      windowsHide: true
    })
    child.on('error', () => resolve(1))
    child.on('exit', (code) => resolve(code ?? 1))
  })
}

async function cmdAll(config, java, maven, opts) {
  // 先把两个端口都清干净，避免起一个失败一个
  await startBackend(config, java, maven, opts)

  step('等待后端就绪…')
  const ready = await waitForPort(config.backendPort, 120000)
  if (ready) {
    ok(`后端已就绪：http://localhost:${config.backendPort}`)
  } else {
    warn('等待后端就绪超时，仍继续启动前端（请查看上方后端日志）')
  }

  await startFrontend(config, opts)

  log('')
  ok('服务已启动')
  log(`  前端  ${paint('cyan', `http://localhost:${config.frontendPort}`)}`)
  log(`  后端  ${paint('cyan', `http://localhost:${config.backendPort}`)}`)
  log(paint('dim', '  按 Ctrl+C 停止全部服务'))
  log('')
}

// ---------------------------------------------------------------------------
// 交互菜单
// ---------------------------------------------------------------------------

async function askChoice() {
  const rl = readline.createInterface({ input: process.stdin, output: process.stdout })
  const options = [
    '同时启动前端和后端',
    '只启动后端',
    '只启动前端',
    '编译后端（mvn clean package -DskipTests）',
    '退出'
  ]
  log('')
  log(paint('magenta', '  知己 · 本地开发'))
  options.forEach((o, i) => log(`  ${i + 1}. ${o}`))
  log('')

  const answer = await new Promise((resolve) => rl.question('请选择 [1-5]：', resolve))
  rl.close()
  return Number(answer.trim())
}

// ---------------------------------------------------------------------------
// 入口
// ---------------------------------------------------------------------------

function parseArgs(argv) {
  const opts = {
    command: null,
    javaHome: null,
    backendPort: null,
    frontendPort: null,
    kill: true,
    install: false
  }
  const alias = { fe: 'frontend', be: 'backend', both: 'all', b: 'build' }

  for (const arg of argv) {
    if (arg.startsWith('--java-home=')) {
      opts.javaHome = arg.slice('--java-home='.length)
      continue
    }
    if (arg.startsWith('--backend-port=')) {
      opts.backendPort = Number(arg.slice('--backend-port='.length))
      continue
    }
    if (arg.startsWith('--frontend-port=')) {
      opts.frontendPort = Number(arg.slice('--frontend-port='.length))
      continue
    }
    if (arg === '--no-kill') {
      opts.kill = false
      continue
    }
    if (arg === '--install') {
      opts.install = true
      continue
    }
    if (arg.startsWith('-')) continue

    if (!opts.command) opts.command = alias[arg] || arg
  }
  return opts
}

function printHelp() {
  log(`
${paint('magenta', '知己 · 本地开发启动脚本')}

${paint('cyan', '用法')}
  node start.js [命令] [参数]

${paint('cyan', '命令')}
  all | both            同时启动前端和后端（默认等后端就绪再起前端）
  backend | be          只启动后端
  frontend | fe         只启动前端
  build | b             编译后端（mvn clean package -DskipTests）
  help                  显示本帮助
  （不传命令则进入交互菜单）

${paint('cyan', '参数')}
  --java-home=<路径>      指定 JDK，优先级最高
  --backend-port=<端口>   覆盖后端端口
  --frontend-port=<端口>  覆盖前端端口
  --no-kill              端口被占用时不自动结束占用进程
  --install              强制重装前端依赖

${paint('cyan', '配置')}
  首次运行会由 start.config.example.json 生成 start.config.json。
  换电脑时改这个文件即可（该文件不入库，各机器各留一份）：
    javaHome      留空 = 自动探测；探测不到就填本机 JDK 路径
    mavenCommand  留空 = 有 mvnw 用 mvnw，否则用 PATH 上的 mvn
    backendPort / frontendPort   端口
    autoKillPort  端口被占用是否自动结束占用进程

  涉密配置（LLM / 会话密钥）只放本地，不入库也不进 jar：
    cp config/application-local.properties.example config/application-local.properties
    本地开发自动加载，无需额外参数（细节见模板头部说明）

${paint('cyan', '示例')}
  node start.js all
  node start.js build
  node start.js backend --java-home=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
`)
}

async function main() {
  const opts = parseArgs(process.argv.slice(2))

  if (opts.command === 'help' || opts.command === '-h') {
    printHelp()
    return
  }

  if (opts.command && !['all', 'backend', 'frontend', 'build'].includes(opts.command)) {
    fail(`未知命令：${opts.command}`)
    printHelp()
    process.exit(1)
  }

  const config = loadConfig()
  if (opts.backendPort) config.backendPort = opts.backendPort
  if (opts.frontendPort) config.frontendPort = opts.frontendPort

  // 编译/启动后端才需要 JDK 与 Maven；只起前端时不做这些检查
  const needsBackend = !opts.command || opts.command === 'all' || opts.command === 'backend' || opts.command === 'build'

  let java = null
  let maven = null
  if (needsBackend) {
    java = resolveJavaHome(config, opts.javaHome)
    if (!java) {
      fail('未能定位到可用的 JDK')
      hint(`需要 Java ${MIN_JAVA_MAJOR}+（含 javac 的 JDK，不是 JRE）`)
      hint('解决办法二选一：')
      hint('  1. 设置环境变量 JAVA_HOME 指向 JDK 目录')
      hint(`  2. 在 ${CONFIG_PATH} 里填 javaHome`)
      process.exit(1)
    }
    maven = resolveMavenCommand(config)
    assertCommandAvailable(maven.cmd, 'Maven')
  }

  if (!opts.command) {
    const choice = await askChoice()
    if (choice === 1) opts.command = 'all'
    else if (choice === 2) opts.command = 'backend'
    else if (choice === 3) opts.command = 'frontend'
    else if (choice === 4) opts.command = 'build'
    else process.exit(0)
  }

  // 只起前端时不需要 JDK 信息，但环境回显仍要能打印，这里兜一个占位
  printEnvironment(config, java ?? { home: '-', major: '-', src: '不需要' }, maven ?? { cmd: '-', src: '不需要' }, needsBackend)

  const needFrontendOnly = opts.command === 'frontend'
  if (needFrontendOnly) {
    assertCommandAvailable(config.npmCommand, 'npm')
  } else if (opts.command === 'all') {
    assertCommandAvailable(config.npmCommand, 'npm')
  }

  process.on('SIGINT', () => shutdown(0))
  process.on('SIGTERM', () => shutdown(0))

  if (opts.command === 'build') {
    await cmdBuild(config, java, maven)
  } else if (opts.command === 'backend') {
    await startBackend(config, java, maven, { kill: config.autoKillPort && opts.kill })
    keepAlive()
  } else if (opts.command === 'frontend') {
    await startFrontend(config, { kill: config.autoKillPort && opts.kill, install: opts.install })
    keepAlive()
  } else if (opts.command === 'all') {
    await cmdAll(config, java, maven, { kill: config.autoKillPort && opts.kill, install: opts.install })
    keepAlive()
  }
}

/** 长驻命令不主动退出，靠 Ctrl+C / 子进程退出触发清理。 */
function keepAlive() {
  setInterval(() => {}, 1 << 30)
}

main().catch((err) => {
  fail(`脚本异常：${err && err.stack ? err.stack : err}`)
  shutdown(1)
})
