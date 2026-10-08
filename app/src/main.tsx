/**
 * Hermey Android entry point — the twin of apps/desktop/src/main.tsx.
 *
 * Identical mount sequence to the desktop renderer (same providers, same
 * order, same side-effect imports) with ONE addition: the Android bridge
 * install (`window.hermesDesktop`) happens first, so every store and hook
 * that touches it at import time sees a complete bridge.
 *
 * Everything else is byte-identical to the desktop main: same providers,
 * same HashRouter config (useTransitions={false} for the documented
 * starvation fix), same clipboard shim, same animation-pause installer.
 */
import './styles.css'
// The Android bridge MUST be installed before anything that reads
// `window.hermesDesktop` at module scope (stores do — e.g. gateway boot).
import './bridge/desktop'

// Side-effect: reports in-flight turns (quit guard on desktop; harmless here).
import './store/active-work'
// Side-effect: mirrors the machine's AC/battery state.
import './store/power'
// Side-effect: applies persisted window translucency (no-op on Android).
import './store/translucency'
// Side-effect: applies the persisted user-bubble transparency on load.
import './store/user-bubble-transparency'
// Side-effect: restores chat typography before the first paint.
import './store/chat-text-scale'

import { QueryClientProvider } from '@tanstack/react-query'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { HashRouter } from 'react-router'

import App from './app'
import { RootErrorBoundary } from './components/error-boundary'
import { HapticsProvider } from './components/haptics-provider'
import { RootTooltipProvider } from './components/ui/tooltip'
import { ProfileI18nProvider as I18nProvider } from './i18n/profile-provider'
import { installClipboardShim } from './lib/clipboard'
import { queryClient } from './lib/query-client'
import { installRendererAnimationPauseState } from './lib/renderer-loop-pause'
import { installSelectionCopyColorGuard } from './lib/selection-copy-colors'
import { ThemeProvider } from './themes/context'

installClipboardShim()
// Chromium serializes selection copies with the theme's computed colors
// inlined; without this guard a dark-theme selection pastes near-white
// into light-background targets. Same rule as desktop.
installSelectionCopyColorGuard()

// CSS animations do not inherit Chromium's JS-loop pause policy — mirror
// the window's visibility state to :root so decorative infinite
// animations stop producing frames when nobody can see them (Android
// lifecycle: backgrounded app = hidden root).
installRendererAnimationPauseState()

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <RootErrorBoundary>
      <QueryClientProvider client={queryClient}>
        <I18nProvider>
          <ThemeProvider>
            <HapticsProvider>
              {/* ONE tooltip provider for the whole app (hoisted, exactly
                  like the desktop: every `Tip` used to carry its own, and
                  with ~107 call sites those subtrees dominated unrelated
                  interactions). Radix's provider holds only refs and stable
                  callbacks, so hoisting is what it's for. */}
              <RootTooltipProvider>
                {/* useTransitions={false}: react-router v8's HashRouter wraps
                    every route state update in React.startTransition() by
                    default. Under load (streaming token deltas, gateway
                    events, store updates) those higher-priority updates keep
                    interrupting the transition, starving the route change
                    commit — the sidebar highlight + main pane freeze for
                    seconds. Disabling transitions makes navigate() commit at
                    default priority. Same setting as desktop. */}
                <HashRouter useTransitions={false}>
                  <App />
                </HashRouter>
              </RootTooltipProvider>
            </HapticsProvider>
          </ThemeProvider>
        </I18nProvider>
      </QueryClientProvider>
    </RootErrorBoundary>
  </StrictMode>
)
