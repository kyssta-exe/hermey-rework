import { useEffect } from 'react'

/**
 * Mobile touch gestures for the shell — the phone equivalents of the
 * desktop's keyboard/hover affordances.
 *
 * The desktop app toggles sidebars with hotkeys and dismisses revealed panes
 * with a backdrop click or Escape. On a phone those are awkward, so we add
 * the standard touch gestures. Every gesture is TOUCH-ONLY (pointer: coarse)
 * and a pure addition — desktop rendering and interaction are untouched.
 *
 * Gestures:
 *  - Swipe right from the left edge  → open the left sidebar drawer
 *  - Swipe left                      → close the left sidebar drawer
 *  - Swipe left from the right edge  → open the right sidebar / file rail
 *  - Swipe right (from anywhere)     → close the right sidebar
 *  - Swipe left while an overlay is open → dismiss it (swipe-to-close)
 */

const EDGE_ZONE_PX = 28
const THRESHOLD_PX = 60
const MAX_VERTICAL_DRIFT_PX = 40

/** True on touch-primary devices (phones/tablets); false on desktop. */
function isTouchPrimary(): boolean {
  return (
    typeof window !== 'undefined' &&
    window.matchMedia?.('(pointer: coarse)').matches === true
  )
}

interface SwipeHandlers {
  /** Swipe right from the LEFT edge opens the left sidebar. */
  onOpenLeft?: () => void
  /** Swipe left (from anywhere) closes the left sidebar. */
  onCloseLeft?: () => void
  /** Swipe left from the RIGHT edge opens the right sidebar / file rail. */
  onOpenRight?: () => void
  /** Swipe right (from anywhere) closes the right sidebar. */
  onCloseRight?: () => void
  /** When true, a swipe-left anywhere dismisses the open overlay. */
  overlayOpen?: boolean
  onDismissOverlay?: () => void
}

/**
 * Wire edge-swipe gestures for the shell. Mount once inside the app shell.
 * All handlers are optional; only the ones provided engage.
 */
export function useShellSwipeGestures(handlers: SwipeHandlers): void {
  const {
    onOpenLeft,
    onCloseLeft,
    onOpenRight,
    onCloseRight,
    overlayOpen = false,
    onDismissOverlay
  } = handlers

  useEffect(() => {
    if (!isTouchPrimary()) return

    let startX = 0
    let startY = 0
    let fromLeftEdge = false
    let fromRightEdge = false
    let tracking = false

    const vw = (): number => window.innerWidth || document.documentElement.clientWidth

    // Ignore gestures that begin inside a horizontally-scrollable region
    // (kanban board, tab strips, wide tables/code) or an open overlay's own
    // content — those own the horizontal axis and a swipe there is a scroll,
    // not a shell gesture.
    const startsInScrollableOrOverlay = (target: EventTarget | null): boolean => {
      if (!(target instanceof Element)) return false
      return Boolean(
        target.closest(
          '[data-narrow-overlay], [data-pane-overlay], [data-swipe-ignore], ' +
            '.overflow-x-auto, .overflow-x-scroll, [data-radix-scroll-area-viewport]'
        )
      )
    }

    const onTouchStart = (e: TouchEvent): void => {
      const t = e.touches[0]
      if (!t) return
      startX = t.clientX
      startY = t.clientY
      fromLeftEdge = startX <= EDGE_ZONE_PX
      fromRightEdge = startX >= vw() - EDGE_ZONE_PX
      // Edge swipes (open) always track; a close-from-anywhere swipe must not
      // originate inside a scroller/overlay.
      tracking = fromLeftEdge || fromRightEdge || !startsInScrollableOrOverlay(e.target)
    }

    const onTouchEnd = (e: TouchEvent): void => {
      if (!tracking) return
      tracking = false
      const t = e.changedTouches[0]
      if (!t) return
      const dx = t.clientX - startX
      const dy = Math.abs(t.clientY - startY)
      // Must be a horizontal swipe, not a vertical scroll.
      if (dy > MAX_VERTICAL_DRIFT_PX) return

      // Overlay-dismiss wins when an overlay is open.
      if (overlayOpen && onDismissOverlay && dx < -THRESHOLD_PX) {
        onDismissOverlay()
        return
      }
      // Open a sidebar only when the swipe STARTS at its edge.
      if (fromLeftEdge && dx > THRESHOLD_PX) {
        onOpenLeft?.()
      } else if (!fromRightEdge && dx < -THRESHOLD_PX) {
        onCloseLeft?.()
      }
      if (fromRightEdge && dx < -THRESHOLD_PX) {
        onOpenRight?.()
      } else if (!fromLeftEdge && dx > THRESHOLD_PX) {
        onCloseRight?.()
      }
    }

    window.addEventListener('touchstart', onTouchStart, { passive: true })
    window.addEventListener('touchend', onTouchEnd, { passive: true })
    return () => {
      window.removeEventListener('touchstart', onTouchStart)
      window.removeEventListener('touchend', onTouchEnd)
    }
  }, [onOpenLeft, onCloseLeft, onOpenRight, onCloseRight, overlayOpen, onDismissOverlay])
}
