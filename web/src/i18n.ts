import { STRINGS } from './i18n.gen';

export type Lang = 'de' | 'en';
const stored = typeof localStorage !== 'undefined' ? (localStorage.getItem('lang') as Lang | null) : null;
export const lang: Lang = stored || (typeof navigator !== 'undefined' && navigator.language?.toLowerCase().startsWith('de') ? 'de' : 'en');
if (typeof document !== 'undefined') document.documentElement.lang = lang;

/** Text lookup with `{1}`, `{2}` placeholders (Android `%1$s` style). Unknown keys return the key. */
export function t(key: string, ...args: (string | number)[]): string {
  const s = STRINGS[lang][key] ?? key;
  return s.replace(/\{(\d)\}/g, (_, i) => String(args[Number(i) - 1] ?? ''));
}
