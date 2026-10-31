import { describe, expect, it } from 'vitest';
import { plan } from './planner';

describe('SyncPlanner (same table as SyncPlannerTest.kt)', () => {
  it('file exists, cloud alive', () => {
    expect(plan(true, false, false, false)).toBe('NOTHING');
    expect(plan(true, true, false, false)).toBe('PUSH');
    expect(plan(true, false, false, true)).toBe('PULL');
    expect(plan(true, true, false, true)).toBe('CONFLICT');
  });
  it('cloud deleted: edited here, nothing may be lost', () => {
    expect(plan(true, false, true, true)).toBe('DELETE_LOCAL');
    expect(plan(true, false, true, false)).toBe('DELETE_LOCAL');
    expect(plan(true, true, true, true)).toBe('DETACH');
    expect(plan(true, true, true, false)).toBe('DETACH');
  });
  it('deleted here', () => {
    expect(plan(false, false, false, false)).toBe('DELETE_REMOTE');
    expect(plan(false, false, false, true)).toBe('REDOWNLOAD');
    expect(plan(false, false, true, true)).toBe('FORGET');
    expect(plan(false, false, true, false)).toBe('FORGET');
  });
  it('is total: every input has an action, never PUSH/PULL without a local file', () => {
    for (const a of [true, false]) for (const b of [true, false]) for (const c of [true, false]) for (const d of [true, false]) {
      const r = plan(a, b, c, d);
      expect(r).toBeTruthy();
      if (!a) expect(['FORGET', 'DELETE_REMOTE', 'REDOWNLOAD']).toContain(r);
    }
  });
});
