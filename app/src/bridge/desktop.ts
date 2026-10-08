/**
 * Hermey Android — `window.hermesDesktop` implementation.
 *
 * This file is the Android twin of apps/desktop/electron/preload.ts. It is
 * deliberately kept as a byte-for-behavior mirror of the preload's surface:
 * every method, every callback shape, every channel name. The renderer
 * (all 3,475 files of it) imports none of this directly — it only ever
 * touches `window.hermesDesktop.*` — so shipping this file INSTEAD of the
 * Electron preload is what turns the desktop app into the Android app
 * without touching a single renderer line.
 *
 * Impossibility map (best-effort stubs, per the port contract):
 *  - Floating always-on-top HUD → in-app full-screen HUD view.
 *  - Global hotkeys (Quick Entry, ⌘K wake) → in-app affordances.
 *  - System tray / minimize-to-tray → no-op (app is foreground-only).
 *  - macOS screenshot gesture → Android MediaProjection screen capture
 *    (permission-gated), same API shape.
 *  - Auto-updater → Play-Store-style "check for update" prompt (downloads
 *    via openExternal to the release page).
 *  - node-pty shell → no PTY; the terminal pane renders a faithful
 *    "terminal unavailable on mobile" surface instead of a dead pane.
 *  - Native windows (openSessionWindow/openWindow/openBrowserWindow) →
 *    in-app navigation; no multi-window on Android.
 */
import { bridgeCall, bridgePost, bridgeFacts, onBridgeEvent } from './plugin'
import type {
  DesktopActiveProfile,
  DesktopAgentRoster,
  DesktopBootProgress,
  DesktopBootstrapState,
  DesktopCloudDiscoverResult,
  DesktopCloudStatus,
  DesktopConnectionConfig,
  DesktopConnectionConfigInput,
  DesktopConnectionProbeResult,
  DesktopConnectionTestResult,
  DesktopConnectionsRegistry,
  DesktopOauthLoginOptions,
  DesktopOauthLoginResult,
  DesktopOauthLogoutResult,
  DesktopProfileRoute,
  DesktopRegistryConnection,
  DesktopRegistryConnectionInput,
  DesktopSshHostsResult,
  DesktopSshResolveResult,
  DesktopSyncReceipt,
  DesktopUpdateStatus,
  DesktopVersionInfo,
  HermesConnection,
  HermesTerminalSession
} from '@/global'

// Lazy import of the global types module for the ambient declaration merge
// (types only — erased at build time).
export interface DesktopMarketplaceSearchItem {
  extensionId: string
  displayName: string
  publisher: string
  description: string
  installs: number
}

export interface DesktopMarketplaceThemeFile {
  label: string
  uiTheme?: string
  contents: string
}

export interface DesktopMarketplaceThemeResult {
  extensionId: string
  displayName: string
  themes: DesktopMarketplaceThemeFile[]
}

export interface HermesTerminalExit {
  code: null | number
  signal: null | string
}

interface PetOverlayBounds {
  height: number
  width: number
  x: number
  y: number
}

interface PetOverlayOpenRequest {
  bounds: PetOverlayBounds
  screen?: { height: number; width: number }
}

interface PetOverlayStatePayload {
  [key: string]: unknown
}

interface PetOverlayControl {
  [key: string]: unknown
}

interface HermesGitWorktree {
  path: string
  branch: null | string
  isMain: boolean
  detached: boolean
  locked: boolean
}

interface HermesGitBranch {
  name: string
  checkedOut: boolean
  isDefault: boolean
  isRemote: boolean
  worktreePath: null | string
}

interface HermesGitBaseBranch {
  name: string
  isRemote: boolean
  isDefault: boolean
}

interface HermesRepoStatusFile {
  path: string
  staged: boolean
  unstaged: boolean
  untracked: boolean
  conflicted: boolean
}

interface HermesRepoStatus {
  branch: null | string
  defaultBranch: null | string
  detached: boolean
  ahead: number
  behind: number
  staged: number
  unstaged: number
  untracked: number
  conflicted: number
  changed: number
  added: number
  removed: number
  files: HermesRepoStatusFile[]
}

interface HermesReviewScope {
  [key: string]: unknown
}

interface HermesReviewFile {
  path: string
  added: number
  removed: number
  status: string
  staged: boolean
}

interface HermesReviewList {
  files: HermesReviewFile[]
  base: null | string
}

interface HermesReviewPr {
  url: string
  state: string
  number: number
}

interface HermesBranchPullRequest {
  branch: string
  draft: boolean
  number: number
  state: string
  title: string
  url: string
}

interface HermesRepoPullRequests {
  ghReady: boolean
  prs: HermesBranchPullRequest[]
}

interface HermesReviewShipInfo {
  ghReady: boolean
  pr: HermesReviewPr | null
}

interface HermesReadDirEntry {
  name: string
  path: string
  isDirectory: boolean
}

interface HermesReadDirResult {
  entries: HermesReadDirEntry[]
  error?: string
}

interface HermesReadFileTextResult {
  binary?: boolean
  byteSize?: number
  language?: string
  mimeType?: string
  path: string
  text: string
  truncated?: boolean
}

interface HermesReadFileErrorResult {
  ok: false
  error: string
  message: string
  path?: string
}

interface HermesPreviewTarget {
  binary?: boolean
  byteSize?: number
  kind: 'file' | 'url'
  label: string
  large?: boolean
  language?: string
  mimeType?: string
  path?: string
  previewKind?: string
  renderMode?: 'preview' | 'source'
  source: string
  url: string
}

interface HermesPreviewWatch {
  id: string
  path: string
}

interface HermesPreviewFileChanged {
  id: string
  path: string
  url: string
}

