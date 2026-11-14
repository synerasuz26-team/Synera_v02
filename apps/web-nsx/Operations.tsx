import { useEffect, useMemo, useState } from 'react';
import {
  Activity,
  AlertTriangle,
  ArrowDownRight,
  ArrowRight,
  ArrowUpRight,
  Bell,
  Boxes,
  Building2,
  CalendarDays,
  Check,
  CheckCheck,
  ChevronDown,
  ChevronLeft,
  ChevronRight,
  CircleCheck,
  CircleHelp,
  ClipboardList,
  Clock3,
  Command,
  HandCoins,
  LayoutDashboard,
  Menu,
  Map,
  MapPin,
  Moon,
  MoreHorizontal,
  Package,
  PanelLeft,
  Search,
  Settings,
  ShieldCheck,
  Sparkles,
  Store,
  Sun,
  TicketCheck,
  TrendingUp,
  Truck,
  UsersRound,
  X,
} from 'lucide-react';
import {
  dailySellIn30d,
  dailySellOut30d,
  distributors,
  featureFlags,
  orders as demoOrders,
  overview,
  people,
  products,
  stores,
  tickets,
} from '../../packages/mock-data';
import {
  CatalogScreen,
  FinanceScreen,
  OrganizationScreen,
  ReportScreen,
  RoutesScreen,
  SettingsScreen,
  SupportScreen,
} from './WorkspaceScreens';
import type { Role, Screen, ToastMessage } from './types';

type Order = (typeof demoOrders)[number];

const roles: { id: Role; name: string; title: string; initials: string; scope: string }[] = [
  { id: 'leader', name: 'Lê Quang', title: 'Lãnh đạo', initials: 'LQ', scope: 'Toàn quốc' },
  {
    id: 'rsm',
    name: people.rsm.name,
    title: people.rsm.title,
    initials: people.rsm.initials,
    scope: 'Miền Nam',
  },
  {
    id: 'asm',
    name: people.asm.name,
    title: people.asm.title,
    initials: people.asm.initials,
    scope: 'TP.HCM',
  },
  {
    id: 'supervisor',
    name: 'Nguyễn Minh',
    title: 'Sup · Quận 3',
    initials: 'NM',
    scope: 'Tuyến Quận 3',
  },
  {
    id: 'sales',
    name: people.sales.name,
    title: 'Sales',
    initials: people.sales.initials,
    scope: 'Tuyến Quận 3',
  },
  { id: 'intake', name: 'Minh Anh', title: 'Tiếp nhận đơn', initials: 'MA', scope: 'Toàn quốc' },
  { id: 'support', name: 'Ngọc Mai', title: 'CSKH', initials: 'NM', scope: 'Toàn quốc' },
  { id: 'trade', name: 'Hải Yến', title: 'Trade marketing', initials: 'HY', scope: 'Miền Nam' },
  { id: 'accounting', name: 'Thu Trang', title: 'Kế toán', initials: 'TT', scope: 'Toàn quốc' },
  { id: 'admin', name: 'Quản trị viên', title: 'Admin', initials: 'AD', scope: 'Toàn quốc' },
];

const menu: { id: Screen; label: string; icon: typeof LayoutDashboard; access: Role[] }[] = [
  {
    id: 'overview',
    label: 'Điều hành',
    icon: LayoutDashboard,
    access: [
      'leader',
      'rsm',
      'asm',
      'supervisor',
      'intake',
      'support',
      'trade',
      'accounting',
      'admin',
    ],
  },
  {
    id: 'organization',
    label: 'Tổ chức bán hàng',
    icon: UsersRound,
    access: ['leader', 'rsm', 'admin'],
  },
  {
    id: 'routes',
    label: 'Vùng, tuyến & MCP',
    icon: Map,
    access: ['leader', 'rsm', 'asm', 'supervisor', 'sales', 'admin'],
  },
  {
    id: 'catalog',
    label: 'Sản phẩm, giá & ưu đãi',
    icon: Package,
    access: ['leader', 'rsm', 'trade', 'accounting', 'admin'],
  },
  {
    id: 'orders',
    label: 'Tiếp nhận & duyệt đơn',
    icon: ClipboardList,
    access: ['leader', 'rsm', 'asm', 'intake', 'accounting', 'admin'],
  },
  {
    id: 'finance',
    label: 'Công nợ & đối soát',
    icon: HandCoins,
    access: ['leader', 'rsm', 'accounting', 'admin'],
  },
  {
    id: 'tickets',
    label: 'Hỗ trợ & phản ánh',
    icon: TicketCheck,
    access: [
      'leader',
      'rsm',
      'asm',
      'supervisor',
      'sales',
      'support',
      'trade',
      'accounting',
      'admin',
    ],
  },
  {
    id: 'reports',
    label: 'Báo cáo sell-in/sell-out',
    icon: Activity,
    access: ['leader', 'rsm', 'asm', 'accounting', 'admin'],
  },
  { id: 'settings', label: 'Người dùng & cấu hình', icon: Settings, access: ['leader', 'admin'] },
];

const fmt = new Intl.NumberFormat('vi-VN');
const money = (value: number) => `${fmt.format(value)}đ`;
const currentRole = (id: Role) => roles.find((role) => role.id === id) ?? roles[0];

function getScreenTitle(screen: Screen) {
  return menu.find((item) => item.id === screen)?.label ?? 'Điều hành';
}

function StatusBadge({ children, tone = 'neutral' }: { children: React.ReactNode; tone?: string }) {
  return <span className={`ops-badge ${tone}`}>{children}</span>;
}

function QuantityStepper({ order }: { order: Order }) {
  const steps = [
    { label: 'Đặt', value: order.ordered },
    { label: 'Xác nhận', value: order.confirmed },
    { label: 'Thực xuất', value: order.shipped },
    { label: 'Thực nhận', value: order.received },
  ];
  return (
    <div className="ops-stepper" aria-label="Tiến trình số lượng đơn hàng">
      {steps.map((step, index) => (
        <div className={`ops-step ${step.value > 0 ? 'done' : ''}`} key={step.label}>
          <span className="ops-step-dot">{step.value > 0 ? <Check size={12} /> : index + 1}</span>
          <span className="ops-step-label">{step.label}</span>
          <strong>{step.value}</strong>
          {index < steps.length - 1 && <span className="ops-step-line" />}
        </div>
      ))}
    </div>
  );
}

function OrderKind({ type }: { type: Order['type'] }) {
  return (
    <span className={`ops-order-kind ${type}`}>{type === 'sell-in' ? 'SELL-IN' : 'SELL-OUT'}</span>
  );
}

