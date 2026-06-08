# 糯米播放器 · 架构与流程

本文档描述糯米播放器的运行时架构、Android Auto 接管流程、关键时序与可调参数。

## 1. 组件分层

```
┌───────────────────────────────────────────────────────────────────┐
│                       Android Auto (车机)                          │
└───────────────────────────────────────────────────────────────────┘
                              │
                              │  bind MediaBrowserService
                              ▼
┌───────────────────────────────────────────────────────────────────┐
│              MyMusicService (shared 模块)                          │
│                                                                    │
│  • MediaBrowserServiceCompat：被 AA 探测/绑定                       │
│  • 持有 MirrorSession (MediaSessionCompat)                         │
│  • 持有 remoteCtrl (MediaControllerCompat) 指向目标 App            │
│  • 把 remoteCtrl 的 metadata/state 同步进 MirrorSession            │
│  • 透传 transport 控制（play/pause/seek/skip）到目标 App           │
└───────────────────────────────────────────────────────────────────┘
                              ▲                          │
                ACTION_CONTROLLER (token)                │ transport
                              │                          ▼
┌───────────────────────────────────────────────────────────────────┐
│            MusicSessionSniffer (mobile 模块, NLS)                  │
│                                                                    │
│  • 通过 MediaSessionManager.getActiveSessions 列举活跃 session      │
│  • 命中 last_pkg 的 session 后，通过 LocalBroadcast 发送 token      │
│  • 通过 OnActiveSessionsChangedListener 感知 session 变化            │
└───────────────────────────────────────────────────────────────────┘
                              │
                              │  observe
                              ▼
┌───────────────────────────────────────────────────────────────────┐
│         目标音乐 App（如 QQ 音乐、网易云）                          │
│         自身持有 MediaSession，本应用通过 NLS 权限读取其 token       │
└───────────────────────────────────────────────────────────────────┘
```

辅助组件：

| 组件 | 模块 | 职责 |
|------|-----|------|
| `MainActivity` | mobile | 设置入口、歌词开关、当前歌曲展示；前台时主动唤起发现链路 |
| `SessionPickerSheet` | mobile | 让用户选要镜像的 App，写入 `session_pref.last_pkg` |
| `SessionSnifferService` | mobile | 第二个 NLS，仅供 picker UI 列举活跃 session |
| `BootReceiver` | mobile | 开机 / 应用更新后请求重绑 Sniffer，前移 NLS 绑定时机 |
| `PlaybackControlsFragment` / `AlbumCoverFragment` | mobile | 手机端 UI 渲染 |

## 2. 进程内信道

| Action | 方向 | 说明 |
|--------|------|------|
| `com.nuomi.ACTION_CONTROLLER` | Sniffer → MyMusicService / MainActivity | 携带 (pkg, MediaSession token) |
| `com.nuomi.REQUEST_TOKEN` | MyMusicService / MainActivity → Sniffer | 请求立即重发 token |
| `com.nuomi.ACTION_TOGGLE_LYRICS_MODE` | UI → MyMusicService | 切换 QQ 歌词覆盖模式 |
| `com.nuomi.ACTION_SELECTION_CHANGED` | Picker → MainActivity | 选中的 App 已变更 |

所有上述 action 均通过 `LocalBroadcastManager`，仅进程内有效。

## 3. 关键流程：AA 接管

### 3.1 总体时序

