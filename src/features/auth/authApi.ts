const apiBaseUrl = (import.meta.env.VITE_API_BASE_URL || 'http://127.0.0.1:8080').replace(
  /\/$/,
  '',
);

let currentSession: AuthResponse | null = null;

type AuthResponse = {
  id: string;
  fullName: string;
  email: string;
  accessToken: string;
  refreshToken: string;
  tokenType: 'Bearer';
  expiresIn: number;
  deviceId: string;
};

type AuthMeResponse = {
  user: Pick<AuthResponse, 'id' | 'fullName' | 'email'>;
  tenant: { id: string; type: string; code: string; name: string };
  roles: { code: string; name: string; assignmentLevel: number }[];
  permissions: { code: string; scope: string }[];
};

type ApiErrorResponse = {
  message?: unknown;
};

async function getCsrfToken() {
  const response = await fetch(`${apiBaseUrl}/api/v1/auth/csrf`);
  if (!response.ok) {
    throw new Error('Không thể khởi tạo kết nối an toàn với dịch vụ.');
  }

  const body: unknown = await response.json();
  if (
    typeof body !== 'object' ||
    body === null ||
    !('token' in body) ||
    typeof body.token !== 'string'
  ) {
    throw new Error('Phản hồi bảo mật từ máy chủ không hợp lệ.');
  }
  return body.token;
}

async function readErrorMessage(response: Response) {
  try {
    const body: ApiErrorResponse = await response.json();
    if (typeof body.message === 'string' && body.message.length > 0) {
      return body.message;
    }
  } catch {
    return 'Dịch vụ không thể xử lý yêu cầu. Vui lòng thử lại.';
  }
  return 'Dịch vụ không thể xử lý yêu cầu. Vui lòng thử lại.';
}

async function submitAuthRequest(path: string, payload: Record<string, string>) {
  const csrfToken = await getCsrfToken();
  const response = await fetch(`${apiBaseUrl}/api/v1/auth/${path}`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-XSRF-TOKEN': csrfToken,
    },
    body: JSON.stringify(payload),
  });

  if (!response.ok) {
    throw new Error(await readErrorMessage(response));
  }
  currentSession = (await response.json()) as AuthResponse;
  return currentSession;
}

export function loginAccount(email: string, password: string) {
  return submitAuthRequest('login', { email, password });
}

export function getAccessToken() {
  return currentSession?.accessToken ?? null;
}

export async function refreshAccountSession() {
  if (!currentSession) {
    throw new Error('Phiên đăng nhập không còn trong bộ nhớ. Vui lòng đăng nhập lại.');
  }
  return submitAuthRequest('refresh', {
    refreshToken: currentSession.refreshToken,
    deviceId: currentSession.deviceId,
  });
}

export async function getCurrentUser(): Promise<AuthMeResponse> {
  const accessToken = getAccessToken();
  if (!accessToken) {
    throw new Error('Vui lòng đăng nhập để tiếp tục.');
  }
  const response = await fetch(`${apiBaseUrl}/api/v1/auth/me`, {
    headers: { Authorization: `Bearer ${accessToken}` },
  });
  if (!response.ok) {
    throw new Error(await readErrorMessage(response));
  }
  return (await response.json()) as AuthMeResponse;
}

export async function logoutAccount() {
  if (!currentSession) {
    return;
  }
  const { refreshToken, deviceId } = currentSession;
  const response = await fetch(`${apiBaseUrl}/api/v1/auth/logout`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ refreshToken, deviceId }),
  });
  if (!response.ok) {
    throw new Error(await readErrorMessage(response));
  }
  currentSession = null;
}
