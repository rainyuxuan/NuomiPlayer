# NuomiPlayer — Android Auto 识别问题分析（v1.4.0 branch）

> 基于 branch `v1.4.0` 的代码阅读，与 `main` 相比结构已大幅重写。

---

## 一、项目结构

```
NuomiPlayer/
├── mobile/          # 手机端 Application（min SDK 33）
│   └── src/main/java/com/nuomi/
│       ├── MainActivity.java           # 主界面，管理 fragment + 歌词开关
│       ├── MusicSessionSniffer.java    # NotificationListenerService，监听选中 app 的 MediaSession
│       ├── SessionSnifferService.java  # 空壳 NLS，仅用于让 SessionRepo 能调 getActiveSessions()
│       ├── SessionPickerSheet.java     # 底部弹窗，让用户选择播放器
│       ├── SessionRepo.java            # 枚举所有活跃 MediaSession
│       ├── SessionInfo.java            # 数据类（包名、标签、图标、正在播放）
│       ├── SessionDump.java            # 调试用：打印 MediaMetadata 所有字段
│       ├── PlaybackControlsFragment.java
│       ├── AlbumCoverFragment.java
│       ├── MusicSlider.kt
│       ├── SquigglyProgress.kt
│       └── NotifAccessHelper.java
│
├── shared/          # Library 模块（min SDK 28）
│   └── src/main/java/com/nuomi/shared/
│       └── MyMusicService.java         # MediaBrowserServiceCompat，AA 连接点
│
└── automotive/      # Automotive 模块（最终产物，无独立 Java 代码）
```

> ⚠️ `shared/src/main/java/com/example/myapplication/shared/MyMusicService.java` 是残留的旧文件（main branch 版本），**manifest 引用的是 `com.nuomi.shared.MyMusicService`，旧文件不参与运行**。同理 `mobile/src/main/java/com/example/myapplication/QqSessionSniffer.java` 也是死代码。

---

## 二、运行时数据流

```
用户打开 SessionPickerSheet → 选中一个播放器（如 QQ 音乐）
  → 写 SharedPreferences: session_pref / last_pkg = "com.tencent.qqmusic"
  → LocalBroadcast: com.nuomi.REQUEST_TOKEN

MusicSessionSniffer (reqTokenRx 接收到 REQUEST_TOKEN)
  → refreshSelectedController()
      读 SP，找 com.tencent.qqmusic 的 MediaController
  → sendTokenIfAny()
      LocalBroadcast: com.nuomi.ACTION_CONTROLLER
        extra "pkg"    = "com.tencent.qqmusic"
        extra "binder" = MediaSessionCompat.Token

MyMusicService (tokenRx 接收到 ACTION_CONTROLLER)
  → 验证 pkg == SP.last_pkg
  → 判断是否 QQ：决定 isNcmMode
  → updateSessionActive("sourceChanged")   ← ⚠️ 关键 bug 在这里（见下文）
  → 创建 remoteCtrl = MediaControllerCompat(token)
  → mirror() 同步当前 meta/state 到 mSession

remoteCtrl.RemoteCallback
  → onMetadataChanged → mirror(meta, null)
  → onPlaybackStateChanged → mirror(null, state)

mirror() → mSession.setMetadata / setPlaybackState
  → Android Auto 订阅 mSession → 更新车机 UI
```

### 额外触发路径

| 触发点 | 行为 |
|---|---|
| `MusicSessionSniffer.onListenerConnected()` | 读 SP 找 controller → `sendTokenIfAny()` |
| `MusicSessionSniffer.onNotificationPosted()` | 只在通知来自选中 pkg 时 → `sendTokenIfAny()` |
| `SessionPickerSheet` 用户选择 | 写 SP → 发 `REQUEST_TOKEN` → Sniffer 重发 token |
| `autoLyricsReceiver`（ACTION_TOGGLE_LYRICS_MODE）| 仅 QQ 模式：开启歌词模式 |

---

## 三、发现的问题

### 🔴 问题 1 — `updateSessionActive()` 条件错误（**核心 bug，直接导致 AA 看不见 app**）

```java
// MyMusicService.java:77-83
private void updateSessionActive(String reason) {
    boolean should = (!isNcmMode && isLyricsMode); // 只有 QQ + 歌词模式 才激活
    if (mSession.isActive() != should) {
        mSession.setActive(should);
    }
}
```

这个条件要求**同时满足**：QQ 模式 + 歌词模式开启，session 才 active。

`updateSessionActive()` 的调用点：

| 调用位置 | `isNcmMode` | `isLyricsMode` | `should` |
|---|---|---|---|
| `onCreate()` | false | false | **false** |
| `tokenRx`（收到 QQ token）| false | false（默认）| **false** |
| `tokenRx`（收到非 QQ token）| true | false | **false** |
| `onCustomAction` 开启歌词 | false | **true** | true ✓ |

**结论：`mSession` 几乎永远处于 inactive 状态。**

