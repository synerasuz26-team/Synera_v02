export const apiBaseUrl = (
  import.meta.env.VITE_API_URL ||
  import.meta.env.VITE_API_BASE_URL ||
  'http://127.0.0.1:8080'
)
  .replace(/\/$/, '')
  .replace(/\/api\/v1$/, '');

export type Session = {
  id: string;
  fullName: string;
  email: string;
  accessToken: string;
  refreshToken: string;
  tokenType: 'Bearer';
  expiresIn: number;
  deviceId: string;
};
export type CurrentUser = {
  user: Pick<Session, 'id' | 'fullName' | 'email'>;
  tenant: { id: string; type: string; code: string; name: string };
  roles: { code: string; name: string; assignmentLevel: number }[];
  permissions: { code: string; scope: string }[];
};
export class ApiError extends Error {
  constructor(
    public status: number,
    public code: string,
    message: string,
    public details?: unknown,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}
let session: Session | null = null;
let generation = 0;
let refreshFlight: Promise<Session> | null = null;
const listeners = new Set<() => void>();
export const getAccessToken = () => session?.accessToken ?? null;
export const hasSession = () => session !== null;
export function subscribeSession(listener: () => void) {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}
function setSession(value: Session | null) {
  session = value;
  generation++;
  listeners.forEach((listener) => listener());
}
async function readError(response: Response) {
  const parsed: unknown = await response.json().catch(() => ({}));
  const body = parsed && typeof parsed === 'object' ? (parsed as Record<string, unknown>) : {};
  const fallback =
    response.status === 403
      ? 'Bạn không có quyền thực hiện thao tác này.'
      : response.status === 401
        ? 'Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.'
        : response.status === 409
          ? 'Dữ liệu đã thay đổi. Vui lòng tải lại trước khi thao tác.'
          : 'Không thể xử lý yêu cầu. Vui lòng thử lại.';
  return new ApiError(
    response.status,
    typeof body.code === 'string' ? body.code : 'API_ERROR',
    typeof body.message === 'string' ? body.message : fallback,
    body.details ?? body.fieldErrors,
  );
}
async function transport(path: string, init?: RequestInit) {
  try {
    return await fetch(`${apiBaseUrl}/api/v1${path}`, init);
  } catch (error) {
    if (error instanceof Error && error.name === 'AbortError') throw error;
    throw new ApiError(0, 'NETWORK_ERROR', 'Không thể kết nối máy chủ. Kiểm tra mạng và thử lại.');
  }
}
async function authRequest(path: string, payload: object) {
  const response = await transport(`/auth/${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });
  if (!response.ok) throw await readError(response);
  return (await response.json()) as Session;
}
export async function loginAccount(email: string, password: string) {
  const attempt = generation;
  const result = await authRequest('login', { email, password });
  if (attempt !== generation)
    throw new ApiError(401, 'SESSION_CHANGED', 'Phiên đăng nhập đã thay đổi.');
  setSession(result);
  return result;
}
export function refreshAccountSession(): Promise<Session> {
  if (refreshFlight) return refreshFlight;
  if (!session)
    return Promise.reject(new ApiError(401, 'NO_SESSION', 'Vui lòng đăng nhập để tiếp tục.'));
  const previous = session;
  const epoch = generation;
  refreshFlight = authRequest('refresh', {
    refreshToken: previous.refreshToken,
    deviceId: previous.deviceId,
  })
    .then((result) => {
      if (epoch !== generation)
        throw new ApiError(401, 'SESSION_CHANGED', 'Phiên đăng nhập đã thay đổi.');
      session = result;
      return result;
    })
    .catch((error) => {
      if (
        epoch === generation &&
        error instanceof ApiError &&
        [400, 401, 403].includes(error.status)
      )
        setSession(null);
      throw error;
    })
    .finally(() => {
      refreshFlight = null;
    });
  return refreshFlight;
}
export async function apiRequest<T>(
  path: string,
  init: RequestInit = {},
  responseType: 'json' | 'blob' = 'json',
): Promise<T> {
  if (!path.startsWith('/') || path.startsWith('//'))
    throw new Error('Đường dẫn API không hợp lệ.');
  const epoch = generation;
  const token = getAccessToken();
  if (!token) throw new ApiError(401, 'NO_SESSION', 'Vui lòng đăng nhập để tiếp tục.');
  const send = () => {
    const headers = new Headers(init.headers);
    headers.set('Authorization', `Bearer ${getAccessToken()}`);
    if (init.body && !(init.body instanceof FormData))
      headers.set('Content-Type', 'application/json');
    return transport(path, { ...init, headers });
  };
  let response = await send();
  if (response.status === 401 && epoch === generation) {
    if (getAccessToken() === token) await refreshAccountSession();
    init.signal?.throwIfAborted();
    if (epoch !== generation)
      throw new ApiError(401, 'SESSION_CHANGED', 'Phiên đăng nhập đã thay đổi.');
    response = await send();
  }
  if (epoch !== generation)
    throw new ApiError(401, 'SESSION_CHANGED', 'Phiên đăng nhập đã thay đổi.');
  if (!response.ok) {
    if (response.status === 401) setSession(null);
    throw await readError(response);
  }
  if (response.status === 204) return undefined as T;
  if (responseType === 'blob') return (await response.blob()) as T;
  return (await response.json()) as T;
}
export const apiDownload = (path: string, signal?: AbortSignal) =>
  apiRequest<Blob>(path, { signal }, 'blob');
export const getCurrentUser = (signal?: AbortSignal) =>
  apiRequest<CurrentUser>('/auth/me', { signal });
export async function logoutAccount() {
  const previous = session;
  setSession(null);
  if (!previous) return;
  const response = await transport('/auth/logout', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ refreshToken: previous.refreshToken, deviceId: previous.deviceId }),
  });
  if (!response.ok) throw await readError(response);
}
export const can = (me: CurrentUser, permission: string) =>
  me.permissions.some(
    (item) => item.code === permission && item.scope !== 'N' && item.scope !== '-',
  );
export function homeFor(me: CurrentUser) {
  if (me.tenant.type === 'NPP') return '/npp';
  if (me.tenant.type === 'OUTLET') return '/outlet';
  if (can(me, 'visit:checkin') && !can(me, 'order_si:view')) return '/sales';
  return '/nsx';
}
