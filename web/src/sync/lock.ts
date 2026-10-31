// Mutual exclusion by name: across tabs when the Web Locks API exists, otherwise within this page.
/** Runs `f` exclusively (across tabs when the Web Locks API exists), so the rotating refresh token is never used twice. */
export async function exclusive<T>(name: string, f: () => Promise<T>): Promise<T> {
  const locks = (globalThis as any).navigator?.locks;
  if (locks?.request) return locks.request(name, f);
  const prev = chains.get(name) ?? Promise.resolve();
  const next = prev.then(f, f);
  chains.set(name, next.catch(() => undefined));
  return next;
}
const chains = new Map<string, Promise<unknown>>();