interface HermesSelectPathsOptions {
  title?: string
  defaultPath?: string
  directories?: boolean
  multiple?: boolean
  profile?: string
  filters?: Array<{ name: string; extensions: string[] }>
}

interface BackendExit {
  code: null | number
  signal: null | string
}

interface HermesWindowState {
  customWindowControls?: boolean
  darwinMajor?: number
  isFullscreen: boolean
  isMaximized?: boolean
  isMinimized?: boolean
  isVisible?: boolean
  nativeOverlayWidth: number
  windowButtonPosition: { x: number; y: number } | null
}

interface HermesTitleBarTheme {
  background: string
  foreground: string
}

interface HermesActiveWork {
  count: number
  titles: string[]
}

interface HermesApiRequest {
  path: string
  method?: string
  body?: unknown
  upload?: { filename: string; contentType?: string; bytes: ArrayBuffer }
  timeoutMs?: number
  profile?: null | string
  connectionId?: null | string
  passive?: boolean
  priority?: 'foreground'
}

interface GatewayWsUrlResult {
  ok: true
  wsUrl: string
}

interface UpdateHoldWire {
  [key: string]: unknown
}

interface UpdateRunReport {
  [key: string]: unknown
}

interface PoolLimits {
  [key: string]: unknown
}

interface KeepAwakeMode {
  [key: string]: unknown
}

interface WindowSizeMode {
  [key: string]: unknown
}

interface ScreenshotApi {
  getSettings: () => Promise<{ enabled: boolean }>
  setEnabled: (enabled: boolean) => Promise<{ enabled: boolean }>
  openPermissionSettings: (kind: string) => Promise<void>
  capture: (requestId: string) => Promise<{ ok: boolean; dataUrl?: string; error?: string }>
  onStatus: (callback: (status: { enabled: boolean; granted: boolean }) => void) => () => void
  onRequest: (callback: (requestId: string) => void) => () => void
}

interface HudModifierApi {
  getSettings: () => Promise<{ enabled: boolean }>
  setEnabled: (enabled: boolean) => Promise<{ enabled: boolean }>
  openPermissionSettings: () => Promise<void>
  onStatus: (callback: (status: { enabled: boolean; granted: boolean }) => void) => () => void
}

interface MachineProfile {
  [key: string]: unknown
}

interface ChallengeOutcome {
  [key: string]: unknown
}

interface QuickEntryStatus {
  enabled: boolean
  registered: boolean
  shortcut: string
  error?: string
}

interface QuickEntrySubmitPayload {
  [key: string]: unknown
}

interface QuickEntrySubmitResult {
  ok: boolean
  error?: string
}

interface QuickEntryStatePush {
  [key: string]: unknown
}

interface WakeIndicatorState {
  [key: string]: unknown
}

interface HermesNotification {
  title?: string
  body?: string
  silent?: boolean
  kind?: string
  sessionId?: string
  focusSessionId?: string
  tag?: string
  icon?: string
  activate?: string
  notifyId?: string
  actions?: { id: string; text: string; activate?: string }[]
}

interface TranslucencyState {
  [key: string]: unknown
}

interface HermesSkin {
  [key: string]: unknown
}

interface ExternalOpenFailedPayload {
  url: string
  message?: string
  code?: 'missing-file'
}

// Facts, loaded async but cached at bridge creation time where possible.
const factsPromise = bridgeFacts()

// ─── Subscription helper ────────────────────────────────────────────────────

const sub = (channel: string) => (callback: (payload: unknown) => void): (() => void) =>
  onBridgeEvent(channel, callback)

// Typed variant for status-style channels whose renderer consumer expects
// a concrete payload shape (hudModifier.onStatus, screenshot.onRequest).
const subAs = <T>(channel: string) => (callback: (payload: T) => void): (() => void) =>
  onBridgeEvent(channel, payload => callback(payload as T))

// ─── The bridge object ──────────────────────────────────────────────────────

