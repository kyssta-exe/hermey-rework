import { useEffect } from 'react'

/**
 * Mobile-only shell gestures — the touch equivalents of the desktop's
 * keyboard-driven sidebar toggle.
 *
 * The desktop app toggles the sidebar with a hotkey / trigger button. On a
 * phone there is no hover affordance and the docked trigger sits under the
 * keyboard, so we add the standard mobile gesture: swipe right from the left
 * edge to open the sidebar drawer, swipe left to close it.
 *
 * This is a pure ADDITION for touch input. On a desktop (no touch, or a wide
 * viewport) none of it engages, so the 1:1 desktop behavior is untouched.
 *
 * @param onOpen  open the mobile sidebar Sheet
 * @param onClose close it
 */
export function useMobileSidebarGestures(onOpen: () => void, onClose: () => void): void {
  useEffect(() => {
    // Only wire gestures for touch-primary devices (phones/tablets).
    const isTouchPrimary =
      typeof window !== 'undefined' &&
      window.matchMedia?.('(pointer: coarse)').matches === true

    if (!isTouchPrimary) return

    let startX = 0
    let startY = 0
    let tracking = false

    const EDGE_ZONE_PX = 28
    const THRESHOLD_PX = 60
    const MAX_VERTICAL_DRIFT_PX = 40

    const onTouchStart = (e: TouchEvent): void => {
      const t = e.touches[0]
      if (!t) return
      startX = t.clientX
      startY = t.clientY
      // A gesture that starts near the left edge opens the drawer.
      tracking = startX <= EDGE_ZONE_PX
    }

    const onTouchEnd = (e: TouchEvent): void => {
      if (!tracking) return
      tracking = false
      const t = e.changedTouches[0]
      if (!t) return
      const dx = t.clientX - startX
      const dy = Math.abs(t.clientY - startY)
      // Horizontal swipe, not a vertical scroll.
      if (dy > MAX_VERTICAL_DRIFT_PX) return
      if (dx > THRESHOLD_PX) onOpen()
      else if (dx < -THRESHOLD_PX) onClose()
    }

    window.addEventListener('touchstart', onTouchStart, { passive: true })
    window.addEventListener('touchend', onTouchEnd, { passive: true })
    return () => {
      window.removeEventListener('touchstart', onTouchStart)
      window.removeEventListener('touchend', onTouchEnd)
    }
  }, [onOpen, onClose])
}
