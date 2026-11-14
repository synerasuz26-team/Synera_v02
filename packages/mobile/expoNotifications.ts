/** Native integration boundary: pass the installed expo-notifications module from the Expo app. */
type Subscription = { remove(): void };
type NotificationResponse = {
  notification: { request: { content: { data: Record<string, unknown> } } };
};
export type ExpoNotifications = {
  getPermissionsAsync(): Promise<{ status: string }>;
  requestPermissionsAsync(): Promise<{ status: string }>;
  getExpoPushTokenAsync(options: { projectId: string }): Promise<{ data: string }>;
  setNotificationChannelAsync(
    id: string,
    options: { name: string; importance: number },
  ): Promise<unknown>;
  addPushTokenListener(listener: () => void): Subscription;
  addNotificationResponseReceivedListener(
    listener: (response: NotificationResponse) => void,
  ): Subscription;
};
type Backend = (path: string, options: { method: string; body?: string }) => Promise<unknown>;

// Call only after a signed-in user explicitly enables notifications on a physical native device.
export async function enableExpoPush(options: {
  notifications: ExpoNotifications;
  backend: Backend;
  projectId: string;
  deviceId: string;
  platform: 'android' | 'ios';
  onOpen: (notificationId: string) => void;
  onError: (error: unknown) => void;
}) {
  const { notifications, backend, projectId, deviceId, onOpen, onError } = options;
  if (!projectId || !deviceId)
    throw new Error('Cần Expo projectId và deviceId của phiên đăng nhập.');
  if (options.platform === 'android')
    await notifications.setNotificationChannelAsync('default', { name: 'SYNERA', importance: 4 });
  let permission = await notifications.getPermissionsAsync();
  if (permission.status !== 'granted') permission = await notifications.requestPermissionsAsync();
  if (permission.status !== 'granted') throw new Error('Bạn chưa cho phép nhận thông báo.');
  let disposed = false;
  const register = async () => {
    const token = await notifications.getExpoPushTokenAsync({ projectId });
    if (!disposed)
      await backend('/notifications/devices', {
        method: 'POST',
        body: JSON.stringify({ deviceId, expoToken: token.data }),
      });
  };
  await register();
  const tokenListener = notifications.addPushTokenListener(() => {
    void register().catch(onError);
  });
  const responseListener = notifications.addNotificationResponseReceivedListener((response) => {
    const id = response.notification.request.content.data.notificationId;
    if (typeof id === 'string') onOpen(id);
  });
  return {
    dispose() {
      disposed = true;
      tokenListener.remove();
      responseListener.remove();
    },
    // Await before logout; the backend also disables this device when revoking the refresh session.
    async unregister() {
      disposed = true;
      tokenListener.remove();
      responseListener.remove();
      await backend(`/notifications/devices/${encodeURIComponent(deviceId)}`, { method: 'DELETE' });
    },
  };
}
