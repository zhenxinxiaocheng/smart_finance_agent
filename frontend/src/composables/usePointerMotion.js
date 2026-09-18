import { onBeforeUnmount, ref } from 'vue'

/**
 * Pointer-driven motion for the auth pages.
 *
 * Writes plain CSS custom properties onto the elements instead of using Vue
 * reactive state, so a mousemove does not trigger a component re-render. All
 * writes are coalesced into one requestAnimationFrame tick.
 *
 * Properties are written onto BOTH the brand panel and the page, so the
 * page-level backdrop (grid + blooms, which live outside the panel) inherits
 * them too. Values are normalised against the brand panel's box, so the feel
 * does not change just because the backdrop moved to the page.
 *
 *   --nx / --ny  cursor position, -1 .. 1 from the panel centre
 *   --rx / --ry  card tilt in deg
 *
 * No cursor-tracking light spot is emitted. Both the panel spotlight and the
 * card sheen were tried and removed — on a light theme a low-alpha blob that
 * chases the pointer reads as a smudge over the content, not as lighting.
 */

function prefersReducedMotion() {
  return (
    typeof window !== 'undefined' &&
    window.matchMedia('(prefers-reduced-motion: reduce)').matches
  )
}

function resolveElement(value) {
  if (!value) return null
  // A `ref` on a Vue component yields the instance; the root node is $el.
  return value.$el ?? value
}

export function usePointerMotion({ tiltDegrees = 5 } = {}) {
  // The brand panel: where the pointer is tracked, and the box that parallax
  // distances are measured against.
  const heroRef = ref(null)
  // The page: the custom properties land here so the backdrop inherits them.
  const surfaceRef = ref(null)
  const cardRef = ref(null)

  let frame = 0
  let queued = null

  function schedule(task) {
    queued = task
    if (frame) return
    frame = requestAnimationFrame(() => {
      frame = 0
      const run = queued
      queued = null
      if (run) run()
    })
  }

  // Writing to both keeps the composable working even if only one ref is bound;
  // the duplicate write on a descendant is harmless.
  function writeVars(values) {
    const targets = [resolveElement(surfaceRef.value), resolveElement(heroRef.value)].filter(Boolean)
    if (!targets.length) return
    schedule(() => {
      for (const el of targets) {
        for (const name in values) el.style.setProperty(name, values[name])
      }
    })
  }

  function handleHeroMove(event) {
    if (prefersReducedMotion()) return
    const el = resolveElement(heroRef.value)
    if (!el) return

    const rect = el.getBoundingClientRect()
    if (!rect.width || !rect.height) return

    const nx = ((event.clientX - rect.left) / rect.width) * 2 - 1
    const ny = ((event.clientY - rect.top) / rect.height) * 2 - 1

    writeVars({ '--nx': nx.toFixed(4), '--ny': ny.toFixed(4) })
  }

  function handleHeroLeave() {
    writeVars({ '--nx': '0', '--ny': '0' })
  }

  function handleCardMove(event) {
    if (prefersReducedMotion()) return
    const el = resolveElement(cardRef.value)
    if (!el) return

    const rect = el.getBoundingClientRect()
    if (!rect.width || !rect.height) return

    const x = (event.clientX - rect.left) / rect.width
    const y = (event.clientY - rect.top) / rect.height

    schedule(() => {
      el.style.setProperty('--rx', `${((0.5 - y) * tiltDegrees).toFixed(2)}deg`)
      el.style.setProperty('--ry', `${((x - 0.5) * tiltDegrees).toFixed(2)}deg`)
    })
  }

  function handleCardLeave() {
    const el = resolveElement(cardRef.value)
    if (!el) return
    schedule(() => {
      el.style.setProperty('--rx', '0deg')
      el.style.setProperty('--ry', '0deg')
    })
  }

  onBeforeUnmount(() => {
    if (frame) cancelAnimationFrame(frame)
  })

  return {
    heroRef,
    surfaceRef,
    cardRef,
    handleHeroMove,
    handleHeroLeave,
    handleCardMove,
    handleCardLeave
  }
}
