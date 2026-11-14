import { lazy, Suspense } from 'react';
import { Navigate, Route, Routes } from 'react-router-dom';

const Landing = lazy(() => import('../features/landing/Landing'));
const Login = lazy(() => import('../features/auth/Login'));
const Register = lazy(() => import('../features/auth/Register'));
const Workspace = lazy(() => import('../../apps/live/Workspace'));
// Vite folds DEV to false in production, removing the entire demo dependency graph.
const mock = import.meta.env.DEV && import.meta.env.VITE_USE_MOCK === 'true';
const Operations = mock ? lazy(() => import('../../apps/web-nsx/Operations')) : null;
const PortalNpp = mock ? lazy(() => import('../../apps/portal-npp/PortalNpp')) : null;
const PortalOutlet = mock ? lazy(() => import('../../apps/portal-outlet/PortalOutlet')) : null;
const MobileSales = mock ? lazy(() => import('../../apps/mobile-sales/MobileSales')) : null;
export default function App() {
  const nsx = Operations ? <Operations /> : <Workspace portal="nsx" />;
  return (
    <Suspense
      fallback={
        <div className="loading" role="status">
          Đang tải SYNERA…
        </div>
      }
    >
      <Routes>
        <Route path="/" element={nsx} />
        <Route path="/nsx" element={nsx} />
        <Route path="/npp" element={PortalNpp ? <PortalNpp /> : <Workspace portal="npp" />} />
        <Route
          path="/outlet"
          element={PortalOutlet ? <PortalOutlet /> : <Workspace portal="outlet" />}
        />
        <Route
          path="/sales"
          element={MobileSales ? <MobileSales /> : <Workspace portal="sales" />}
        />
        <Route path="/landing" element={<Landing />} />
        <Route path="/login" element={<Login />} />
        <Route path="/register" element={<Register />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </Suspense>
  );
}