`MediaSession.isActive() == false` 的后果：
- 系统不将此 session 列入"活跃媒体会话"
- Android Auto 扫描媒体应用时，该 session 不可见
- AA 显示"无可用媒体应用"

这解释了为什么绝大多数情况下 AA 看不见糯米：**普通使用（不开歌词）session 始终是 inactive 的**。

**修复**：`mSession.setActive(true)` 在 `onCreate()` 时设置，不再随歌词模式变化。

---

### 🟡 问题 2 — `MyMusicService` 启动后不主动请求 token

`MusicSessionSniffer` 已有 `reqTokenRx` 响应 `com.nuomi.REQUEST_TOKEN`——这是现成的"按需重发"机制。但当 AA 连接并调用 `onGetRoot()` 时，`MyMusicService` 没有发出这个请求。

**时序（AA 连接时，MusicSessionSniffer 已有 selectedCtrl）：**

```
AA bind MyMusicService → onGetRoot()
  → 什么都不做，只返回 BrowserRoot
  → mSession inactive，AA 认为无内容

MusicSessionSniffer 没有新通知，不会主动重发 token
  → MyMusicService 永远等不到 ACTION_CONTROLLER
```

**修复**：在 `onGetRoot()` 里发送 `com.nuomi.REQUEST_TOKEN`，触发 Sniffer 重发。

---

### 🟡 问题 3 — SharedPreferences 未选时静默失败

若用户从未打开过 SessionPickerSheet（例如手机锁屏，自动化直接连 AA），SP 里 `last_pkg` 为 null。

`MusicSessionSniffer.onListenerConnected()` → `refreshSelectedController()` → 读到 null → `selectedCtrl = null` → `sendTokenIfAny()` 无操作。

`MyMusicService.tokenRx` 永远不触发，session 空空如也。

这是预期行为（用户没选）还是 bug，取决于产品决策。**如果希望"锁屏连 AA 也能用"，需要一个默认选源策略**（例如自动选最近播放的 app，或者在 session_pref 为 null 时把第一个活跃 session 作为临时 fallback）。

---

### ⚪ 问题 4 — 旧文件残留（不影响运行，但造成混淆）

以下两个文件已无效，但还留在 repo 里：
- `mobile/src/main/java/com/example/myapplication/QqSessionSniffer.java`
- `shared/src/main/java/com/example/myapplication/shared/MyMusicService.java`（且被我们之前的 fix 修改过）

建议删除，避免日后误改。

---

## 四、修复方案

### Fix 1：`mSession.setActive(true)` 无条件开启（必须）

将 `updateSessionActive()` 的 condition 改为 `remoteCtrl != null`，或者更激进地——从 `onCreate()` 起就保持 active，session 始终对 AA 可见：

**`MyMusicService.onCreate()` 改为：**
```java
mSession.setActive(true);   // ← 无条件，让 AA 始终能看到 app
```

**`updateSessionActive()` 改为：**
```java
private void updateSessionActive(String reason) {
    // session 只要 service 在运行就保持 active；
    // AA 通过 session 内容（metadata/state）判断是否有可用媒体，不依赖 isActive() 决定显示
    if (!mSession.isActive()) {
        mSession.setActive(true);
        Log.i(TAG, "setActive=true reason=" + reason);
    }
}
```

---

### Fix 2：`onGetRoot()` 发 `REQUEST_TOKEN` ping（必须）

```java
@Override
public BrowserRoot onGetRoot(@NonNull String clientPackageName, int clientUid, Bundle rootHints) {
    // AA 每次 bind 都会调此方法（包括重连）。
    // 触发 MusicSessionSniffer 立即重发当前 token。
    LocalBroadcastManager.getInstance(this)
            .sendBroadcast(new Intent("com.nuomi.REQUEST_TOKEN"));
    return new BrowserRoot("root", null);
}
```

---

### Fix 3（可选）：默认选源 fallback

当 `last_pkg` 为 null 且存在活跃 session 时，`MusicSessionSniffer.onListenerConnected()` 可以自动选第一个活跃 session（排除本应用）并暂存，触发 token 发送。

这能让"锁屏 + 未选源"场景也能工作，代价是可能选错 app。实施前需要产品层面决策。

---

### Fix 4（清理）：删除无效旧文件

```
delete: mobile/src/main/java/com/example/myapplication/QqSessionSniffer.java
delete: shared/src/main/java/com/example/myapplication/shared/MyMusicService.java
```

---

## 五、不在此次修复范围内

| 项目 | 说明 |
|---|---|
| 歌词模式 / NCM 模式逻辑 | 功能正确，不改 |
| `mirror()` / `applyLyricsOverlay()` | 功能正确，不改 |
| `SessionSnifferService` 空壳 NLS | 设计合理，用于解锁 `getActiveSessions()` 权限 |
| `onLoadChildren()` 返回空列表 | AA 只需 session，不需要 browse 内容 |