```
T-∞  开机 / 应用更新 → BootReceiver.onReceive
        └─ requestSnifferRebind()              ← 让 NLS 在插车前就连上

T0   AA 探测 → bindService(MyMusicService)
        ├─ MyMusicService.onCreate()
        │     ├─ 注册 tokenRx
        │     ├─ seedInitialSessionContent  (占位 metadata + PAUSED)
        │     ├─ setSessionToken / setActive(true)
        │     └─ requestSnifferRebind()
        └─ MyMusicService.onBind()  ← boundClientCount++
              └─ enterForegroundIfNeeded()  ← MIN 优先级通知 + MediaStyle

T1   AA 调用 onGetRoot()
        ├─ startDiscovery()  ← 启动 60s 发现循环
        └─ if remoteCtrl==null: autoStartLastApp()
              ├─ bind 目标 App 的 MediaBrowserService
              ├─ onConnected: play()
              └─ startPathBObservation()  ← PoC，观察 token 是否带 metadata/state

T2   discovery tick (0.5s, 1, 2, 4, 8, 8…)
        ├─ broadcast REQUEST_TOKEN
        └─ requestSnifferRebind() （仅前 3 次 tick）

T3   Sniffer onListenerConnected()  ← 何时触发取决于系统
        ├─ addOnActiveSessionsChangedListener
        ├─ refreshSelectedController + sendTokenIfAny
        └─ scheduleDelayedRefresh (1.5s/4s/9s 兜底)

T4   Sniffer 找到 last_pkg 对应的 active session
        └─ 广播 ACTION_CONTROLLER (pkg, token)

T5   MyMusicService.tokenRx 收到
        ├─ switchModeForSource(pkg) → 切换 QQ / NCM 分支
        ├─ 构造 remoteCtrl，注册 RemoteCallback
        ├─ stopDiscovery("got-token")  ← 停掉所有 pending 工作
        ├─ disconnectAutoStartBrowser() （连带停掉 PathB 观察）
        └─ mirror(meta, state) → AA 屏幕显示真实歌曲

T∞   AA 断开 → MyMusicService.onUnbind()  ← boundClientCount==0
        └─ exitForeground()  ← 通知移除，进程进入普通后台态
              (前台 Service 期间进程未被回收，下次 AA 重连零冷启动)
```

### 3.2 发现循环（onGetRoot 之后）

```
discoveryTick:
    if remoteCtrl != null      → stopDiscovery("got-token")
    if elapsed > 60s           → stopDiscovery("timeout")
    tickCount++
    broadcast REQUEST_TOKEN
    if tickCount <= 3          → requestSnifferRebind()
    nextDelay = min(8s, max(0.5s, nextDelay * 2))
    postDelayed(self, nextDelay)
```

**关键设计**
- 退避序列：0.5 → 1 → 2 → 4 → 8 → 8s
- 60s 时间窗：覆盖目标 App 冷启动与 NLS 绑定延迟之和的常见情形
- `requestRebind` 只在前 3 次 tick 调用：rebind 对"已连接"无效，重复调用是浪费
- `onGetRoot` 与 `onLoadChildren` 都会**刷新窗口起点**（不重置退避），AA 频繁查询不会退化为高频轮询

### 3.3 autoStart 兜底

当 `onGetRoot` 时 `remoteCtrl == null`（还没拿到 token），会尝试主动 bind 目标 App 的 MediaBrowserService 并 `play()`：

```
autoStartLastApp:
    pkg = last_pkg
    cn = queryIntentServices(MediaBrowserService, pkg).first
    autoStartBrowser = new MediaBrowserCompat(this, cn, cb)
    autoStartBrowser.connect()

    onConnected:    play() → 3s 后 disconnect()
    onFailed:       scheduleAutoStartRetry()  (最多 3 次, 2s 间隔)
    onSuspended:    scheduleAutoStartRetry()
```

仅在目标 App 暴露 MediaBrowserService 且允许第三方连接时有效。失败不阻塞主流程。

### 3.4 Sniffer 唤醒策略

NLS 绑定状态由系统决定，存在"长期未绑定"的可能性。本应用通过四处主动唤醒：

| 触发点 | 调用方 | 时机 |
|--------|--------|------|
| BootReceiver | 开机 / 应用更新 | 把 NLS 绑定时机前移到 boot 时段 |
| MyMusicService.onCreate | MediaBrowserService 启动后 | AA bind 时 |
| discovery tick (前 3 次) | MyMusicService.discoveryTick | AA 等 token 期间 |
| MainActivity.onResume | Activity 切入前台 | 用户打开 UI |

任一通道生效，Sniffer 即可绑定并发送 token。

### 3.5 前台 Service 生命周期

为避免 AA 断开后进程被回收、下次重连重走整个冷启动链路，MyMusicService 在被 bind 期间提升为前台 Service：

```
onBind:    boundClientCount++  → enterForegroundIfNeeded()
onUnbind:  boundClientCount--  → if 0: exitForeground()
```

- 通知通道 `nuomi.aa.fg`：IMPORTANCE_LOW，无声、无角标
- 通知样式：`MediaStyle.setMediaSession(token)`，系统会从 session 自动拉歌曲信息
- 优先级 MIN + VISIBILITY_PUBLIC：尽量不打扰，但锁屏可见用于控件交互
- API 29+ 使用 3-arg `startForeground(id, n, FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)` 满足 Android 14+ 要求
- 失败回退：startForeground 抛异常不会让 service 崩溃，退化为普通 bound service

