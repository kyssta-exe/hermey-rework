# Hermey

**A 1:1 Android port of the Hermes Desktop app.**

Hermey runs the *actual* Hermes Desktop renderer — same components, same fonts, same styles, same themes, same feature set — inside an Android app. The only thing that changed is which native shell backs `window.hermesDesktop`:

- **Desktop:** Electron's `preload.ts` (IPC to the main process)
- **Android:** the Capacitor `HermeyBridgePlugin` (Kotlin), implementing the identical API

Because zero renderer lines were modified, the mobile experience is a mirror of the desktop experience. The agent connects to a **remote gateway** (a Hermes backend on your server, homelab, VPS, or Tailnet — exactly like Desktop's "Remote gateway" connection).

## Architecture

```
app/
  src/                  ← the Hermes Desktop renderer, copied verbatim (0 changes)
  fonts/                ← Collapse + JetBrains Mono (bundled, offline)
  public/               ← icons, sprites, mascot frames (copied verbatim)
  src/bridge/
    plugin.ts           ← Capacitor plugin registration + ipcRenderer-style event bus
    desktop.ts          ← the full window.hermesDesktop API (1:1 with preload.ts)
  electron-shim/        ← type-only mirrors of the 9 electron/ modules the renderer imports
  main.tsx              ← same provider mount as desktop, bridge installs first
  vite.config.ts        ← same Tailwind v4 + font + chunking pipeline as desktop
android/                ← Capacitor Android shell + Kotlin bridge
  HermeyBridgePlugin.kt ← every hermes:* channel, dispatched in Kotlin
  HermeySettings.kt     ← connection registry + prefs (SharedPreferences)
  ScreenshotActivity.kt ← MediaProjection consent host
  ScreenshotCapture.kt  ← MediaProjection screen capture
```

## What ports 1:1

Chat with streaming tool output, markdown/reasoning rendering, code blocks with syntax highlighting, the composer with all its affordances, sessions sidebar, command palette, artifacts, capabilities (skills/toolsets/MCP), messaging, webhooks, cron/scheduled jobs, profiles, agents, starmap, all 12 themes with light/dark/system modes, chat typography settings, voice input/TTS, attachments, notifications, the full settings surface, onboarding, i18n (40+ locales), plugins (kanban/radio/bots UI), find-in-page, zoom, clipboard, deep links.

## What Android can't do (best-effort stubs)

These are OS limitations, not omissions — each has the most faithful equivalent:

| Desktop feature | Android equivalent |
|---|---|
| Floating always-on-top HUD | In-app full-screen HUD view |
| Global hotkeys (Quick Entry, ⌘K wake) | In-app affordances |
| System tray / minimize-to-tray | No-op (foreground-only app) |
| macOS screenshot gesture | Android MediaProjection capture (permission-gated) |
| Auto-updater | "Check for update" → opens the release page |
| node-pty shell (terminal pane) | Faithful "unavailable on mobile" surface |
| Multi-window (session/browser pop-outs) | In-app navigation |

The remote gateway owns git worktrees, plugin installation, and marketplace themes — the same division the desktop uses for remote connections.

## Build

```bash
cd app
npm install
npm run build          # vite build → dist/ (needs NODE_OPTIONS=--max-old-space-size=8192 on <8GB hosts)
npx cap sync android
cd android && ./gradlew assembleDebug
```

The debug APK lands at `android/app/build/outputs/apk/debug/app-debug.apk`.

### Host note (ARM64 Linux)

The Android SDK's AAPT2 is an x86_64 binary. On an ARM64 host it runs under `qemu-x86_64`, which needs the x86_64 dynamic loader present:

```bash
# /lib64/ld-linux-x86-64.so.2 must resolve to a real x86_64 glibc loader
```

Without it, `:app:processDebugResources` fails with "Daemon startup failed".

## Connection

On first launch, open **Settings → Gateway** and point Hermey at your remote Hermes gateway URL (e.g. `https://hermes.example.com`), with a token or OAuth. Everything else — sessions, cron, memory, skills — is served by that gateway, exactly as Desktop's remote mode works.

## License

Built to work with [Hermes Agent](https://github.com/NousResearch/hermes-agent) by Nous Research (MIT). Hermes, Hermes Agent, and Hermes Desktop are names of Nous Research's project; Hermey uses them only to say what it works with.
