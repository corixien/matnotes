// HTTP client for the sync server (fetch). Binary document bodies are already encrypted.

/** An error answer of the server (4xx/5xx). `serverVersion` is set for 409 conflicts on documents. */
export class ApiException extends Error {
  constructor(readonly status: number, readonly code: string, message: string, readonly serverVersion: number | null = null, readonly deleted = false) { super(message); }
}
/** The server could not be reached at all (offline, DNS, CORS). */
export class NetworkError extends Error {}

export type ApiResponse = { status: number; body: Uint8Array<ArrayBuffer>; headers: Headers };
type Json = Record<string, any>;

export class SyncApi {
  constructor(public baseUrl: string = (import.meta as any).env?.VITE_SYNC_URL || '/api') {}

  private async open(method: string, path: string, token: string | null, headers: Record<string, string>, contentType: string, body?: BodyInit | null): Promise<ApiResponse> {
    const h: Record<string, string> = { Accept: 'application/json, application/octet-stream', ...headers };
    if (token) h.Authorization = `Bearer ${token}`;
    if (body != null) h['Content-Type'] = contentType;
    let r: Response;
    try {
      r = await fetch(this.baseUrl + path, { method, headers: h, body: body ?? undefined, cache: 'no-store', credentials: 'omit' });
    } catch (e) { throw new NetworkError((e as Error).message || 'network'); }
    const out = { status: r.status, body: new Uint8Array(await r.arrayBuffer()) as Uint8Array<ArrayBuffer>, headers: r.headers };
    if (r.status >= 200 && r.status < 400) return out;
    let j: Json | null = null;
    try { j = JSON.parse(new TextDecoder().decode(out.body)); } catch { /* not json */ }
    throw new ApiException(r.status, j?.error || `http_${r.status}`, j?.message || `HTTP ${r.status}`, j?.serverVersion ?? null, !!j?.deleted);
  }

  /** JSON request. `token` is the access token; callers that need automatic refresh go through Account.call. */
  async json(method: string, path: string, body?: Json | null, token: string | null = null, headers: Record<string, string> = {}): Promise<Json> {
    const r = await this.open(method, path, token, headers, 'application/json', body ? JSON.stringify(body) : null);
    return r.body.length ? JSON.parse(new TextDecoder().decode(r.body)) : {};
  }

  bytes(method: string, path: string, token: string, headers: Record<string, string> = {}, body?: Uint8Array | null): Promise<ApiResponse> {
    return this.open(method, path, token, headers, 'application/octet-stream', body as BodyInit | null | undefined);
  }
}
