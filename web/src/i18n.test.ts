import { describe, expect, it } from 'vitest';
import { STRINGS } from './i18n.gen';

describe('generated strings', () => {
  it('has the same keys in both languages', () => {
    expect(Object.keys(STRINGS.de).sort()).toEqual(Object.keys(STRINGS.en).sort());
  });
  it('converts printf placeholders', () => {
    expect(STRINGS.de.sync_counts).toBe('{1} hochgeladen, {2} heruntergeladen, {3} gelöscht');
    expect(STRINGS.de.tagline).toContain('Layout');
  });
});
