import { afterEach, describe, expect, it, vi } from 'vitest';
import { loginAccount, registerAccount } from './authApi';

const authResponse = {
  id: '550e8400-e29b-41d4-a716-446655440000',
  fullName: 'Example User',
  email: 'person@example.com',
};

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('auth API', () => {
  it('sends registration through the CSRF-protected session API', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(Response.json({ token: 'csrf-token' }))
      .mockResolvedValueOnce(Response.json(authResponse, { status: 201 }));
    vi.stubGlobal('fetch', fetchMock);

    await registerAccount('Example User', 'person@example.com', 'secure-password');

    expect(fetchMock).toHaveBeenNthCalledWith(
      1,
      'http://127.0.0.1:8080/api/auth/csrf',
      expect.objectContaining({ credentials: 'include' }),
    );
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      'http://127.0.0.1:8080/api/auth/register',
      expect.objectContaining({
        method: 'POST',
        credentials: 'include',
        headers: {
          'Content-Type': 'application/json',
          'X-XSRF-TOKEN': 'csrf-token',
        },
        body: JSON.stringify({
          fullName: 'Example User',
          email: 'person@example.com',
          password: 'secure-password',
        }),
      }),
    );
  });

  it('shows the API error message when login is rejected', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(Response.json({ token: 'csrf-token' }))
      .mockResolvedValueOnce(
        Response.json(
          { code: 'INVALID_CREDENTIALS', message: 'Email hoặc mật khẩu không đúng.' },
          {
            status: 401,
          },
        ),
      );
    vi.stubGlobal('fetch', fetchMock);

    await expect(loginAccount('person@example.com', 'wrong-password')).rejects.toThrow(
      'Email hoặc mật khẩu không đúng.',
    );
  });
});