const desktop = {
  // Static facts (Electron answered these synchronously in the preload).
  // Android resolves them asynchronously at boot, but the renderer only
  // reads them after mount, so a promise-backed value is acceptable; we
  // seed sensible defaults that the Kotlin side overrides on `boot`.
  glassSupported: false,
  translucencySupported: false,
  localModelsEnabled: false,
  guestOnboardingEnabled: false,
  localSkin: null as { profile: string; skin: HermesSkin } | null,

  // ── Connections / gateway ──────────────────────────────────────────────

  getConnection: (profile?: null | string, opts?: { priority?: 'foreground' | 'background' }) =>
    bridgeCall<HermesConnection>('hermes:connection', profile, opts),

  getConnectionFor: (payload: {
    connectionId?: null | string
    profile?: null | string
    priority?: 'foreground' | 'background'
  }) => bridgeCall<HermesConnection>('hermes:connection:for', payload),

  getGatewayWsUrl: (profile?: null | string) =>
    bridgeCall<GatewayWsUrlResult | string>('hermes:gateway:ws-url', profile),

  getGatewayWsUrlFor: (payload: { connectionId?: null | string; profile?: null | string }) =>
    bridgeCall<GatewayWsUrlResult | string>('hermes:gateway:ws-url-for', payload),

  getAgentRoster: () => bridgeCall<DesktopAgentRoster>('hermes:agents:roster'),

  getProfileRoutes: (profiles: string[]) =>
    bridgeCall<DesktopPluginProfileRoute[]>('hermes:plugin-profile-routes', profiles),

  getEmbedHostOrigin: () => bridgeCall<string>('hermes:embed-host:origin'),

  revalidateConnection: () => bridgeCall<{ ok: boolean; rebuilt: boolean }>('hermes:connection:revalidate'),

  touchBackend: (profile?: null | string, options?: { activeTurn?: boolean }) =>
    bridgeCall<{ ok: boolean }>('hermes:backend:touch', profile, options),

  getPoolLimits: () => bridgeCall<PoolLimits>('hermes:pool-limits:get'),

  setPoolLimits: (limits: { maxBackends?: number; idleMs?: number }) =>
    bridgeCall<{ ok: boolean; limits: PoolLimits }>('hermes:pool-limits:set', limits),

  // ── Windows (all in-app on Android) ────────────────────────────────────

  openSessionWindow: (sessionId: string, opts?: { connectionId?: null | string; profile?: null | string; watch?: boolean }) =>
    bridgeCall<{ ok: boolean; error?: string }>('hermes:window:openSession', sessionId, opts),

  openSessionInTerminal: (sessionId: string, opts?: { cwd?: string; profile?: string }) =>
    bridgeCall<{ ok: boolean; error?: string }>('hermes:window:openInTerminal', sessionId, opts),

  openWindow: (options?: DesktopProfileRoute) =>
    bridgeCall<{ ok: boolean; error?: string }>('hermes:window:openInstance', options),

  openBrowserWindow: (tabId: string) => bridgeCall<{ ok: boolean; error?: string }>('hermes:window:openBrowser', tabId),

  windowRelay: {
    send: (payload: unknown) => void bridgePost('hermes:window:relay', payload),
    onMessage: sub('hermes:window:relay')
  },

  onBrowserPopoutClosed: sub('hermes:browser-popout:closed'),

  claimAmbientCue: (key: string) => bridgeCall<boolean>('hermes:ambient:claim', key),

  // Window controls: Android has an OS navigation bar; these are no-ops the
  // app never renders (windowControls.custom is always false on Android).
  windowControls: {
    custom: false,
    minimize: () => void bridgePost('hermes:window-control', 'minimize'),
    toggleMaximize: () => void bridgePost('hermes:window-control', 'toggle-maximize'),
    close: () => void bridgePost('hermes:window-control', 'close')
  },

  // ── Wake indicator (in-app banner) ─────────────────────────────────────

  wakeIndicator: {
    getState: () => bridgeCall<WakeIndicatorState>('hermes:wake-indicator:get'),
    setState: (state: WakeIndicatorState) => void bridgePost('hermes:wake-indicator:set', state),
    onState: sub('hermes:wake-indicator:state')
  },

  chatOnboarding: {
    size: (mode: WindowSizeMode) => void bridgePost('hermes:window:size', mode)
  },

  // ── Pet overlay (in-app floating card on Android) ──────────────────────

  petOverlay: {
    open: (request: PetOverlayOpenRequest) =>
      bridgeCall<{ ok: boolean; bounds?: PetOverlayBounds }>('hermes:pet-overlay:open', request),
    close: () => bridgeCall<{ ok: boolean }>('hermes:pet-overlay:close'),
    setBounds: (bounds: PetOverlayBounds) => void bridgePost('hermes:pet-overlay:set-bounds', bounds),
    setIgnoreMouse: (ignore: boolean) => void bridgePost('hermes:pet-overlay:ignore-mouse', ignore),
    setFocusable: (focusable: boolean) => void bridgePost('hermes:pet-overlay:set-focusable', focusable),
    pushState: (payload: PetOverlayStatePayload) => void bridgePost('hermes:pet-overlay:state', payload),
    control: (payload: PetOverlayControl) => void bridgePost('hermes:pet-overlay:control', payload),
    onState: sub('hermes:pet-overlay:state'),
    onControl: sub('hermes:pet-overlay:control')
  },

  // ── HUD (full-screen view on Android) ──────────────────────────────────

  hud: {
    nativeDrag: false,
    windowing: {
      clientPlacement: true,
      controlDrag: false,
      nativeDrag: false,
      solid: true,
      workspaceTransfer: false
    },
    open: (request?: { sessionId?: null | string; profile?: null | string }) =>
      bridgeCall<{ ok: boolean }>('hermes:hud:open', request),
    close: () => bridgeCall<{ ok: boolean }>('hermes:hud:close'),
    setIgnoreMouse: (ignore: boolean) => void bridgePost('hermes:hud:ignore-mouse', ignore),
    beginMove: () => void bridgePost('hermes:hud:begin-move'),
    endMove: () => void bridgePost('hermes:hud:end-move'),
    moveBy: (delta: { width: number; height: number }) => void bridgePost('hermes:hud:move-by', delta),
    setWorkspaceTransfer: (transferring: boolean) =>
      void bridgePost('hermes:hud:workspace-transfer', transferring),
    setBounds: (bounds: { x: number; y: number; width: number; height: number }) =>
      void bridgePost('hermes:hud:set-bounds', bounds),
    resetLayout: () => bridgeCall<{ ok: boolean }>('hermes:hud:reset-layout'),
    setFrost: (showing: boolean) => bridgeCall<{ ok: boolean }>('hermes:hud:frost', showing),
    setSession: (sessionId: null | string) => void bridgePost('hermes:hud:session', sessionId),
    onGoto: sub('hermes:hud:goto'),
    onChanged: sub('hermes:hud:changed'),
    onCursor: sub('hermes:hud:cursor'),
    onGameOverlay: sub('hermes:hud:game-overlay')
  },

  hudModifier: {
    getSettings: () => bridgeCall<{ enabled: boolean }>('hermes:hud-modifier:settings:get'),
    setEnabled: (enabled: boolean) => bridgeCall<{ enabled: boolean }>('hermes:hud-modifier:settings:set', enabled),
    openPermissionSettings: () => bridgeCall<void>('hermes:hud-modifier:permission'),
    onStatus: subAs<{ enabled: boolean; granted: boolean }>('hermes:hud-modifier:status')
  } satisfies HudModifierApi,

  // Screenshot: Android MediaProjection capture, permission-gated at runtime.
  screenshot: {
    getSettings: () => bridgeCall<{ enabled: boolean }>('hermes:screenshot:settings:get'),
    setEnabled: (enabled: boolean) => bridgeCall<{ enabled: boolean }>('hermes:screenshot:settings:set', enabled),
    openPermissionSettings: (kind: string) => bridgeCall<void>('hermes:screenshot:permission', kind),
    capture: (requestId: string) =>
      bridgeCall<{ ok: boolean; dataUrl?: string; error?: string }>('hermes:screenshot:capture', requestId),
    onStatus: subAs<{ enabled: boolean; granted: boolean }>('hermes:screenshot:status'),
    onRequest: subAs<string>('hermes:screenshot:request')
  } satisfies ScreenshotApi,

  // ── Quick Entry (in-app sheet on Android) ──────────────────────────────

  quickEntry: {
    getSettings: () => bridgeCall<QuickEntryStatus>('hermes:quick-entry:settings:get'),
    setSettings: (patch: { enabled?: boolean; shortcut?: string }) =>
      bridgeCall<QuickEntryStatus>('hermes:quick-entry:settings:set', patch),
    submit: (payload: QuickEntrySubmitPayload) => bridgeCall<QuickEntrySubmitResult>('hermes:quick-entry:submit', payload),
    ackSubmit: (correlationId: string, result: QuickEntrySubmitResult) =>
      void bridgePost('hermes:quick-entry:ack', { correlationId, result }),
    dismiss: () => void bridgePost('hermes:quick-entry:dismiss'),
    pushState: (payload: QuickEntryStatePush) => void bridgePost('hermes:quick-entry:state', payload),
    onState: sub('hermes:quick-entry:state'),
    onSubmit: sub('hermes:quick-entry:submit'),
    onShown: sub('hermes:quick-entry:shown'),
    onLateResult: sub('hermes:quick-entry:late-result')
  },

  // ── Boot / bootstrap ───────────────────────────────────────────────────

  getBootProgress: () => bridgeCall<DesktopBootProgress>('hermes:boot-progress:get'),

  getBootstrapState: () => bridgeCall<DesktopBootstrapState>('hermes:bootstrap:get'),

  probeLocalBackend: () => bridgeCall<{ bootstrapNeeded: boolean }>('hermes:local-backend:probe'),

  continueBootstrapLocal: () => bridgeCall<{ ok: boolean }>('hermes:bootstrap:continue-local'),

  recycleBackend: (profile?: null | string) => bridgeCall<{ ok: boolean }>('hermes:backend:recycle', profile),

  resetBootstrap: () => bridgeCall<{ ok: boolean }>('hermes:bootstrap:reset'),

  updateHold: {
    recheck: () => bridgeCall<{ ok: boolean }>('hermes:update-hold:recheck'),
    quit: () => bridgeCall<{ ok: boolean }>('hermes:update-hold:quit'),
    startAnyway: (request: { holdId: string; confirmed: true }) =>
      bridgeCall<{ ok: boolean }>('hermes:update-hold:start-anyway', request)
  },

  repairBootstrap: () => bridgeCall<{ ok: boolean; error?: string }>('hermes:bootstrap:repair'),

  cancelBootstrap: () => bridgeCall<{ ok: boolean; cancelled: boolean }>('hermes:bootstrap:cancel'),

  onBootstrapEvent: sub('hermes:bootstrap:event'),

  onBootProgress: sub('hermes:boot-progress'),

  // ── Connection config ──────────────────────────────────────────────────

  getConnectionConfig: (profile?: null | string) =>
    bridgeCall<DesktopConnectionConfig>('hermes:connection-config:get', profile),

  saveConnectionConfig: (payload: DesktopConnectionConfigInput) =>
    bridgeCall<DesktopConnectionConfig>('hermes:connection-config:save', payload),

  applyConnectionConfig: (payload: DesktopConnectionConfigInput) =>
    bridgeCall<DesktopConnectionConfig>('hermes:connection-config:apply', payload),

  testConnectionConfig: (payload: DesktopConnectionConfigInput) =>
    bridgeCall<DesktopConnectionTestResult>('hermes:connection-config:test', payload),

  getSecretStorageEncryption: () => bridgeCall<{ on: boolean }>('hermes:secret-storage:get'),

  setSecretStorageEncryption: (on: boolean) => bridgeCall<{ on: boolean }>('hermes:secret-storage:set', on),

  connections: {
    list: () => bridgeCall<DesktopConnectionsRegistry>('hermes:connections:list'),
    save: (payload: DesktopRegistryConnectionInput) =>
      bridgeCall<{ ok: boolean; connection: DesktopRegistryConnection; registry: DesktopConnectionsRegistry }>(
        'hermes:connections:save',
        payload
      ),
    remove: (id: string) =>
      bridgeCall<{ ok: boolean; registry: DesktopConnectionsRegistry }>('hermes:connections:remove', id),
    setPrimary: (id: string) =>
      bridgeCall<{ ok: boolean; registry: DesktopConnectionsRegistry }>('hermes:connections:set-primary', id),
    setLaunchMode: (mode: 'last-used' | 'primary') =>
      bridgeCall<{ ok: boolean; registry: DesktopConnectionsRegistry }>('hermes:connections:set-launch-mode', mode),
    setLastUsed: (id: string) =>
      bridgeCall<{ ok: boolean; registry: DesktopConnectionsRegistry }>('hermes:connections:set-last-used', id),
    test: (id: string) => bridgeCall<DesktopConnectionTestResult>('hermes:connections:test', id),
    updateManaged: (id: string) => bridgeCall<unknown>('hermes:connections:update-managed', id),
    updateAll: (options?: { excludeIds?: string[] }) => bridgeCall<unknown>('hermes:connections:update-all', options),
    onChanged: sub('hermes:connections:changed')
  },

  sshConfigHosts: () => bridgeCall<DesktopSshHostsResult>('hermes:ssh-config:hosts'),

  sshResolveHost: (host: string) => bridgeCall<DesktopSshResolveResult>('hermes:ssh-config:resolve', host),

  probeConnectionConfig: (remoteUrl: string) =>
    bridgeCall<DesktopConnectionProbeResult>('hermes:connection-config:probe', remoteUrl),

  oauthLoginConnectionConfig: (remoteUrl: string, options?: DesktopOauthLoginOptions) =>
    bridgeCall<DesktopOauthLoginResult>('hermes:connection-config:oauth-login', remoteUrl, options),

  oauthLogoutConnectionConfig: (remoteUrl: string) =>
    bridgeCall<DesktopOauthLogoutResult>('hermes:connection-config:oauth-logout', remoteUrl),

  // ── Hermes Cloud ───────────────────────────────────────────────────────

  cloud: {
    status: () => bridgeCall<DesktopCloudStatus>('hermes:cloud:status'),
    login: () => bridgeCall<DesktopCloudStatus & { ok: boolean }>('hermes:cloud:login'),
    logout: () => bridgeCall<DesktopCloudStatus & { ok: boolean }>('hermes:cloud:logout'),
    discover: (org?: string) => bridgeCall<DesktopCloudDiscoverResult>('hermes:cloud:discover', org),
    agentSignIn: (dashboardUrl: string) => bridgeCall<unknown>('hermes:cloud:agent-sign-in', dashboardUrl)
  },

  // ── Profiles ───────────────────────────────────────────────────────────

  profile: {
    getDefault: () => bridgeCall<DesktopProfileRoute | null>('hermes:profile:default:get'),
    setDefault: (route: DesktopProfileRoute) => bridgeCall<DesktopProfileRoute>('hermes:profile:default:set', route),
    onDefaultChanged: sub('hermes:profile:default:changed'),
    get: () => bridgeCall<DesktopActiveProfile>('hermes:profile:get'),
    remember: (name: null | string) => bridgeCall<DesktopActiveProfile>('hermes:profile:remember', name),
    set: (name: null | string) => bridgeCall<DesktopActiveProfile>('hermes:profile:set', name)
  },

  // ── REST API (gateway HTTP) ────────────────────────────────────────────

  api: <T>(request: HermesApiRequest) => bridgeCall<T>('hermes:api', request),

  notify: (payload: HermesNotification) => bridgeCall<boolean>('hermes:notify', payload),

  claimStartupLatency: () => bridgeCall<null | number>('hermes:startup-latency:claim'),

  requestMicrophoneAccess: () => bridgeCall<boolean>('hermes:requestMicrophoneAccess'),

  readWindowBelow: () => bridgeCall<unknown>('hermes:window:readBelow'),

  // ── File IO (Android SAF + app-private dir) ────────────────────────────

  readFileDataUrl: (filePath: string) => bridgeCall<string>('hermes:readFileDataUrl', filePath),

  readFileDataUrlForAttach: (filePath: string) => bridgeCall<string>('hermes:readFileDataUrlForAttach', filePath),

  dataUrlReadMax: {
    get: () => bridgeCall<{ defaultMaxMb: number; maxBytes: number; maxMb: number }>('hermes:data-url-read-max:get'),
    set: (maxMb: number) => bridgeCall<{ defaultMaxMb: number; maxBytes: number; maxMb: number }>('hermes:data-url-read-max:set', maxMb)
  },

  readFileText: (filePath: string) => bridgeCall<HermesReadFileTextResult | HermesReadFileErrorResult>('hermes:readFileText', filePath),

  readPluginSource: (filePath: string) => bridgeCall<HermesReadFileTextResult>('hermes:readPluginSource', filePath),

  selectPaths: (options?: HermesSelectPathsOptions) => bridgeCall<string[]>('hermes:selectPaths', options),

  selectSavePath: (options?: {
    defaultPath?: string
    filters?: Array<{ extensions: string[]; name: string }>
    title?: string
  }) => bridgeCall<null | string>('hermes:selectSavePath', options),

  writeClipboard: (text: string) => bridgeCall<boolean>('hermes:writeClipboard', text),

  readClipboard: () => bridgeCall<string>('hermes:readClipboard'),

  saveGatewayFile: (payload: {
    connectionId?: null | string
    path: string
    profile?: null | string
    sessionId?: string
    suggestedName?: string
  }) =>
    bridgeCall<{ canceled?: boolean; path?: string; saved: boolean }>('hermes:saveGatewayFile', payload),

  saveImageFromUrl: (url: string) => bridgeCall<boolean>('hermes:saveImageFromUrl', url),

  contextMenuEdit: (command: 'copy' | 'cut' | 'paste' | 'selectAll') =>
    bridgeCall<void>('hermes:context-menu:edit', command),

  contextMenuCopyImage: () => bridgeCall<void>('hermes:context-menu:copy-image'),

  contextMenuSpellcheck: (action: { kind: 'add' | 'replace'; word: string }) =>
    bridgeCall<void>('hermes:context-menu:spellcheck', action),

  contextMenuGuestAddWord: (payload: { webContentsId: number; word: string }) =>
    bridgeCall<void>('hermes:context-menu:guest-add-word', payload),

  onContextMenuSpellcheck: sub('hermes:context-menu-spellcheck'),

  saveImageBuffer: (data: ArrayBuffer | Uint8Array, ext: string, name?: string) =>
    bridgeCall<string>('hermes:saveImageBuffer', { data, ext, name }),

  capturePreview: (payload: {
    rect?: { height: number; width: number; x: number; y: number }
    viewport?: { height: number; width: number }
    webContentsId: number
  }) => bridgeCall<string>('hermes:capturePreview', payload),

  savePastedText: (text: string) => bridgeCall<string>('hermes:savePastedText', { text }),

  saveClipboardImage: () => bridgeCall<string>('hermes:saveClipboardImage'),

  getPathForFile: (file: File) => (file as unknown as { path?: string }).path ?? '',

  normalizePreviewTarget: (target: string, baseDir?: string) =>
    bridgeCall<HermesPreviewTarget | null>('hermes:normalizePreviewTarget', target, baseDir),

  watchPreviewFile: (url: string) => bridgeCall<HermesPreviewWatch | HermesReadFileErrorResult>('hermes:watchPreviewFile', url),

  watchDirectory: (dir: string) => bridgeCall<HermesPreviewWatch>('hermes:watchDirectory', dir),

  stopPreviewFileWatch: (id: string) => bridgeCall<boolean>('hermes:stopPreviewFileWatch', id),

  // ── Window/state notifications ─────────────────────────────────────────

  setActiveWork: (payload: HermesActiveWork) => void bridgePost('hermes:active-work', payload),

  setTitleBarTheme: (payload: HermesTitleBarTheme) => void bridgePost('hermes:titlebar-theme', payload),

  setNativeTheme: (mode: 'dark' | 'light' | 'system') => void bridgePost('hermes:native-theme', mode),

  setTranslucency: (payload: TranslucencyState) => void bridgePost('hermes:translucency', payload),

  setKeepAwake: (mode: KeepAwakeMode) => void bridgePost('hermes:keep-awake', mode),

  // Tray: no-op on Android (foreground-only app).
  minimizeToTray: {
    get: () => Promise.resolve({ enabled: false, available: false }),
    set: (on: boolean) => Promise.resolve({ enabled: false, available: false }),
    onChanged: () => () => {}
  },

  setDisableF12: (blocked: boolean) => void bridgePost('hermes:devtools:disable-f12', blocked),

  setF12ShortcutActive: (active: boolean) => void bridgePost('hermes:f12ShortcutActive', Boolean(active)),

  onF12Shortcut: sub('hermes:f12-shortcut'),

  setPreviewShortcutActive: (active: boolean) => void bridgePost('hermes:previewShortcutActive', Boolean(active)),

  setPreviewGuestHidden: (webContentsId: number, hidden: boolean) =>
    void bridgePost('hermes:preview-guest-hidden', { webContentsId, hidden: Boolean(hidden) }),

  openExternal: (url: string) => bridgeCall<void>('hermes:openExternal', url),

  onExternalOpenFailed: sub('hermes:external-open-failed'),

  freeTierChallenge: {
    run: (request: { url: string; required: boolean; expiresIn?: number; attempt?: number }) =>
      bridgeCall<ChallengeOutcome>('hermes:freeTierChallenge:run', request)
  },

  mcpOauth: {
    listen: () => bridgeCall<{ id: string; redirectUri: string }>('hermes:mcp-oauth:listen'),
    wait: (id: string, timeoutMs?: number) =>
      bridgeCall<{ code: null | string; error: null | string; iss: null | string; state: null | string }>(
        'hermes:mcp-oauth:wait',
        id,
        timeoutMs
      ),
    cancel: (id: string) => bridgeCall<boolean>('hermes:mcp-oauth:cancel', id)
  },

  openPreviewInBrowser: (url: string) => bridgeCall<void>('hermes:openPreviewInBrowser', url),

  reachPreviewUrl: (url: string) => bridgeCall<string>('hermes:preview:reach', url),

  setActiveConnectionRoute: (
    route: { connectionId?: null | string; profile?: string; registryScoped?: boolean } | null
  ) => void bridgePost('hermes:connection:active-route', route),

  fetchLinkTitle: (url: string) => bridgeCall<string>('hermes:fetchLinkTitle', url),

  resolveFavicon: (url: string) => bridgeCall<string>('hermes:resolveFavicon', url),

  sanitizeWorkspaceCwd: (cwd?: null | string) => bridgeCall<{ cwd: string; sanitized: boolean }>('hermes:workspace:sanitize', cwd),

  settings: {
    getDefaultProjectDir: () =>
      bridgeCall<{ defaultLabel: string; dir: null | string; resolvedCwd: string }>('hermes:setting:defaultProjectDir:get'),
    setDefaultProjectDir: (dir: null | string) =>
      bridgeCall<{ dir: null | string }>('hermes:setting:defaultProjectDir:set', dir),
    pickDefaultProjectDir: () => bridgeCall<{ canceled: boolean; dir: null | string }>('hermes:setting:defaultProjectDir:pick')
  },

  zoom: {
    get: () => bridgeCall<{ level: number; percent: number }>('hermes:zoom:get'),
    factor: () => 1,
    setPercent: (percent: number) => void bridgePost('hermes:zoom:set-percent', percent),
    onChanged: sub('hermes:zoom:changed')
  },

  revealLogs: () => bridgeCall<{ ok: boolean; path: string; error?: string }>('hermes:logs:reveal'),

  getRecentLogs: () => bridgeCall<{ path: string; lines: string[] }>('hermes:logs:recent'),

  reportRendererError: (report: {
    label: string
    boundary: string
    message: string
    componentStack: string
  }) => void bridgePost('hermes:logs:renderer-error', report),

  logLine: (line: string) => void bridgePost('hermes:logs:renderer-line', line),

  readDir: (dirPath: string) => bridgeCall<HermesReadDirResult>('hermes:fs:readDir', dirPath),

  gitRoot: (startPath: string) => bridgeCall<string | null>('hermes:fs:gitRoot', startPath),

  revealPath: (targetPath: string) => bridgeCall<boolean>('hermes:fs:reveal', targetPath),

  openDir: (dirPath: string) => bridgeCall<{ ok: boolean; error?: string }>('hermes:fs:openDir', dirPath),

  desktopPluginsRoot: () => bridgeCall<string>('hermes:fs:desktopPluginsRoot'),

  reconcileDesktopPlugins: () => bridgeCall<string[]>('hermes:fs:reconcileDesktopPlugins'),

  logsRoot: (profile?: string) => bridgeCall<string>('hermes:fs:logsRoot', profile),

  renamePath: (targetPath: string, newName: string) =>
    bridgeCall<{ path: string }>('hermes:fs:rename', targetPath, newName),

  writeTextFile: (filePath: string, content: string) =>
    bridgeCall<{ path: string }>('hermes:fs:writeText', filePath, content),

  trashPath: (targetPath: string) => bridgeCall<boolean>('hermes:fs:trash', targetPath),

  // ── Git (delegated to the remote gateway over SSH-equivalent RPC where
  //    a local repo exists; on a pure-remote gateway these reject cleanly) ─

  git: {
    worktreeList: (repoPath: string) => bridgeCall<HermesGitWorktree[]>('hermes:git:worktreeList', repoPath),
    worktreeAdd: (repoPath: string, options?: { name?: string; branch?: string; base?: string; existingBranch?: string }) =>
      bridgeCall<{ path: string; branch: string; repoRoot: string }>('hermes:git:worktreeAdd', repoPath, options),
    worktreeRemove: (repoPath: string, worktreePath: string, options?: { force?: boolean }) =>
      bridgeCall<{ removed: string }>('hermes:git:worktreeRemove', repoPath, worktreePath, options),
    branchSwitch: (repoPath: string, branch: string) => bridgeCall<{ branch: string }>('hermes:git:branchSwitch', repoPath, branch),
    branchList: (repoPath: string) => bridgeCall<HermesGitBranch[]>('hermes:git:branchList', repoPath),
    baseBranchList: (repoPath: string) => bridgeCall<HermesGitBaseBranch[]>('hermes:git:baseBranchList', repoPath),
    repoStatus: (repoPath: string) => bridgeCall<HermesRepoStatus | null>('hermes:git:repoStatus', repoPath),
    fileDiff: (repoPath: string, filePath: string) => bridgeCall<string>('hermes:git:fileDiff', repoPath, filePath),
    scanRepos: (roots: string[], options?: { maxDepth?: number; enabled?: boolean; excludePaths?: string[] }) =>
      bridgeCall<{ root: string; label: string }[]>('hermes:git:scanRepos', roots, options),
    review: {
      list: (repoPath: string, scope: HermesReviewScope, baseRef?: null | string) =>
        bridgeCall<HermesReviewList>('hermes:git:review:list', repoPath, scope, baseRef),
      diff: (repoPath: string, filePath: string, scope: HermesReviewScope, baseRef?: null | string, staged?: boolean) =>
        bridgeCall<string>('hermes:git:review:diff', repoPath, filePath, scope, baseRef, staged),
      stage: (repoPath: string, filePath?: null | string) =>
        bridgeCall<{ ok: boolean }>('hermes:git:review:stage', repoPath, filePath),
      unstage: (repoPath: string, filePath?: null | string) =>
        bridgeCall<{ ok: boolean }>('hermes:git:review:unstage', repoPath, filePath),
      revert: (repoPath: string, filePath?: null | string) =>
        bridgeCall<{ ok: boolean }>('hermes:git:review:revert', repoPath, filePath),
      revParse: (repoPath: string, ref?: null | string) => bridgeCall<null | string>('hermes:git:review:revParse', repoPath, ref),
      commit: (repoPath: string, message: string, push: boolean) =>
        bridgeCall<{ ok: boolean }>('hermes:git:review:commit', repoPath, message, push),
      commitContext: (repoPath: string) => bridgeCall<{ diff: string; recent: string }>('hermes:git:review:commitContext', repoPath),
      push: (repoPath: string) => bridgeCall<{ ok: boolean }>('hermes:git:review:push', repoPath),
      shipInfo: (repoPath: string) => bridgeCall<HermesReviewShipInfo>('hermes:git:review:shipInfo', repoPath),
      prList: (repoPath: string, branches: string[], numbers?: number[]) =>
        bridgeCall<HermesRepoPullRequests>('hermes:git:review:prList', repoPath, branches, numbers),
      createPr: (repoPath: string) => bridgeCall<{ url: string }>('hermes:git:review:createPr', repoPath)
    }
  },

  // ── Terminal (no PTY on Android: stub surface) ─────────────────────────

  terminal: {
    attach: (id: string) => Promise.resolve(false),
    cwd: (id: string) => Promise.resolve(null),
    dispose: (id: string) => Promise.resolve(false),
    resize: (id: string, size: { cols: number; rows: number }) => Promise.resolve(false),
    start: (options?: { cols?: number; cwd?: string; rows?: number }): Promise<HermesTerminalSession> =>
      Promise.resolve({ cwd: options?.cwd ?? '', id: '', shell: 'android-unavailable' }),
    write: (id: string, data: string) => Promise.resolve(false),
    onData: (id: string, callback: (payload: string) => void) => {
      // No PTY: report unavailability once, then never again.
      callback('\u001b[33mTerminal sessions are not available on Android.\u001b[0m\r\n')

      return () => {}
    },
    onExit: (id: string, callback: (payload: HermesTerminalExit) => void) => {
      callback({ code: null, signal: 'android-unavailable' })

      return () => {}
    }
  },

  // ── Preview / deep links / plugins ─────────────────────────────────────

  onClosePreviewRequested: sub('hermes:close-preview-requested'),

  onPreviewNav: sub('hermes:preview-nav'),

  onOpenFolderRequested: sub('hermes:open-folder-requested'),

  onOpenUpdatesRequested: sub('hermes:open-updates'),

  onDeepLink: sub('hermes:deep-link'),

  signalDeepLinkReady: () => bridgeCall<{ ok: boolean }>('hermes:deep-link-ready'),

  probePluginRepo: (payload: { identifier?: string; repo?: string }) => bridgeCall<unknown>('hermes:plugin:probe', payload),

  installDesktopPlugin: (payload: { identifier?: string; repo?: string; force?: boolean }) =>
    bridgeCall<{ ok: boolean; pluginName?: string; path?: string; error?: string }>('hermes:plugin:installDesktop', payload),

  removeDesktopPlugin: (payload: { name: string }) =>
    bridgeCall<{ ok: boolean; path?: string; error?: string }>('hermes:plugin:removeDesktop', payload),

  onWindowStateChanged: sub('hermes:window-state-changed'),

  onFocusSession: sub('hermes:focus-session'),

  onNotificationAction: sub('hermes:notification-action'),

  onNotificationActivate: sub('hermes:notification-activate'),

  onPreviewFileChanged: sub('hermes:preview-file-changed'),

  onBackendExit: sub('hermes:backend-exit'),

  onPoolBackendRetiring: sub('hermes:pool:retiring'),

  onConnectionApplied: sub('hermes:connection:applied'),

  onPowerResume: sub('hermes:power-resume'),

  getOnBattery: () => bridgeCall<boolean>('hermes:power-battery:get'),

  onBatteryChanged: sub('hermes:power-battery'),

  // ── Version / machine / uninstall / updates ────────────────────────────

  getVersion: (scope?: { connectionId?: string; profile?: string }) =>
    bridgeCall<DesktopVersionInfo>('hermes:version', scope),

  relaunchApp: () => bridgeCall<void>('hermes:app:relaunch'),

  getMachineProfile: () => bridgeCall<MachineProfile>('hermes:machine:profile'),

  getRemoteDisplayReason: () => bridgeCall<string | null>('hermes:get-remote-display-reason'),

  getSyncStatus: () => bridgeCall<DesktopSyncReceipt | null>('hermes:sync-status'),

  uninstall: {
    summary: () => bridgeCall<unknown>('hermes:uninstall:summary'),
    run: (mode: 'full' | 'gui' | 'lite') => bridgeCall<unknown>('hermes:uninstall:run', { mode }),
    openAppsSettings: () => bridgeCall<void>('hermes:uninstall:openAppsSettings')
  },

  updates: {
    check: (opts?: { force?: boolean }) => bridgeCall<DesktopUpdateStatus>('hermes:updates:check', opts),
    apply: (opts?: { dirtyStrategy?: 'abort' | 'stash' | 'force' }) => bridgeCall<unknown>('hermes:updates:apply', opts),
    getBranch: () => bridgeCall<{ branch: string }>('hermes:updates:branch:get'),
    setBranch: (name: string) => bridgeCall<{ branch: string }>('hermes:updates:branch:set', name),
    onProgress: sub('hermes:updates:progress'),
    takePendingRun: () => bridgeCall<UpdateRunReport | null>('hermes:updates:metric:take'),
    ackPendingRun: (sent: boolean) => bridgeCall<void>('hermes:updates:metric:ack', sent),
    onPendingRun: sub('hermes:updates:metric:pending')
  },

  desktopMetrics: {
    setEnabled: (on: boolean, profile: string) => bridgeCall<void>('hermes:desktop-metrics:set-enabled', on, profile),
    takeRendererCrashes: () => bridgeCall<unknown>('hermes:desktop-metrics:crash:take'),
    ackRendererCrashes: (sent: boolean) => bridgeCall<void>('hermes:desktop-metrics:crash:ack', sent)
  },

  themes: {
    fetchMarketplace: (id: string) => bridgeCall<DesktopMarketplaceThemeResult>('hermes:vscode-theme:fetch', id),
    searchMarketplace: (query: string) => bridgeCall<DesktopMarketplaceSearchItem[]>('hermes:vscode-theme:search', query)
  },

  findInPage: (query: string, options?: { forward?: boolean; findNext?: boolean }) =>
    bridgeCall<{ count: number }>('hermes:find-in-page', query, options),

  stopFindInPage: () => bridgeCall<void>('hermes:stop-find-in-page'),

  onFoundInPage: sub('hermes:found-in-page'),

  onOpenFindBarRequested: sub('hermes:open-find-bar')
}

// Merge the async facts into the static fields once they arrive, so
// `window.hermesDesktop.glassSupported` etc. reflect reality after boot.
void factsPromise.then(facts => {
  desktop.glassSupported = facts.glassSupported
  desktop.translucencySupported = facts.translucencySupported
})

// Install the bridge. Guarded so HMR / double-mount doesn't clobber a live one.
if (typeof window !== 'undefined' && !(window as unknown as { hermesDesktop?: unknown }).hermesDesktop) {
  ;(window as unknown as { hermesDesktop: typeof desktop }).hermesDesktop = desktop
}

export default desktop

interface DesktopPluginProfileRoute {
  connectionId: string
  mode: 'local' | 'remote'
  primary?: true
  profile: string
  targetProfile: string
}
