/**
 * machine-profile shim — type-only mirror of
 * apps/desktop/electron/machine-profile.ts.
 *
 * The desktop registers an `hermes:machine:profile` ipcMain handler fed by
 * Node's os module; on Android the Kotlin bridge answers the same channel
 * with device facts. Only the interface crosses the bridge.
 */
export interface MachineProfile {
  ageDays: number | null
  arch: string
  locale: string
  model: string
  nvidia: boolean
  platform: NodeJS.Platform
  release: string
  username: string
}