### 3.6 路径 B 观察（PoC，log-only）

主路径走 NotificationListenerService 拿 token。理论上还有路径 B：直接 `bind` 目标 App 的 `MediaBrowserService`，`getSessionToken` 得到 token 后构造 `MediaControllerCompat` 观察。`autoStartLastAppOnce` 已经 bind 了一次，这里仅旁路注册一个临时回调验证：

```
startPathBObservation:
    probeCtrl = new MediaControllerCompat(this, autoStartBrowser.getSessionToken())
    probeCtrl.registerCallback {
        onMetadataChanged → Log.i(PathB, ...)
        onPlaybackStateChanged → Log.i(PathB, ...)
    }
    handler.postDelayed(summarizePathB, 12s)

12s 后或 autoStart disconnect 时：unregisterProbeObserver()
```

观察目标：
- `getSessionToken()` 拿到的 session 是否带 metadata（标题/封面）
- 是否能持续收到 state 变化（说明它是"真正在播的那个" session）
- 不同 App 版本下的兼容性

logcat 过滤 tag `PathB` 即可看到。**主流程行为不受影响。**

## 4. 关键流程：QQ 歌词模式

仅 QQ 音乐支持（其他 App 进入 NCM 分支，自动关闭歌词）。

### 4.1 数据来源

QQ 音乐在其 MediaMetadata 中私有键暴露：
- `ucar.media.metadata.LYRICS_WHOLE`：整曲 LRC 文本
- `ucar.media.metadata.PLAY_MODE`：循环模式（0=shuffle, 1=repeat-one, 2=repeat-all）

### 4.2 时钟同步

歌词覆盖模式下需要按 ms 精度推算当前位置：

```
clockPosition():
    if STATE_PLAYING: basePosMs + (now - baseUpdateElapsed) * baseSpeed
    else:             basePosMs
```

`base*` 字段在以下时刻刷新：
- RemoteCallback.onPlaybackStateChanged
- onSeekTo
- enterLyricsMode

### 4.3 每秒 tick 与去重

`lyricsUpdater` 每 1000ms 触发 `applyLyricsOverlay`：

```
applyLyricsOverlay:
    更新 lastPlayMode、durationMs、parsedLyrics（若 lyricsWhole 变化）
    idx = findLyricsIndex(clockPosition())
    if idx == lastLyricsIdx && !lyricsChanged:
        return                          ← 跳过两次 IPC
    lastLyricsIdx = idx
    setMetadata(...)                    ← TITLE=当前句, ARTIST=下一句
    setPlaybackState(...)
```

歌词行通常 3~5 秒一换，配合去重后 IPC 量为 ~1/4。
`lastLyricsIdx` 在 enterLyricsMode / exitLyricsMode / onSeekTo 时重置为 -1。

### 4.4 mirror 在歌词模式下的分支

```
mirror(meta, st):
    if 歌词模式:
        if st != null: 更新 base 时钟
        applyLyricsOverlay(meta ?: lastRemoteMeta)   ← 一次性 set 二者
        return
    if meta != null: mirrorMetadataStandard(meta)
    if st   != null: mirrorPlaybackStateStandard(meta, st)
```

合并到单一调用避免双写 PlaybackState。

## 5. 状态字段速查

| 字段 | 类型 | 含义 |
|------|------|------|
| `remoteCtrl` | MediaControllerCompat | 当前镜像的目标 App controller；null 表示未绑定 |
| `isNcmMode` | boolean | true=非 QQ 模式（禁用歌词与自定义按钮） |
| `isLyricsMode` | boolean | 仅 QQ 有效，true=歌词覆盖中 |
| `lastRemoteMeta` / `lastRemoteState` | 缓存 | 给歌词模式的 base 时钟计算用 |
| `lastPlayMode` | int | 0=shuffle, 1=repeat-one, 2=repeat-all；默认 2 |
| `lastLyricsIdx` | int | 上次写入的歌词行号；用于跨 tick 去重 |
| `discoveryRunning` / `discoveryTickCount` | 控制 | 发现循环状态 |
| `autoStartAttempts` | int | autoStart 已尝试次数（上限 3） |
| `boundClientCount` / `inForeground` | 控制 | 前台 Service 生命周期 |
| `probeCtrl` / `probeCb` / `probeSawMetadata` / `probeSawState` | 调试 | 路径 B 观察用临时 controller 与命中标记 |

## 6. 可调参数（MyMusicService）

