// The engine draws into a canvas with devicePixelRatio times as many pixels as the screen is wide: a phone with ratio 3 pays for 9x
// the pixels of ratio 1, which is what makes scrolling and typing laggy. Cap the ratio the engine sees (`?dpr=1` or localStorage "dpr"
// overrides; text gets a little softer, the speed-up is large).
export function capPixelRatio(): number {
  const real = window.devicePixelRatio || 1;
  const wanted = parseFloat(new URLSearchParams(location.search).get('dpr') || localStorage.getItem('dpr') || '');
  const phone = matchMedia('(pointer: coarse)').matches || innerWidth < 900;
  const cap = Number.isFinite(wanted) && wanted > 0 ? wanted : phone ? 1.25 : 1.5;
  const used = Math.min(real, cap);
  if (used !== real) Object.defineProperty(window, 'devicePixelRatio', { get: () => used, configurable: true });
  return used;
}
