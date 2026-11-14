import { QueryClient } from '@tanstack/react-query';
import { ApiError, subscribeSession } from './client';

export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      refetchOnWindowFocus: true,
      retry: (count, error) => count < 1 && (!(error instanceof ApiError) || error.status >= 500),
    },
    mutations: { retry: false },
  },
});
// Prevent records and permissions from a previous identity appearing in another session.
subscribeSession(() => {
  queryClient.clear();
});