| 常量 | 默认 | 说明 |
|------|------|------|
| `DISCOVERY_WINDOW_MS` | 60000 | 发现循环总窗口 |
| `DISCOVERY_INITIAL_DELAY_MS` | 500 | 首次 tick 间隔 |
| `DISCOVERY_MAX_DELAY_MS` | 8000 | 退避上限 |
| `DISCOVERY_REBIND_TICK_LIMIT` | 3 | 仅前 N 次 tick 触发 requestRebind |
| `AUTO_START_MAX_ATTEMPTS` | 3 | autoStart 重试次数 |
| `AUTO_START_RETRY_DELAY_MS` | 2000 | autoStart 重试间隔 |
| `PATH_B_OBSERVE_DURATION_MS` | 12000 | 路径 B 观察窗口 |
| `FG_CHANNEL_ID` / `FG_NOTIFICATION_ID` | 字符串 / 1042 | 前台通知 ID |

Sniffer 端：

| 常量 | 默认 | 说明 |
|------|------|------|
| `POST_CONNECT_REFRESH_DELAYS_MS` | {1500, 4000, 9000} | onListenerConnected 后的兜底重查时刻 |

## 7. SharedPreferences

| File | Key | 写入方 | 读取方 |
|------|-----|--------|--------|
| `session_pref` | `last_pkg` | SessionPickerSheet | Sniffer / MyMusicService / MainActivity |
| `session_pref` | `last_label` | SessionPickerSheet | MainActivity |
| `last_meta` | `title` / `artist` | MyMusicService.persistLastMeta | MyMusicService.seedInitialSessionContent |
| `settings` | `autoLyrics` | MainActivity | MyMusicService.onCreate |
| `settings` | `guideShown` | MainActivity | MainActivity |

## 8. 已知限制

1. **NLS 绑定不可强制**：`requestRebind` 是请求而非命令，系统可拒绝。本应用在四处主动调用以提高命中率，但不保证。
2. **后台 Activity 启动受限**（Android 12+）：不能从车机端直接把目标 App 的 UI 拉到前台。autoStart 仅依赖目标 App 的 MediaBrowserService。
3. **目标 App 的 MediaBrowserService 不一定对外公开**：若需鉴权，autoStart 会失败，需要用户在手机端手动操作目标 App。
4. **NLS 权限须用户开启**：未开启时 `getActiveSessions` 抛 SecurityException，所有发现逻辑失效；MainActivity 启动时会弹窗引导。
5. **未解锁开机 + 直接插车**：FBE 锁定阶段 NLS 不会绑定，`SharedPreferences` 也读不到。BootReceiver 自身仍能触发（`LOCKED_BOOT_COMPLETED` 已注册），但 NLS 实际生效仍要等首次解锁。

## 9. 性能特征

| 路径 | 估算频率 |
|------|---------|
| 歌词模式 IPC（setMetadata + setPlaybackState） | ~1 次 / 歌词行（~3~5 秒一行）|
| 非歌词模式 IPC | 仅 onMetadataChanged / onPlaybackStateChanged 触发，频率随源 App |
| discovery 期间 IPC | 0.5/1/2/4/8s 退避，60s 上限，约 6~8 次 |
| requestRebind | onCreate + onResume + BootReceiver + 前 3 个 discovery tick |
| AA 重连冷启动 | 前台 Service 期间为 0；进程被回收后才走完整 onCreate 链路 |
| MainActivity 在后台 | 不处理 token 广播（onStop 时注销） |
| onPlay / onPause 响应 | 乐观更新立即 setPlaybackState；实际 IPC 与 transport 命令并发 |

## 10. 本轮已落地的"接近原生"优化

- **前台 Service**（AA bind 生命周期内）：AA 重连零冷启动，进程稳态
- **BootReceiver**：开机 / 应用更新即触发 Sniffer 重绑，前移 NLS 绑定时机
- **乐观 PlaybackState**：onPlay/onPause 立即反映新状态，不等远端 100~300ms 延迟
- **路径 B PoC**：旁路观察直接 bind 目标 App 的 MediaBrowserService 是否能省掉 NLS 主路径（log-only）

仍未做、可作下一步的：

- 路径 B 转主路径（依赖 PoC 验证结果）
- Direct-Boot-aware MyMusicService（把 last_pkg 挪到 device-protected storage）—— 仅边际收益，NLS 仍需解锁
- 速度更激进的 BootReceiver 持续重绑（JobScheduler 周期 ping）
