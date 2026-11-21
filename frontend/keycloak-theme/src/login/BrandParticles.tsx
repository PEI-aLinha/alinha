import { Particles, ParticlesProvider } from '@tsparticles/react'
import type { Engine, ISourceOptions } from '@tsparticles/engine'
import { loadSlim } from '@tsparticles/slim'

const initEngine = (engine: Engine) => loadSlim(engine)

/** White in both themes: the brand panel keeps the light palette in dark mode (see `.brand-panel` in index.css). */
const COLOR = '#ffffff'

// Slow drifting network of points and lines (prototype OnboardingParticles).
const OPTIONS: ISourceOptions = {
  fullScreen: { enable: false },
  background: { color: 'transparent' },
  fpsLimit: 60,
  detectRetina: true,
  particles: {
    number: { value: 55 },
    color: { value: COLOR },
    opacity: { value: 0.4 },
    size: { value: { min: 1.5, max: 2.5 } },
    links: { enable: true, color: COLOR, opacity: 0.16, distance: 150, width: 1 },
    move: { enable: true, speed: 0.35, outModes: { default: 'bounce' } },
  },
  interactivity: {
    events: { onHover: { enable: true, mode: 'grab' } },
    modes: { grab: { distance: 160, links: { opacity: 0.35 } } },
  },
}

/** Animated background of the brand panel, loaded lazily by the Template. */
export default function BrandParticles() {
  return (
    <ParticlesProvider init={initEngine}>
      <Particles id="brand-particles" options={OPTIONS} className="absolute inset-0" />
    </ParticlesProvider>
  )
}