function Sidebar({
  role,
  screen,
  onNavigate,
  onRoleChange,
  mobileOpen,
  onClose,
  collapsed,
  onToggleCollapse,
}: {
  role: Role;
  screen: Screen;
  onNavigate: (screen: Screen) => void;
  onRoleChange: (role: Role) => void;
  mobileOpen: boolean;
  onClose: () => void;
  collapsed: boolean;
  onToggleCollapse: () => void;
}) {
  return (
    <>
      {mobileOpen && (
        <button className="ops-mobile-scrim" onClick={onClose} aria-label="Đóng menu" />
      )}
      <aside
        className={`ops-sidebar ${mobileOpen ? 'mobile-open' : ''} ${collapsed ? 'collapsed' : ''}`}
      >
        <a className="ops-brand" href="#/" aria-label="SYNERA · Điều hành NSX">
          <span className="ops-brand-mark">S</span>
          <span>
            <strong>SYNERA</strong>
            <small>NSX SUỐI VIỆT</small>
          </span>
        </a>
        <button
          type="button"
          className="ops-brand-collapse"
          onClick={onToggleCollapse}
          aria-label={collapsed ? 'Mở rộng thanh điều hướng' : 'Thu gọn thanh điều hướng'}
        >
          <PanelLeft size={17} />
        </button>
        <div className="ops-workspace">
          <span className="ops-workspace-mark">SV</span>
          <span>
            <strong>Suối Việt</strong>
            <small>Nhà sản xuất</small>
          </span>
          <ChevronDown size={16} />
        </div>
        <span className="ops-nav-caption">KHÔNG GIAN LÀM VIỆC</span>
        <nav className="ops-nav" aria-label="Điều hướng chính">
          {menu.map((item) => {
            const Icon = item.icon;
            const permitted = item.access.includes(role);
            return (
              <button
                key={item.id}
                className={`ops-nav-link ${screen === item.id ? 'active' : ''} ${!permitted ? 'locked' : ''}`}
                onClick={() => (permitted ? onNavigate(item.id) : undefined)}
                disabled={!permitted}
                title={
                  !permitted
                    ? `Vai trò ${currentRole(role).title} chưa được cấp quyền xem mục này`
                    : ''
                }
                aria-current={screen === item.id ? 'page' : undefined}
              >
                <Icon size={18} strokeWidth={1.8} />
                <span>{item.label}</span>
                {item.id === 'orders' && <span className="ops-nav-count">1</span>}
              </button>
            );
          })}
        </nav>
        <div className="ops-sidebar-spacer" />
        <div className="ops-sidebar-bottom">
          <div className="ops-help-link">
            <CircleHelp size={17} />
            <span>Trung tâm trợ giúp</span>
          </div>
          <label className="ops-role-switcher">
            <span>VAI TRÒ DEMO</span>
            <span className="ops-role-select-wrap">
              <span className="ops-avatar small">{currentRole(role).initials}</span>
              <select
                value={role}
                onChange={(event) => onRoleChange(event.target.value as Role)}
                aria-label="Chuyển vai trò demo"
              >
                {roles.map((item) => (
                  <option value={item.id} key={item.id}>
                    {item.name} · {item.title}
                  </option>
                ))}
              </select>
              <ChevronDown size={14} />
            </span>
          </label>
        </div>
      </aside>
    </>
  );
}

function Header({
  role,
  screen,
  onSearch,
  onTheme,
  dark,
  onHelp,
}: {
  role: Role;
  screen: Screen;
  onSearch: () => void;
  onTheme: () => void;
  dark: boolean;
  onHelp: () => void;
}) {
  const [notificationsOpen, setNotificationsOpen] = useState(false);
  return (
    <header className="ops-topbar">
      <button
        className="ops-mobile-menu"
        aria-label="Mở menu"
        onClick={() => window.dispatchEvent(new Event('ops:menu'))}
      >
        <Menu size={19} />
      </button>
      <div className="ops-breadcrumb">
        <span>SYNERA / NSX SUỐI VIỆT</span>
        <ChevronRight size={14} />
        <strong>{getScreenTitle(screen)}</strong>
      </div>
      <span className="ops-scope-pill">
        <MapPin size={14} />
        {currentRole(role).scope}
      </span>
      <div className="ops-topbar-actions">
        <button className="ops-search-trigger" onClick={onSearch} aria-label="Tìm kiếm nhanh">
          <Search size={16} />
          <span>Tìm kiếm</span>
          <kbd>⌘ K</kbd>
        </button>
        <button className="ops-icon-button" aria-label="Đổi giao diện sáng tối" onClick={onTheme}>
          {dark ? <Sun size={18} /> : <Moon size={18} />}
        </button>
        <button className="ops-icon-button" aria-label="Trợ giúp" onClick={onHelp}>
          <CircleHelp size={18} />
        </button>
        <div className="ops-notifications">
          <button
            className="ops-icon-button notification-button"
            aria-label="Thông báo"
            aria-expanded={notificationsOpen}
            onClick={() => setNotificationsOpen(!notificationsOpen)}
          >
            <Bell size={18} />
            <span />
          </button>
          {notificationsOpen && (
            <div className="ops-notification-popover">
              <div className="ops-popover-title">
                <strong>Thông báo</strong>
                <button onClick={() => setNotificationsOpen(false)} aria-label="Đóng thông báo">
                  <X size={15} />
                </button>
              </div>
              <p>
                <span className="ops-notice-dot" />
                SI-1182 đang chờ NSX duyệt
              </p>
              <p>
                <span className="ops-notice-dot amber" />
                TK-331 cần xử lý trước 13/11
              </p>
            </div>
          )}
        </div>
        <span className="ops-topbar-divider" />
        <button className="ops-profile" aria-label={`Vai trò hiện tại ${currentRole(role).title}`}>
          <span className="ops-avatar">{currentRole(role).initials}</span>
          <span className="ops-profile-copy">
            <strong>{currentRole(role).name}</strong>
            <small>NSX SUỐI VIỆT · {currentRole(role).title}</small>
          </span>
          <ChevronDown size={15} />
        </button>
      </div>
    </header>
  );
}

function MetricCard({
  label,
  value,
  detail,
  freshness,
  icon: Icon,
  color,
  screen,
  onNavigate,
}: {
  label: string;
  value: string;
  detail: string;
  freshness?: string;
  icon: typeof ClipboardList;
  color: string;
  screen?: Screen;
  onNavigate?: (screen: Screen) => void;
}) {
  return (
    <button
      className="ops-metric-card"
      onClick={() => screen && onNavigate?.(screen)}
      disabled={!screen}
    >
      <span className={`ops-metric-icon ${color}`}>
        <Icon size={18} />
      </span>
      <span className="ops-metric-label">{label}</span>
      <strong className="ops-metric-value">{value}</strong>
      <span className="ops-metric-detail">{detail}</span>
      {freshness && <span className="ops-metric-freshness">{freshness}</span>}
      {screen && <ArrowUpRight size={15} className="ops-metric-arrow" />}
    </button>
  );
}

function ComparisonCard({
  label,
  value,
  icon: Icon,
  secondary,
  onDetails,
}: {
  label: string;
  value: string;
  icon: typeof Activity;
  secondary: string;
  onDetails?: () => void;
}) {
  return (
    <div className="ops-comparison-card">
      <span className="ops-comparison-icon">
        <Icon size={17} />
      </span>
      <span className="ops-comparison-copy">
        <span>{label}</span>
        <strong>{value}</strong>
        <small>{secondary}</small>
      </span>
      {onDetails && (
        <button className="ops-subtle-button" onClick={onDetails} aria-label="Xem chi tiết báo cáo">
          <ArrowUpRight size={16} />
        </button>
      )}
    </div>
  );
}

