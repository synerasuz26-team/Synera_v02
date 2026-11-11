const apiBaseUrl = (import.meta.env.VITE_API_BASE_URL || 'http://127.0.0.1:8080').replace(
  /\/$/,
  '',
);

type AuthResponse = {
  id: string;
  fullName: string;
  email: string;
};

type ApiErrorResponse = {
  message?: unknown;
};

async function getCsrfToken() {
  const response = await fetch(`${apiBaseUrl}/api/auth/csrf`, {
    credentials: 'include',
  });
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
  const response = await fetch(`${apiBaseUrl}/api/auth/${path}`, {
    method: 'POST',
    credentials: 'include',
    headers: {
      'Content-Type': 'application/json',
      'X-XSRF-TOKEN': csrfToken,
    },
    body: JSON.stringify(payload),
  });

  if (!response.ok) {
    throw new Error(await readErrorMessage(response));
  }
  return (await response.json()) as AuthResponse;
}

export function registerAccount(fullName: string, email: string, password: string) {
  return submitAuthRequest('register', { fullName, email, password });
}

export function loginAccount(email: string, password: string) {
  return submitAuthRequest('login', { email, password });
}
