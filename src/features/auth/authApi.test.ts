import { afterEach, describe, expect, it, vi } from 'vitest';
import { getAccessToken, loginAccount } from './authApi';

const authResponse = {
  id: '550e8400-e29b-41d4-a716-446655440000',
  fullName: 'Example User',
  email: 'person@example.com',
  accessToken: 'access-token',
  refreshToken: 'refresh-token',
  tokenType: 'Bearer',
  expiresIn: 900,
  deviceId: 'device-id',
};

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('auth API', () => {
  it('sends login to the versioned stateless API and retains the access token', async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(Response.json(authResponse));
    vi.stubGlobal('fetch', fetchMock);

    await loginAccount('person@example.com', 'secure-password');

    expect(fetchMock).toHaveBeenNthCalledWith(
      1,
      'http://127.0.0.1:8080/api/v1/auth/login',
      expect.objectContaining({
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({ email: 'person@example.com', password: 'secure-password' }),
      }),
    );
    expect(getAccessToken()).toBe('access-token');
  });

  it('shows the API error message when login is rejected', async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(
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