function OrdersTable({
  rows,
  onSelect,
  onApprove,
  onRole,
  canApprove,
  selected,
  onToggle,
  onToggleAll,
  compact = false,
}: {
  rows: Order[];
  onSelect: (order: Order) => void;
  onApprove: (order: Order) => void;
  onRole: Role;
  canApprove: boolean;
  selected: string[];
  onToggle: (id: string) => void;
  onToggleAll: () => void;
  compact?: boolean;
}) {
  return (
    <div className={`ops-table-scroll ${compact ? 'compact' : ''}`}>
      <table className="ops-table">
        <thead>
          <tr>
            {!compact && (
              <th className="ops-check-column">
                <input
                  type="checkbox"
                  aria-label="Chọn tất cả đơn hàng"
                  checked={rows.length > 0 && rows.every((order) => selected.includes(order.id))}
                  onChange={onToggleAll}
                />
              </th>
            )}
            <th>Mã đơn</th>
            <th>Khách hàng</th>
            <th>Trạng thái</th>
            <th>Số đặt</th>
            <th>Xác nhận</th>
            <th>Thực xuất</th>
            <th>Thực nhận</th>
            <th>Cập nhật</th>
            <th aria-label="Thao tác" />
          </tr>
        </thead>
        <tbody>
          {rows.map((order) => {
            const waiting = order.status === 'Chờ NSX duyệt';
            const tone =
              order.status === 'Chờ NSX duyệt' || order.status === 'Chờ NPP xác nhận'
                ? 'pending'
                : order.status.startsWith('Giao thiếu')
                  ? 'warning'
                  : order.status === 'Đang giao'
                    ? 'info'
                    : 'success';
            return (
              <tr key={order.id} onClick={() => onSelect(order)}>
                {!compact && (
                  <td onClick={(event) => event.stopPropagation()}>
                    <input
                      type="checkbox"
                      aria-label={`Chọn đơn ${order.id}`}
                      checked={selected.includes(order.id)}
                      onChange={() => onToggle(order.id)}
                    />
                  </td>
                )}
                <td>
                  <span className="ops-order-id">{order.id}</span>
                  <OrderKind type={order.type} />
                </td>
                <td>
                  <strong className="ops-table-primary">{order.customer}</strong>
                  <span className="ops-table-secondary">{order.owner}</span>
                </td>
                <td>
                  <StatusBadge tone={tone}>{order.status}</StatusBadge>
                </td>
                <td className="ops-number-cell">{order.ordered}</td>
                <td className="ops-number-cell">{order.confirmed}</td>
                <td className="ops-number-cell">{order.shipped}</td>
                <td className="ops-number-cell">{order.received}</td>
                <td className="ops-muted-cell">{order.createdAt}</td>
                <td className="ops-row-action" onClick={(event) => event.stopPropagation()}>
                  {waiting && (
                    <button
                      className="ops-inline-action"
                      onClick={() => (canApprove ? onApprove(order) : undefined)}
                      disabled={!canApprove}
                      title={
                        !canApprove
                          ? `Vai trò ${currentRole(onRole).title} không có quyền duyệt đơn`
                          : 'Duyệt đơn'
                      }
                    >
                      Duyệt
                    </button>
                  )}
                  {!waiting && <MoreHorizontal size={17} />}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
      {rows.length === 0 && (
        <div className="ops-empty-state">
          <span>
            <Search size={19} />
          </span>
          <strong>Chưa tìm thấy đơn phù hợp</strong>
          <p>Thử đổi từ khóa hoặc bỏ bớt bộ lọc để xem thêm đơn hàng.</p>
        </div>
      )}
    </div>
  );
}

function OrderDrawer({
  order,
  onClose,
  onApprove,
  canApprove,
  canApproveOverLimit,
  role,
  onReject,
  onRequestMore,
}: {
  order: Order;
  onClose: () => void;
  onApprove: () => void;
  canApprove: boolean;
  canApproveOverLimit: boolean;
  role: Role;
  onReject: () => void;
  onRequestMore: () => void;
}) {
  const showSellInPrice = role !== 'sales';
  return (
    <div className="ops-drawer-backdrop" onMouseDown={onClose}>
      <aside
        className="ops-drawer"
        role="dialog"
        aria-modal="true"
        aria-labelledby="ops-drawer-title"
        onMouseDown={(event) => event.stopPropagation()}
      >
        <div className="ops-drawer-header">
          <div>
            <span className="ops-eyebrow">CHI TIẾT ĐƠN HÀNG</span>
            <h2 id="ops-drawer-title">{order.id}</h2>
          </div>
          <button className="ops-icon-button" onClick={onClose} aria-label="Đóng chi tiết">
            <X size={19} />
          </button>
        </div>
        <div className="ops-drawer-body">
          <div className="ops-drawer-order-meta">
            <OrderKind type={order.type} />
            <StatusBadge tone={order.status.startsWith('Giao thiếu') ? 'warning' : 'pending'}>
              {order.status}
            </StatusBadge>
            <p>
              <strong>{order.customer}</strong>
              <span>Người đặt: {order.owner}</span>
            </p>
          </div>
          <div className="ops-drawer-section">
            <h3>Tiến trình đơn hàng</h3>
            <QuantityStepper order={order} />
            <p className="ops-order-reminder">Đã tạo đơn ≠ đã giao đủ ≠ điểm bán đã nhận đủ</p>
          </div>
          <div className="ops-drawer-section">
            <div className="ops-section-heading">
              <h3>Sản phẩm</h3>
              <span>Giá theo bảng hiệu lực</span>
            </div>
            {order.lines.length > 0 ? (
              <div className="ops-order-lines">
                {order.lines.map((line) => {
                  const product = products.find((item) => item.sku === line.sku);
                  return (
                    <div className="ops-product-line" key={line.sku}>
                      <span className="ops-product-thumb">
                        <Boxes size={17} />
                      </span>
                      <span>
                        <strong>{line.name}</strong>
                        <small>
                          {line.sku} · {line.quantity} thùng
                        </small>
                      </span>
                      {showSellInPrice && product && (
                        <strong>
                          {money(order.type === 'sell-in' ? product.sellIn : product.sellOut)}
                          <small>/ thùng</small>
                        </strong>
                      )}
                    </div>
                  );
                })}
                {order.freeGoods?.map((item) => (
                  <div className="ops-free-goods" key={`gift-${item.sku}`}>
                    <span>
                      <Sparkles size={15} /> Hàng tặng · {item.name}
                    </span>
                    <strong>{item.quantity} thùng</strong>
                    <small>Không tính vào số thu</small>
                  </div>
                ))}
                {order.total != null && (
                  <div className="ops-order-total">
                    <span>Tổng tiền hàng</span>
                    <strong>{money(order.total)}</strong>
                  </div>
                )}
              </div>
            ) : (
              <p className="ops-muted-note">Đơn tổng hợp · {order.ordered} thùng</p>
            )}
          </div>
          {order.type === 'sell-in' && (
            <div className="ops-credit-card">
              <div className="ops-credit-heading">
                <HandCoins size={18} />
                <strong>Kiểm tra hạn mức công nợ</strong>
              </div>
              <div className="ops-credit-figures">
                <span>
                  Hạn mức còn lại
                  <strong>
                    {order.creditRemaining ?? distributors[1].creditRemaining ?? '—'}
                    {order.creditRemaining ? ' thùng' : ''}
                  </strong>
                </span>
                <span>
                  Đơn đang đặt<strong>{order.ordered} thùng</strong>
                </span>
              </div>
              <span className="ops-limit-meter">
                <i
                  style={{
                    width: `${Math.min(100, order.creditRemaining ? (order.ordered / (order.creditRemaining + order.ordered)) * 100 : 0)}%`,
                  }}
                />
              </span>
              {order.creditRemaining != null && order.ordered > order.creditRemaining ? (
                <p className="held">
                  <AlertTriangle size={15} /> Giữ do vượt hạn mức · yêu cầu cấp RSM/Lãnh đạo duyệt.
                </p>
              ) : (
                <p>
                  <CircleCheck size={15} /> Đơn đặt trong hạn mức. Đơn sẽ được giữ và chuyển cấp
                  duyệt nếu vượt hạn mức.
                </p>
              )}
            </div>
          )}
          <div className="ops-drawer-section ops-checklist">
            <h3>Điều kiện trước khi duyệt</h3>
            <p>
              <CircleCheck size={16} /> CTKM áp đúng theo bảng giá hiệu lực
            </p>
            <p>
              <CircleCheck size={16} /> Tồn kho khả dụng đáp ứng đơn
            </p>
            <p>
              <CircleCheck size={16} /> Công nợ trong hạn mức
            </p>
          </div>
          <div className="ops-drawer-section">
            <h3>Thông tin bổ sung</h3>
            <p className="ops-muted-note">{order.note}</p>
            <div className="ops-price-validity">
              <ShieldCheck size={15} /> Bảng giá Suối Việt có hiệu lực đến{' '}
              {overview.priceValidThrough}
            </div>
          </div>
          <div className="ops-drawer-section ops-history">
            <h3>Lịch sử xử lý</h3>
            <p>
              <i /> {order.createdAt} · {order.owner} tạo đơn
            </p>
            <p>
              <i />{' '}
              {order.type === 'sell-in'
                ? 'Đang chờ NSX kiểm tra và duyệt'
                : 'NPP Minh Phát cập nhật tiến trình giao nhận'}
            </p>
          </div>
          {!featureFlags.aiSuggestionsEnabled && (
            <div className="ops-phase-note">
              <Sparkles size={15} />
              <span>
                <strong>Gợi ý AI</strong> · Phase 2 · Chưa bật
              </span>
            </div>
          )}
        </div>
        {order.status === 'Chờ NSX duyệt' && (
          <div className="ops-drawer-footer">
            {order.creditRemaining != null && order.ordered > order.creditRemaining ? (
              <button
                className="ops-button primary"
                onClick={onApprove}
                disabled={!canApproveOverLimit}
                title={!canApproveOverLimit ? 'Chỉ RSM hoặc Lãnh đạo được duyệt vượt hạn mức' : ''}
              >
                <CheckCheck size={16} /> Duyệt vượt hạn mức
              </button>
            ) : (
              <button
                className="ops-button primary"
                onClick={onApprove}
                disabled={!canApprove}
                title={
                  !canApprove ? `Vai trò ${currentRole(role).title} không có quyền duyệt đơn` : ''
                }
              >
                <CheckCheck size={16} /> Duyệt đơn
              </button>
            )}
            <button
              className="ops-button secondary"
              onClick={onReject}
              disabled={!canApprove}
              title={
                !canApprove ? `Vai trò ${currentRole(role).title} không có quyền từ chối đơn` : ''
              }
            >
              Từ chối
            </button>
            <button className="ops-button quiet" onClick={onRequestMore}>
              Yêu cầu bổ sung
            </button>
            <button className="ops-button secondary" onClick={onClose}>
              Đóng
            </button>
          </div>
        )}
      </aside>
    </div>
  );
}

export default function Operations() {
  const [screen, setScreen] = useState<Screen>('overview');
  const [role, setRole] = useState<Role>('leader');
  const [orderRows, setOrderRows] = useState<Order[]>(demoOrders);
  const [selectedOrder, setSelectedOrder] = useState<Order | null>(null);
  const [query, setQuery] = useState('');
  const [orderFilter, setOrderFilter] = useState<
    'all' | 'sell-in' | 'sell-out' | 'pending' | 'held' | 'shipping' | 'received'
  >('all');
  const [selected, setSelected] = useState<string[]>([]);
  const [toast, setToast] = useState<ToastMessage | null>(null);
  const [searchOpen, setSearchOpen] = useState(false);
  const [searchQuery, setSearchQuery] = useState('');
  const [mobileOpen, setMobileOpen] = useState(false);
  const [collapsed, setCollapsed] = useState(false);
  const [dark, setDark] = useState(false);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState(false);
  const [sortNewest, setSortNewest] = useState(true);
  const [actionDialog, setActionDialog] = useState<{
    title: string;
    kind: string;
    detail: string;
  } | null>(null);
  const [actionReason, setActionReason] = useState('');

  const canApprove = ['leader', 'rsm', 'asm', 'admin'].includes(role);
  const canApproveOverLimit = ['leader', 'rsm'].includes(role);
  const activeRole = currentRole(role);
  const canViewOrders = menu.find((item) => item.id === 'orders')?.access.includes(role) ?? false;
  const canViewRoutes = menu.find((item) => item.id === 'routes')?.access.includes(role) ?? false;
  const canViewCatalog = menu.find((item) => item.id === 'catalog')?.access.includes(role) ?? false;
  const visibleOrders = useMemo(() => {
    let rows = orderRows.filter((order) => {
      const matchesQuery =
        `${order.id} ${order.customer} ${order.owner} ${order.lines.map((line) => line.sku).join(' ')}`
          .toLowerCase()
          .includes(query.toLowerCase());
      const matchesType =
        orderFilter === 'all' ||
        (orderFilter === 'pending'
          ? order.status === 'Chờ NSX duyệt' || order.status === 'Chờ NPP xác nhận'
          : orderFilter === 'held'
            ? order.status === 'Giữ do vượt hạn mức'
            : orderFilter === 'shipping'
              ? order.status === 'Đang giao'
              : orderFilter === 'received'
                ? order.status === 'Đã nhận'
                : order.type === orderFilter);
      return matchesQuery && matchesType;
    });
    if (!sortNewest) rows = [...rows].reverse();
    return rows;
  }, [orderFilter, orderRows, query, sortNewest]);

  const searchResults = useMemo(() => {
    const term = searchQuery.trim().toLowerCase();
    if (!term) return [];
    return [
      ...(canViewOrders
        ? orderRows
            .filter((order) =>
              `${order.id} ${order.customer} ${order.owner}`.toLowerCase().includes(term),
            )
            .map((order) => ({
              key: order.id,
              label: order.id,
              detail: order.customer,
              kind: 'order' as const,
              order,
            }))
        : []),
      ...(canViewRoutes
        ? stores
            .filter((store) =>
              `${store.name} ${store.address} ${store.contact}`.toLowerCase().includes(term),
            )
            .map((store) => ({
              key: store.name,
              label: store.name,
              detail: store.address,
              kind: 'store' as const,
            }))
        : []),
      ...(canViewCatalog
        ? products
            .filter((product) => `${product.sku} ${product.name}`.toLowerCase().includes(term))
            .map((product) => ({
              key: product.sku,
              label: product.sku,
              detail: product.name,
              kind: 'product' as const,
            }))
        : []),
    ];
  }, [canViewCatalog, canViewOrders, canViewRoutes, orderRows, searchQuery]);

  useEffect(() => {
    const timer = window.setTimeout(() => setLoading(false), 420);
    return () => window.clearTimeout(timer);
  }, []);

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
        event.preventDefault();
        setSearchOpen(true);
      }
      if (event.key === 'Escape') {
        setSearchOpen(false);
        setSelectedOrder(null);
        setMobileOpen(false);
      }
    };
    const onMenu = () => setMobileOpen(true);
    document.addEventListener('keydown', onKeyDown);
    window.addEventListener('ops:menu', onMenu);
    return () => {
      document.removeEventListener('keydown', onKeyDown);
      window.removeEventListener('ops:menu', onMenu);
    };
  }, []);

  useEffect(() => {
    if (!toast) return;
    const timer = window.setTimeout(() => setToast(null), 4200);
    return () => window.clearTimeout(timer);
  }, [toast]);

  useEffect(() => {
    if (selectedOrder)
      setSelectedOrder(orderRows.find((order) => order.id === selectedOrder.id) ?? null);
  }, [orderRows, selectedOrder]);

  const approveOrder = (order: Order) => {
    if (!canApprove) return;
    const previous = orderRows;
    setOrderRows((current) =>
      current.map((item) =>
        item.id === order.id ? { ...item, status: 'Đang chuẩn bị hàng' } : item,
      ),
    );
    setToast({
      message: `Đã duyệt đơn ${order.id}`,
      undo: () => {
        setOrderRows(previous);
        setSelectedOrder(order);
      },
    });
  };

  const openScreen = (nextScreen: Screen) => {
    const requiredScreen = nextScreen === 'stores' ? 'routes' : nextScreen;
    if (menu.find((item) => item.id === requiredScreen)?.access.includes(role)) {
      setScreen(nextScreen);
      setMobileOpen(false);
    }
  };

  const changeRole = (nextRole: Role) => {
    setRole(nextRole);
    if (!menu.some((item) => item.id === screen && item.access.includes(nextRole)))
      setScreen('overview');
    setSelected([]);
  };

  const submitOrderAction = () => {
    if (!selectedOrder || !actionDialog || !actionReason.trim()) return;
    const newStatus = actionDialog.kind === 'reject' ? 'Từ chối' : 'Chờ bổ sung';
    setOrderRows((current) =>
      current.map((order) =>
        order.id === selectedOrder.id ? { ...order, status: newStatus } : order,
      ),
    );
    setSelectedOrder(null);
    setActionDialog(null);
    setToast({ message: `${selectedOrder.id} · ${newStatus}` });
  };

  const renderLoading = () => (
    <div className="ops-skeleton-page" aria-label="Đang tải dữ liệu" role="status">
      <span className="ops-skeleton ops-skeleton-title" />
      <span className="ops-skeleton ops-skeleton-subtitle" />
      <div className="ops-skeleton-metrics">
        {[0, 1, 2, 3].map((item) => (
          <span className="ops-skeleton" key={item} />
        ))}
      </div>
      <div className="ops-skeleton-content">
        <span className="ops-skeleton" />
        <span className="ops-skeleton" />
      </div>
    </div>
  );

  const renderOverview = () => (
    <>
      <div className="ops-page-heading">
        <div>
          <span className="ops-eyebrow">THỨ NĂM, 12 THÁNG 11, 2026</span>
          <h1>Tổng quan điều hành</h1>
          <p>Chào {activeRole.name}, đây là nhịp vận hành hôm nay.</p>
        </div>
        <button className="ops-button secondary">
          <CalendarDays size={16} /> 12/11/2026 <ChevronDown size={15} />
        </button>
      </div>
      <section className="ops-metrics-grid" aria-label="Chỉ số điều hành">
        <MetricCard
          label="SELL-IN CHỜ DUYỆT"
          value={`${overview.sellInWaiting} đơn`}
          detail="SI-1182 · NPP Minh Phát"
          freshness={`Tạm tính · cập nhật ${overview.operationalUpdatedAt}`}
          icon={ClipboardList}
          color="blue"
          screen="orders"
          onNavigate={() => {
            setOrderFilter('sell-in');
            openScreen('orders');
          }}
        />
        <MetricCard
          label="ĐỘ PHỦ MCP"
          value={`${overview.mcpVisited}/${overview.mcpTotal}`}
          detail="Cafe 24 chưa được ghé"
          freshness={`Tạm tính · cập nhật ${overview.operationalUpdatedAt}`}
          icon={Store}
          color="teal"
          screen="routes"
          onNavigate={openScreen}
        />
        <MetricCard
          label="PHIẾU ĐANG MỞ"
          value={`${overview.openTickets} phiếu`}
          detail="Có 1 phiếu giao thiếu"
          freshness={`Tạm tính · cập nhật ${overview.operationalUpdatedAt}`}
          icon={TicketCheck}
          color="amber"
          screen="tickets"
          onNavigate={openScreen}
        />
        <MetricCard
          label="SELL-OUT TUẦN"
          value={`${overview.sellOutWeek} thùng`}
          detail="NPP cập nhật 12/11"
          freshness={`Tạm tính · cập nhật ${overview.operationalUpdatedAt}`}
          icon={TrendingUp}
          color="purple"
          screen="reports"
          onNavigate={openScreen}
        />
      </section>
      <div className="ops-report-strip">
        <div className="ops-report-strip-heading">
          <div>
            <span className="ops-eyebrow">ĐỐI SOÁT SELL-OUT · TUẦN NÀY</span>
            <h2>Hai lớp dữ liệu, một góc nhìn</h2>
          </div>
          <button className="ops-text-link" onClick={() => openScreen('reports')}>
            Mở báo cáo <ArrowRight size={15} />
          </button>
        </div>
        <div className="ops-comparison-grid">
          <ComparisonCard
            label={`Tạm tính, cập nhật lúc ${overview.operationalUpdatedAt}`}
            value={`${overview.sellOutWeek} thùng`}
            secondary="Lớp 1 · Vận hành"
            icon={Activity}
          />
          <ComparisonCard
            label={`Đã chốt đến ngày ${overview.settledThrough}`}
            value={`${overview.sellOutSettled} thùng`}
            secondary="Lớp 2 · Đã chốt"
            icon={ShieldCheck}
          />
          <div className="ops-delta-card">
            <span className="ops-delta-icon">
              <ArrowDownRight size={17} />
            </span>
            <span>
              <small>Chênh lệch hai lớp</small>
              <strong>
                {overview.sellOutWeek - overview.sellOutSettled} thùng{' '}
                <em>
                  (
                  {(
                    ((overview.sellOutWeek - overview.sellOutSettled) / overview.sellOutSettled) *
                    100
                  ).toFixed(1)}
                  %)
                </em>
              </strong>
            </span>
            {Math.abs(overview.sellOutWeek - overview.sellOutSettled) / overview.sellOutSettled >
              0.05 && <StatusBadge tone="warning">Lệch trên 5%</StatusBadge>}
          </div>
        </div>
      </div>
      <div className="ops-dashboard-grid">
        <section className="ops-panel ops-orders-panel">
          <div className="ops-panel-heading">
            <div>
              <span className="ops-eyebrow">CẦN THEO DÕI</span>
              <h2>Đơn hàng gần đây</h2>
            </div>
            <button className="ops-text-link" onClick={() => openScreen('orders')}>
              Xem hàng đợi đơn <ArrowRight size={15} />
            </button>
          </div>
          <div className="ops-table-title">
            <span>Đơn hàng đang vận hành</span>
            <span className="ops-live-label">
              <i /> Live
            </span>
          </div>
          <OrdersTable
            rows={orderRows}
            onSelect={setSelectedOrder}
            onApprove={approveOrder}
            onRole={role}
            canApprove={canApprove}
            selected={selected}
            onToggle={(id) =>
              setSelected((value) =>
                value.includes(id) ? value.filter((item) => item !== id) : [...value, id],
              )
            }
            onToggleAll={() =>
              setSelected(
                selected.length === orderRows.length ? [] : orderRows.map((order) => order.id),
              )
            }
            compact
          />
          <p className="ops-table-footnote">
            Mỗi đơn có số đặt, xác nhận, thực xuất và thực nhận riêng.
          </p>
        </section>
        <div className="ops-side-stack">
          <section className="ops-panel ops-credit-panel">
            <div className="ops-panel-heading">
              <div>
                <span className="ops-eyebrow">THEO DÕI CÔNG NỢ</span>
                <h2>NPP Minh Phát</h2>
              </div>
              <button className="ops-icon-button small-button" aria-label="Thêm thao tác">
                <MoreHorizontal size={18} />
              </button>
            </div>
            <div className="ops-credit-balance">
              <span>Công nợ hiện tại</span>
              <strong>{money(overview.creditTracked)}</strong>
            </div>
            <div className="ops-credit-remaining">
              <span>Hạn mức còn lại</span>
              <strong>{overview.creditRemainingCases} thùng</strong>
            </div>
            <div className="ops-credit-rule">
              <span>
                <span style={{ width: '63%' }} />
              </span>
              <small>Đơn SI-1182: 40 thùng · Trong hạn mức</small>
            </div>
            <button className="ops-panel-footer-link" onClick={() => openScreen('orders')}>
              Kiểm tra đơn Sell-in <ArrowRight size={15} />
            </button>
          </section>
          <section className="ops-panel ops-work-panel">
            <div className="ops-panel-heading">
              <div>
                <span className="ops-eyebrow">PHỐI HỢP LIÊN PHÒNG BAN</span>
                <h2>Việc cần xử lý</h2>
              </div>
              <StatusBadge tone="warning">2 việc</StatusBadge>
            </div>
            {tickets.map((ticket) => (
              <button
                className="ops-work-item"
                key={ticket.id}
                onClick={() => openScreen('tickets')}
              >
                <span className={`ops-work-icon ${ticket.kind === 'POSM' ? 'purple' : 'amber'}`}>
                  {ticket.kind === 'POSM' ? <Building2 size={16} /> : <Truck size={16} />}
                </span>
                <span className="ops-work-copy">
                  <strong>{ticket.title}</strong>
                  <small>
                    {ticket.id} · {ticket.assignee} · Hạn {ticket.due}
                  </small>
                </span>
                <ChevronRight size={15} />
              </button>
            ))}
            <button className="ops-panel-footer-link" onClick={() => openScreen('tickets')}>
              Mở danh sách ticket <ArrowRight size={15} />
            </button>
          </section>
        </div>
      </div>
      <div className="ops-dashboard-grid dashboard-lower">
        <section className="ops-panel ops-chart-panel">
          <div className="ops-panel-heading">
            <div>
              <span className="ops-eyebrow">XU HƯỚNG 30 NGÀY</span>
              <h2>Sell-in & Sell-out</h2>
            </div>
            <span className="ops-chart-period">
              <CalendarDays size={15} /> 14/10 – 12/11
            </span>
          </div>
          <div className="ops-chart-legend">
            <span>
              <i className="dot blue" /> Sell-in
            </span>
            <span>
              <i className="dot teal" /> Sell-out
            </span>
            <small>Dữ liệu minh họa</small>
          </div>
          <div className="ops-bars" aria-label="Biểu đồ sell-in và sell-out 30 ngày">
            {dailySellOut30d.map((value, index) => (
              <span
                className="ops-bar-pair"
                key={`${index}-${value}`}
                title={`${14 + Math.floor((index + 17) / 30)} ngày · Sell-in ${dailySellIn30d[index]} · Sell-out ${value}`}
              >
                <i style={{ height: `${Math.round((dailySellIn30d[index] / 49) * 100)}%` }} />
                <b style={{ height: `${Math.round((value / 49) * 100)}%` }} />
              </span>
            ))}
          </div>
          <div className="ops-chart-axis">
            <span>14/10</span>
            <span>21/10</span>
            <span>28/10</span>
            <span>04/11</span>
            <span>12/11</span>
          </div>
        </section>
        <section className="ops-panel ops-organization-panel">
          <div className="ops-panel-heading">
            <div>
              <span className="ops-eyebrow">TỔ CHỨC & TUYẾN</span>
              <h2>Miền Nam · TP.HCM</h2>
            </div>
            <button
              className="ops-subtle-button"
              onClick={() => openScreen('organization')}
              aria-label="Mở tổ chức bán hàng"
            >
              <ArrowUpRight size={16} />
            </button>
          </div>
          <div className="ops-route-crumb">
            <span>Miền Nam</span>
            <ChevronRight size={13} />
            <span>TP.HCM</span>
            <ChevronRight size={13} />
            <strong>Tuyến Quận 3</strong>
          </div>
          <div className="ops-route-coverage">
            <span>
              <strong>4/5</strong>
              <small>MCP hôm nay</small>
            </span>
            <span>
              <strong>1</strong>
              <small>Chưa ghé</small>
            </span>
            <span>
              <strong>5</strong>
              <small>Điểm bán</small>
            </span>
          </div>
          <button className="ops-panel-footer-link" onClick={() => openScreen('routes')}>
            Xem tuyến RS00301 <ArrowRight size={15} />
          </button>
        </section>
      </div>
      <div className="ops-dashboard-grid dashboard-lower">
        <section className="ops-panel ops-compact-product-panel">
          <div className="ops-panel-heading">
            <div>
              <span className="ops-eyebrow">SẢN PHẨM, GIÁ & ƯU ĐÃI</span>
              <h2>Bảng giá hiệu lực</h2>
            </div>
            <button className="ops-text-link" onClick={() => openScreen('catalog')}>
              Tra cứu giá <ArrowRight size={15} />
            </button>
          </div>
          <div className="ops-mini-product-table">
            {products.map((product) => (
              <div className="ops-mini-product-row" key={product.sku}>
                <span className="ops-product-thumb">
                  <Boxes size={16} />
                </span>
                <span className="ops-mini-product-name">
                  <strong>{product.sku}</strong>
                  <small>{product.name}</small>
                </span>
                <span>
                  <small>Sell-in</small>
                  <strong>{money(product.sellIn)}</strong>
                </span>
                <span>
                  <small>Ưu đãi</small>
                  <strong>{product.promotion || '—'}</strong>
                </span>
              </div>
            ))}
          </div>
          <div className="ops-price-validity">
            <ShieldCheck size={15} /> Giá tự áp dụng theo bảng hiệu lực đến{' '}
            {overview.priceValidThrough} · Không nhập tay đơn giá.
          </div>
        </section>
        <section className="ops-panel ops-low-stock-panel">
          <div className="ops-panel-heading">
            <div>
              <span className="ops-eyebrow">SỨC KHỎE KÊNH PHÂN PHỐI</span>
              <h2>NPP cần theo dõi tồn</h2>
            </div>
            <span className="ops-live-label">
              <i /> Cập nhật hôm nay
            </span>
          </div>
          {products
            .filter((product) => product.stockHealth !== 'Ổn')
            .map((product) => (
              <div className="ops-stock-alert" key={product.sku}>
                <span
                  className={`ops-stock-mark ${product.stockHealth === 'Cần nhập' ? 'critical' : ''}`}
                >
                  <Boxes size={16} />
                </span>
                <span>
                  <strong>NPP Minh Phát · {product.sku}</strong>
                  <small>{product.sales7d} thùng / 7 ngày gần nhất</small>
                </span>
                <span className="ops-stock-count">
                  <strong>{product.stock}</strong>
                  <small>thùng tồn</small>
                </span>
                <StatusBadge tone={product.stockHealth === 'Cần nhập' ? 'warning' : 'info'}>
                  {product.stockHealth}
                </StatusBadge>
              </div>
            ))}
          <p className="ops-cycle-note">
            <Clock3 size={14} /> NPP chưa đặt theo chu kỳ: chưa có cấu hình chu kỳ đặt hàng.
          </p>
        </section>
      </div>
    </>
  );

  const renderOrders = () => (
    <>
      <div className="ops-page-heading">
        <div>
          <span className="ops-eyebrow">VẬN HÀNH ĐƠN HÀNG</span>
          <h1>Đơn hàng</h1>
          <p>Theo dõi riêng luồng NPP đặt NSX và NPP bán cho điểm bán.</p>
        </div>
        <div className="ops-heading-actions">
          <button className="ops-button secondary" onClick={() => setSortNewest(!sortNewest)}>
            <ArrowUpRight size={16} /> {sortNewest ? 'Mới nhất' : 'Cũ nhất'}
          </button>
          {role !== 'sales' && (
            <button className="ops-button primary" onClick={() => setOrderFilter('pending')}>
              <CheckCheck size={16} /> Xử lý đơn chờ duyệt
            </button>
          )}
        </div>
      </div>
      <section className="ops-panel ops-list-panel">
        <div className="ops-list-toolbar">
          <div className="ops-filter-chips" role="group" aria-label="Lọc loại đơn hàng">
            {(
              [
                ['all', 'Tất cả'],
                ['sell-in', 'Sell-in'],
                ['held', 'Giữ do vượt hạn mức'],
                ['shipping', 'Đang giao'],
                ['received', 'Đã nhận'],
                ['sell-out', 'Sell-out'],
              ] as const
            ).map(([key, label]) => (
              <button
                key={key}
                className={orderFilter === key ? 'selected' : ''}
                onClick={() => setOrderFilter(key)}
              >
                {label}
              </button>
            ))}
          </div>
          <div className="ops-list-tools">
            <label className="ops-inline-search">
              <Search size={16} />
              <input
                value={query}
                onChange={(event) => setQuery(event.target.value)}
                placeholder="Mã đơn, khách hàng, SKU"
                aria-label="Tìm trong danh sách đơn"
              />
            </label>
            <button
              className="ops-button secondary small-button"
              onClick={() => setSortNewest(!sortNewest)}
            >
              <ChevronDown size={15} /> Sắp xếp
            </button>
          </div>
        </div>
        {selected.length > 0 && (
          <div className="ops-bulk-bar">
            <span>
              Đã chọn <strong>{selected.length}</strong> đơn
            </span>
            <button
              className="ops-button primary small-button"
              disabled={!canApprove}
              title={!canApprove ? `Vai trò ${activeRole.title} không có quyền duyệt đơn` : ''}
              onClick={() => {
                const target = orderRows.find(
                  (order) => selected.includes(order.id) && order.status === 'Chờ NSX duyệt',
                );
                if (target) approveOrder(target);
                setSelected([]);
              }}
            >
              {!canApprove && <CircleHelp size={15} />} Duyệt đơn đã chọn
            </button>
            <button onClick={() => setSelected([])}>Bỏ chọn</button>
          </div>
        )}
        <OrdersTable
          rows={visibleOrders}
          onSelect={setSelectedOrder}
          onApprove={approveOrder}
          onRole={role}
          canApprove={canApprove}
          selected={selected}
          onToggle={(id) =>
            setSelected((value) =>
              value.includes(id) ? value.filter((item) => item !== id) : [...value, id],
            )
          }
          onToggleAll={() =>
            setSelected(
              selected.length === visibleOrders.length
                ? []
                : visibleOrders.map((order) => order.id),
            )
          }
        />
        <div className="ops-list-footer">
          <span>
            Hiển thị {visibleOrders.length} trên {orderRows.length} đơn
          </span>
          <span>
            1–{visibleOrders.length} trên {visibleOrders.length}{' '}
            <button aria-label="Trang trước" disabled>
              <ChevronLeft size={15} />
            </button>
            <button aria-label="Trang sau" disabled>
              <ChevronRight size={15} />
            </button>
          </span>
        </div>
        <p className="ops-order-reminder">Đã tạo đơn ≠ đã giao đủ ≠ điểm bán đã nhận đủ</p>
      </section>
      <div className="ops-report-note">
        <ShieldCheck size={16} />
        <span>
          Sell-in là NPP đặt từ NSX. Sell-out là NPP bán cho điểm bán — không phải bán tới người
          tiêu dùng.
        </span>
      </div>
    </>
  );

  const renderStores = () => (
    <>
      <div className="ops-page-heading">
        <div>
          <span className="ops-eyebrow">CRM · HỒ SƠ ĐIỂM BÁN</span>
          <h1>Điểm bán</h1>
          <p>Một hồ sơ chung cho lịch sử mua, chăm sóc và công việc tiếp theo.</p>
        </div>
        <button className="ops-button secondary">
          <CalendarDays size={16} /> Tuyến Quận 3 <ChevronDown size={15} />
        </button>
      </div>
      <div className="ops-store-summary">
        <div>
          <span className="ops-metric-icon teal">
            <UsersRound size={18} />
          </span>
          <span>
            <small>Phủ thị trường hôm nay</small>
            <strong>4/5 điểm bán</strong>
          </span>
        </div>
        <div>
          <span className="ops-metric-icon amber">
            <Clock3 size={18} />
          </span>
          <span>
            <small>Chưa ghé trong tháng</small>
            <strong>1 điểm bán</strong>
          </span>
        </div>
        <div>
          <span className="ops-metric-icon blue">
            <ClipboardList size={18} />
          </span>
          <span>
            <small>Đơn Sell-out tuần</small>
            <strong>91 thùng</strong>
          </span>
        </div>
      </div>
      <section className="ops-panel ops-list-panel">
        <div className="ops-panel-heading ops-store-list-heading">
          <div>
            <span className="ops-eyebrow">HỒ SƠ 360°</span>
            <h2>Khách hàng trên tuyến</h2>
          </div>
          <label className="ops-inline-search">
            <Search size={16} />
            <input placeholder="Tìm điểm bán" aria-label="Tìm điểm bán" />
          </label>
        </div>
        <div className="ops-store-grid">
          {stores.map((store, index) => (
            <article className="ops-store-card" key={store.name}>
              <div className="ops-store-card-top">
                <span className={`ops-store-avatar tone-${index}`}>{store.name.charAt(0)}</span>
                <button
                  className="ops-icon-button small-button"
                  aria-label={`Thao tác ${store.name}`}
                >
                  <MoreHorizontal size={18} />
                </button>
              </div>
              <h3>{store.name}</h3>
              <p>{store.address}</p>
              <div className="ops-store-card-status">
                <StatusBadge
                  tone={
                    store.status === 'Đã ghé'
                      ? 'success'
                      : store.status === 'Chưa ghé'
                        ? 'warning'
                        : 'info'
                  }
                >
                  {store.status}
                </StatusBadge>
                <span>{store.lastVisit}</span>
              </div>
              <div className="ops-store-card-details">
                <span>Người phụ trách</span>
                <strong>
                  {people.sales.name} · {store.contact}
                </strong>
              </div>
              <div className="ops-store-card-footer">
                <span>
                  <ClipboardList size={15} /> Lịch sử đơn hàng
                </span>
                <button
                  aria-label={`Mở hồ sơ ${store.name}`}
                  onClick={() => setToast({ message: `Đang xem hồ sơ ${store.name}` })}
                >
                  <ArrowUpRight size={16} />
                </button>
              </div>
            </article>
          ))}
        </div>
      </section>
    </>
  );

  return (
    <div className={`ops-app ${dark ? 'dark' : ''}`}>
      <Sidebar
        role={role}
        screen={screen}
        onNavigate={openScreen}
        onRoleChange={changeRole}
        mobileOpen={mobileOpen}
        onClose={() => setMobileOpen(false)}
        collapsed={collapsed}
        onToggleCollapse={() => setCollapsed(!collapsed)}
      />
      <main className="ops-main">
        <Header
          role={role}
          screen={screen}
          onSearch={() => setSearchOpen(true)}
          onTheme={() => setDark(!dark)}
          dark={dark}
          onHelp={() => setToast({ message: 'Trung tâm trợ giúp · Hỗ trợ SYNERA' })}
        />
        <div className="ops-content" key={screen}>
          {!loading && loadError ? (
            <div className="ops-error-state" role="alert">
              <span>
                <AlertTriangle size={21} />
              </span>
              <h2>Không tải được dữ liệu</h2>
              <p>Hãy thử lại. Dữ liệu báo cáo chưa thay đổi.</p>
              <button
                className="ops-button primary"
                onClick={() => {
                  setLoadError(false);
                  setLoading(true);
                  window.setTimeout(() => setLoading(false), 350);
                }}
              >
                Thử tải lại
              </button>
            </div>
          ) : loading ? (
            renderLoading()
          ) : (
            <>
              {screen === 'overview' && renderOverview()}
              {screen === 'orders' && renderOrders()}
              {screen === 'stores' && renderStores()}
              {screen === 'organization' && <OrganizationScreen role={role} onToast={setToast} />}
              {screen === 'routes' && <RoutesScreen role={role} onToast={setToast} />}
              {screen === 'catalog' && <CatalogScreen role={role} onToast={setToast} />}
              {screen === 'finance' && <FinanceScreen role={role} onToast={setToast} />}
              {screen === 'tickets' && <SupportScreen role={role} onToast={setToast} />}
              {screen === 'reports' && <ReportScreen role={role} onToast={setToast} />}
              {screen === 'settings' && <SettingsScreen role={role} onToast={setToast} />}
            </>
          )}
          <footer className="ops-footer">
            <span>SYNERA · Suối Việt</span>
            <span>Dữ liệu demo · {getScreenTitle(screen)} · 12/11/2026</span>
          </footer>
        </div>
      </main>
      {selectedOrder && (
        <OrderDrawer
          order={selectedOrder}
          onClose={() => setSelectedOrder(null)}
          onApprove={() => approveOrder(selectedOrder)}
          canApprove={canApprove}
          canApproveOverLimit={canApproveOverLimit}
          role={role}
          onReject={() => {
            setActionReason('');
            setActionDialog({
              title: `Từ chối đơn ${selectedOrder.id}`,
              kind: 'reject',
              detail: 'Vui lòng nhập lý do từ chối. Thông tin này được lưu trong lịch sử xử lý.',
            });
          }}
          onRequestMore={() => {
            setActionReason('');
            setActionDialog({
              title: `Yêu cầu bổ sung · ${selectedOrder.id}`,
              kind: 'request',
              detail: 'Nêu rõ chứng từ hoặc thông tin còn thiếu để đầu mối NPP bổ sung.',
            });
          }}
        />
      )}
      {actionDialog && (
        <div className="ops-command-backdrop" onMouseDown={() => setActionDialog(null)}>
          <section
            className="ops-action-dialog"
            role="dialog"
            aria-modal="true"
            aria-labelledby="ops-action-title"
            onMouseDown={(event) => event.stopPropagation()}
          >
            <div className="ops-drawer-header">
              <div>
                <span className="ops-eyebrow">XỬ LÝ ĐƠN HÀNG</span>
                <h2 id="ops-action-title">{actionDialog.title}</h2>
              </div>
              <button
                className="ops-icon-button"
                onClick={() => setActionDialog(null)}
                aria-label="Đóng"
              >
                <X size={18} />
              </button>
            </div>
            <p>{actionDialog.detail}</p>
            <label className="ops-dialog-label">
              Lý do / nội dung yêu cầu
              <textarea
                autoFocus
                value={actionReason}
                onChange={(event) => setActionReason(event.target.value)}
                rows={4}
                placeholder="Nhập nội dung để tiếp tục..."
              />
            </label>
            <div className="ops-dialog-actions">
              <button className="ops-button secondary" onClick={() => setActionDialog(null)}>
                Hủy
              </button>
              <button
                className="ops-button primary"
                disabled={!actionReason.trim()}
                onClick={submitOrderAction}
              >
                {actionDialog.kind === 'reject' ? 'Xác nhận từ chối' : 'Gửi yêu cầu'}
              </button>
            </div>
          </section>
        </div>
      )}
      {searchOpen && (
        <div className="ops-command-backdrop" onMouseDown={() => setSearchOpen(false)}>
          <section
            className="ops-command"
            role="dialog"
            aria-modal="true"
            aria-label="Tìm kiếm nhanh"
            onMouseDown={(event) => event.stopPropagation()}
          >
            <label>
              <Search size={19} />
              <input
                autoFocus
                value={searchQuery}
                onChange={(event) => setSearchQuery(event.target.value)}
                placeholder="Tìm cửa hàng, mã đơn, SKU..."
              />
              <kbd>ESC</kbd>
              <button aria-label="Đóng tìm kiếm" onClick={() => setSearchOpen(false)}>
                <X size={16} />
              </button>
            </label>
            <div className="ops-command-results">
              {searchQuery.trim() && searchResults.length === 0 && (
                <p>Không tìm thấy kết quả phù hợp.</p>
              )}
              {!searchQuery.trim() && <p>Bắt đầu bằng tên điểm bán, mã đơn hoặc mã SKU.</p>}
              {searchResults.map((result) => (
                <button
                  key={result.key}
                  onClick={() => {
                    if (result.kind === 'order') {
                      setSelectedOrder(result.order);
                      openScreen('orders');
                    }
                    if (result.kind === 'store') openScreen('stores');
                    if (result.kind === 'product') openScreen('catalog');
                    setSearchOpen(false);
                  }}
                >
                  <span>
                    {result.kind === 'order' ? (
                      <ClipboardList size={16} />
                    ) : result.kind === 'store' ? (
                      <Store size={16} />
                    ) : (
                      <Boxes size={16} />
                    )}
                  </span>
                  <strong>{result.label}</strong>
                  <small>{result.detail}</small>
                  <ArrowUpRight size={15} />
                </button>
              ))}
            </div>
            <div className="ops-command-foot">
              <span>
                <kbd>↑</kbd>
                <kbd>↓</kbd> Di chuyển
              </span>
              <span>
                <kbd>↵</kbd> Mở kết quả
              </span>
              <span>
                <Command size={13} /> tìm kiếm SYNERA
              </span>
            </div>
          </section>
        </div>
      )}
      {toast && (
        <div className="ops-toast" role="status">
          <CircleCheck size={18} />
          <span>{toast.message}</span>
          {toast.undo && (
            <button
              onClick={() => {
                toast.undo?.();
                setToast(null);
              }}
            >
              Hoàn tác
            </button>
          )}
          <button aria-label="Đóng thông báo" onClick={() => setToast(null)}>
            <X size={15} />
          </button>
        </div>
      )}
    </div>
  );
}
