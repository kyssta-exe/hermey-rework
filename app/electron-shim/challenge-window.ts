/**
 * challenge-window shim — type-only mirror of
 * apps/desktop/electron/challenge-window.ts.
 *
 * The desktop's challenge flow drives a hidden BrowserWindow through the
 * free-tier portal; on Android the whole flow is stubbed (see the Kotlin
 * HermeyBridgePlugin `hermes:freeTierChallenge:run` handler), and only the
 * outcome type crosses the bridge. Kept in step with the original.
 */
export type ChallengePhase = 'working' | 'interactive' | 'done' | 'failed'

/** The contract's `free_tier.challenge_result` outcomes minus the renderer-only 'unsupported'. */
export type ChallengeOutcome = 'done' | 'failed' | 'closed' | 'timeout' | 'refused' | 'error'
