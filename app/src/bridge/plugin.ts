/**
 * Hermey Android bridge — the `window.hermesDesktop` implementation for
 * Android, replacing the Electron preload 1:1.
 *
 * Every method here maps to the same channel / call shape the Electron
 * preload (electron/preload.ts) exposes, so the renderer code is untouched:
 *  - `invoke(channel, ...args)` methods → `HermeyBridge.call(channel, ...args)`
 *    which routes into the Kotlin `HermeyBridgePlugin` over Capacitor.
 *  - `send(channel, ...args)` fire-and-forget methods → same, ignoring result.
 *  - `ipcRenderer.on(channel, listener)` subscriptions → a WebView-level
 *    event bus fed by `HermeyBridge.notify(channel, payload)`.
 *
 * Where a desktop capability has NO Android equivalent (floating always-on-
 * top HUD, global hotkeys, tray, system screenshot, auto-update, node-pty
 * shell), the bridge returns the most faithful equivalent Android permits
 * and marks it via `platformFacts` — the UI surfaces stay identical, they
 * simply act on what the OS allows.
 */
import { Capacitor, registerPlugin } from '@capacitor/core'

export interface HermeyBridgePlugin {
  /** One-shot call: resolves the channel's response or rejects. */
  call<T = unknown>(options: { channel: string; args?: unknown[] }): Promise<T>
  /** Fire-and-forget (Electron `ipcRenderer.send`). */
  post(options: { channel: string; args?: unknown[] }): Promise<void>
  /** Static platform facts the preload answered synchronously. */
  facts(): Promise<HermeyFacts>
  /** Notify a WebView-side listener (Electron `ipcRenderer.on`). */
  notify(options: { channel: string; payload?: unknown }): Promise<void>
  /** Subscribe to bridge events. */
  addListener(channel: string, handler: (payload: unknown) => void): Promise<{ remove: () => void }>
}

export interface HermeyFacts {
  /** Android cannot back a transparent window with a native material. */
  glassSupported: boolean
  /** Android WebView is opaque; no window translucency. */
  translucencySupported: boolean
  /** The Hermey build is remote-gateway only (no local backend). */
  remoteGatewayOnly: boolean
  /** Quick-entry (global hotkey mini composer) is stubbed on Android. */
  quickEntryStubbed: boolean
  /** Floating HUD is stubbed (no always-on-top windows on Android). */
  hudStubbed: boolean
  /** System screenshot capture is unavailable. */
  screenshotUnavailable: boolean
  /** node-pty shell is unavailable; terminal pane is stubbed. */
  terminalStubbed: boolean
  platform: string
  appVersion: string
}

export interface HermeyBridgeEvent {
  channel: string
  payload: unknown
}

/** No-op default (web preview): every call rejects "not available". */
const stub: HermeyBridgePlugin = {
  async call() {
    throw new Error('HermeyBridge is only available inside the Android app')
  },
  async post() {},
  async facts(): Promise<HermeyFacts> {
    return {
      glassSupported: false,
      translucencySupported: false,
      remoteGatewayOnly: true,
      quickEntryStubbed: true,
      hudStubbed: true,
      screenshotUnavailable: true,
      terminalStubbed: true,
      platform: 'web-preview',
      appVersion: '1.0.0'
    }
  },
  async notify() {},
  async addListener() {
    return { remove: () => {} }
  }
}

export const HermeyBridge: HermeyBridgePlugin = Capacitor.isNativePlatform()
  ? registerPlugin<HermeyBridgePlugin>('HermeyBridge')
  : stub

// ─── Event bus (mirrors ipcRenderer.on / removeListener) ────────────────────

type Handler = (payload: unknown) => void

const listeners = new Map<string, Set<Handler>>()

let nativeSubscription: { remove: () => void } | null = null

function ensureNativeSubscription(): void {
  if (nativeSubscription || !Capacitor.isNativePlatform()) {
    return
  }

  HermeyBridge.addListener('hermes:bridge-event', payload => {
    const event = payload as HermeyBridgeEvent | null

    if (!event?.channel) {
      return
    }

    const set = listeners.get(event.channel)

    if (!set) {
      return
    }

    for (const handler of [...set]) {
      try {
        handler(event.payload)
      } catch (err) {
        console.error(`[hermey-bridge] listener for ${event.channel} threw:`, err)
      }
    }
  })
    .then(handle => {
      nativeSubscription = handle
    })
    .catch(err => console.error('[hermey-bridge] failed to subscribe:', err))
}

/** Subscribe to a bridge channel. Returns the unsubscribe function, exactly
 *  like the preload's `on*` helpers. */
export function onBridgeEvent(channel: string, handler: Handler): () => void {
  ensureNativeSubscription()

  let set = listeners.get(channel)

  if (!set) {
    set = new Set()
    listeners.set(channel, set)
  }

  set.add(handler)

  return () => {
    set?.delete(handler)
  }
}

// ─── call helpers ───────────────────────────────────────────────────────────

export async function bridgeCall<T = unknown>(channel: string, ...args: unknown[]): Promise<T> {
  return HermeyBridge.call<T>({ channel, args })
}

export async function bridgePost(channel: string, ...args: unknown[]): Promise<void> {
  try {
    await HermeyBridge.post({ channel, args })
  } catch {
    /* fire-and-forget: matches ipcRenderer.send semantics */
  }
}

let factsCache: HermeyFacts | null = null

export async function bridgeFacts(): Promise<HermeyFacts> {
  factsCache ??= await HermeyBridge.facts()

  return factsCache
}
