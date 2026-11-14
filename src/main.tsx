import ReactDOM from 'react-dom/client';
import { HashRouter } from 'react-router-dom';
import App from './app/App';
import { QueryClientProvider } from '@tanstack/react-query';
import { queryClient } from '../packages/api/query';
import './styles/tokens.css';
import './styles/base.css';
import './styles/auth.css';
import './styles/landing.css';
import '../apps/web-nsx/operations.css';
import '../apps/portal-npp/portal-npp.css';
import '../apps/portal-outlet/portal-outlet.css';
import '../apps/mobile-sales/mobile-sales.css';
import '../apps/live/live.css';
ReactDOM.createRoot(document.getElementById('root')!).render(
  <QueryClientProvider client={queryClient}>
    <HashRouter>
      <App />
    </HashRouter>
  </QueryClientProvider>,
);
