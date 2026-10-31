import { t } from './i18n';
import { RecoveryKeyError } from './sync/account';
import { ApiException, NetworkError } from './sync/api';
import { CryptoError } from './sync/crypto';

export type Page = 'signin' | 'register' | 'recover';

export function errorText(e: unknown, page?: Page): string {
  if (e instanceof ApiException) {
    if (e.code === 'invalid_credentials') return page === 'recover' ? t('acc_err_recovery') : t('acc_err_credentials');
    if (e.code === 'email_taken') return t('acc_err_email_taken');
    if (e.code === 'invalid_request' && /email/.test(e.message)) return t('acc_err_email');
    if (e.code === 'rate_limited') return e.message;
    return e.message;
  }
  if (e instanceof CryptoError || e instanceof RecoveryKeyError) return t('acc_err_recovery');
  if (e instanceof NetworkError) return t('acc_err_network');
  return (e as Error)?.message || String(e);
}
