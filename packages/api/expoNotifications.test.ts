import { describe, expect, it, vi } from 'vitest';
import { enableExpoPush } from '../mobile/expoNotifications';

function fixture(status = 'granted') {
  return {
    getPermissionsAsync: vi.fn(async () => ({ status })),
    requestPermissionsAsync: vi.fn(async () => ({ status })),
    getExpoPushTokenAsync: vi.fn(async () => ({ data: 'ExpoPushToken[test-device-token]' })),
    setNotificationChannelAsync: vi.fn(async () => undefined),
    addPushTokenListener: vi.fn(() => ({ remove: vi.fn() })),
    addNotificationResponseReceivedListener: vi.fn(() => ({ remove: vi.fn() })),
  };
}
describe('native Expo integration', () => {
  it('does not register when permission is denied', async () => {
    const backend = vi.fn(async () => undefined);
    await expect(
      enableExpoPush({
        notifications: fixture('denied'),
        backend,
        projectId: 'project',
        deviceId: 'device',
        platform: 'ios',
        onOpen: vi.fn(),
        onError: vi.fn(),
      }),
    ).rejects.toThrow('chưa cho phép');
    expect(backend).not.toHaveBeenCalled();
  });
  it('registers the Expo token against the session and removes it before logout', async () => {
    const notifications = fixture();
    const backend = vi.fn(async () => undefined);
    const result = await enableExpoPush({
      notifications,
      backend,
      projectId: 'project',
      deviceId: 'device',
      platform: 'android',
      onOpen: vi.fn(),
      onError: vi.fn(),
    });
    expect(notifications.setNotificationChannelAsync).toHaveBeenCalledWith(
      'default',
      expect.anything(),
    );
    expect(backend).toHaveBeenCalledWith('/notifications/devices', {
      method: 'POST',
      body: JSON.stringify({ deviceId: 'device', expoToken: 'ExpoPushToken[test-device-token]' }),
    });
    await result.unregister();
    expect(backend).toHaveBeenLastCalledWith('/notifications/devices/device', { method: 'DELETE' });
    expect(notifications.addPushTokenListener.mock.results[0].value.remove).toHaveBeenCalled();
  });
});
