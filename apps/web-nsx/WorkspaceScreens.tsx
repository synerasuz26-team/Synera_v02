import { useState } from 'react';
import {
  AlertTriangle,
  ArrowDownRight,
  ArrowRight,
  ArrowUpRight,
  CalendarDays,
  Check,
  ChevronDown,
  ChevronRight,
  CircleHelp,
  Clock3,
  Download,
  Eye,
  FileText,
  GripVertical,
  HandCoins,
  MapPin,
  MoreHorizontal,
  Package,
  Plus,
  Save,
  Search,
  ShieldCheck,
  TicketCheck,
  Truck,
  UserRound,
  UsersRound,
  X,
} from 'lucide-react';
import {
  actionLog,
  closeSchedule,
  dailySellIn30d,
  dailySellOut30d,
  financeRows,
  overview,
  people,
  products,
  routes,
  salesRegions,
  stores,
  tickets,
  userDirectory,
} from '../../packages/mock-data';
import type { Role, ToastMessage } from './types';

type Props = { role: Role; onToast: (toast: ToastMessage) => void };
const fmt = new Intl.NumberFormat('vi-VN');
const money = (value: number) => `${fmt.format(value)}đ`;
const roleName: Record<Role, string> = {
  leader: 'Lãnh đạo',
  rsm: 'RSM Miền Nam',
  asm: 'ASM TP.HCM',
  supervisor: 'Sup · Quận 3',
  sales: 'Sales',
  intake: 'Tiếp nhận đơn',
  support: 'CSKH',
  trade: 'Trade marketing',
  accounting: 'Kế toán',
  admin: 'Admin',
};

function Badge({ children, tone = 'neutral' }: { children: React.ReactNode; tone?: string }) {
  return <span className={`ops-badge ${tone}`}>{children}</span>;
}

function PageHeading({
  eyebrow,
  title,
  description,
  action,
}: {
  eyebrow: string;
  title: string;
  description: string;
  action?: React.ReactNode;
}) {
  return (
    <div className="ops-page-heading">
      <div>
        <span className="ops-eyebrow">{eyebrow}</span>
        <h1>{title}</h1>
        <p>{description}</p>
      </div>
      {action && <div className="ops-heading-actions">{action}</div>}
    </div>
  );
}

function PanelHeading({
  eyebrow,
  title,
  action,
}: {
  eyebrow: string;
  title: string;
  action?: React.ReactNode;
}) {
  return (
    <div className="ops-panel-heading">
      <div>
        <span className="ops-eyebrow">{eyebrow}</span>
        <h2>{title}</h2>
      </div>
      {action}
    </div>
  );
}

export function OrganizationScreen({ role, onToast }: Props) {
  const [selectedNode, setSelectedNode] = useState('TP.HCM');
  const [expanded, setExpanded] = useState<string[]>(['Miền Bắc', 'Miền Trung', 'Miền Nam']);
  const [tree, setTree] = useState(salesRegions);
  const canEdit = role === 'leader' || role === 'rsm' || role === 'admin';
  const selectedRegion = tree
    .flatMap((region) => region.regions)
    .find((region) => region.name === selectedNode);
  const selectedDivision = tree.find((region) => region.name === selectedNode);
  const owner = selectedRegion?.owner ?? people.rsm.name;
  const salesCount =
    selectedRegion?.sales ??
    selectedDivision?.regions.reduce((sum, item) => sum + item.sales, 0) ??
    0;
  const outletCount =
    selectedRegion?.outlets ??
    selectedDivision?.regions.reduce((sum, item) => sum + item.outlets, 0) ??
    0;
  const isExpanded = (name: string) => expanded.includes(name);
  const toggle = (name: string) =>
    setExpanded((items) =>
      items.includes(name) ? items.filter((item) => item !== name) : [...items, name],
    );
  const moveRegion = (source: string, target: string) => {
    const sourceDivision = tree.find((division) =>
      division.regions.some((region) => region.name === source),
    );
    const targetDivision =
      tree.find((division) => division.name === target) ??
      tree.find((division) => division.regions.some((region) => region.name === target));
    if (!sourceDivision || !targetDivision || sourceDivision.name === targetDivision.name) return;
    const region = sourceDivision.regions.find((item) => item.name === source);
    if (!region) return;
    setTree((current) =>
      current.map((division) => {
        if (division.name === sourceDivision.name) {
          return { ...division, regions: division.regions.filter((item) => item.name !== source) };
        }
        if (division.name === targetDivision.name) {
          return { ...division, regions: [...division.regions, region] };
        }
        return division;
      }),
    );
    setSelectedNode(source);
    setExpanded((items) => [...new Set([...items, targetDivision.name])]);
    onToast({
      message: `Đã chuyển ${source} sang ${targetDivision.name}; lịch sử phân công được giữ lại.`,
    });
  };
  return (
    <>
      <PageHeading
        eyebrow="TỔ CHỨC BÁN HÀNG"
        title="Ai phụ trách thị trường nào?"
        description="Quản lý miền, vùng, khu vực và vùng bán hàng — gắn người phụ trách với khách hàng và tuyến đi bán."
        action={
          <button
            className="ops-button primary"
            disabled={!canEdit}
            title={!canEdit ? `Vai trò ${roleName[role]} không có quyền sửa sơ đồ` : ''}
            onClick={() => onToast({ message: 'Chọn vị trí trong cây để thêm khu vực' })}
          >
            <Plus size={16} /> Thêm khu vực
          </button>
        }
      />
      <div className="ops-org-layout">
        <section className="ops-panel ops-org-tree-panel">
          <PanelHeading
            eyebrow="CÂY THỊ TRƯỜNG"
            title="Cơ cấu vùng bán hàng"
            action={
              <button className="ops-icon-button small-button" aria-label="Thêm thao tác">
                <MoreHorizontal size={17} />
              </button>
            }
          />
          <div className="ops-org-tree" role="tree">
            {tree.map((division) => (
              <div className="ops-org-division" key={division.name}>
                <div
                  className={`ops-org-node division ${selectedNode === division.name ? 'selected' : ''}`}
                  role="treeitem"
                  aria-expanded={isExpanded(division.name)}
                  onClick={() => {
                    toggle(division.name);
                    setSelectedNode(division.name);
                  }}
                  onDragOver={(event) => event.preventDefault()}
                  onDrop={(event) => {
                    event.preventDefault();
                    moveRegion(event.dataTransfer.getData('text/plain'), division.name);
                  }}
                >
                  <button
                    aria-label={`${isExpanded(division.name) ? 'Thu gọn' : 'Mở rộng'} ${division.name}`}
                  >
                    <ChevronRight
                      size={16}
                      className={isExpanded(division.name) ? 'expanded' : ''}
                    />
                  </button>
                  <span className="ops-org-icon">
                    <UsersRound size={16} />
                  </span>
                  <strong>{division.name}</strong>
                  <span className="ops-org-count">{division.regions.length} vùng</span>
                  <GripVertical size={15} className="ops-grip" />
                </div>
                {isExpanded(division.name) && (
                  <div className="ops-org-children">
                    {division.regions.map((region) => (
                      <button
                        className={`ops-org-node region ${selectedNode === region.name ? 'selected' : ''}`}
                        key={region.name}
                        role="treeitem"
                        onClick={() => setSelectedNode(region.name)}
                        draggable={canEdit}
                        onDragStart={(event) =>
                          event.dataTransfer.setData('text/plain', region.name)
                        }
                        onDragOver={(event) => event.preventDefault()}
                        onDrop={(event) => {
                          event.preventDefault();
                          moveRegion(event.dataTransfer.getData('text/plain'), region.name);
                        }}
                      >
                        <span className="ops-org-branch" />
                        <span className="ops-org-icon">
                          <MapPin size={15} />
                        </span>
                        <span>{region.name}</span>
                        <span className="ops-org-count">
                          {region.outlets.toLocaleString('vi-VN')} điểm
                        </span>
                        <GripVertical size={15} className="ops-grip" />
                      </button>
                    ))}
                  </div>
                )}
              </div>
            ))}
          </div>
          <div className="ops-org-legend">
            <GripVertical size={15} /> Kéo thả để di chuyển vùng · thay đổi sẽ cần xác nhận
          </div>
        </section>
        <section className="ops-panel ops-org-detail-panel">
          <div className="ops-org-detail-title">
            <span className="ops-org-detail-icon">
              <MapPin size={20} />
            </span>
            <span>
              <span className="ops-eyebrow">CHI TIẾT PHẠM VI</span>
              <h2>{selectedNode}</h2>
            </span>
            <button className="ops-icon-button small-button" aria-label="Tùy chọn khu vực">
              <MoreHorizontal size={18} />
            </button>
          </div>
          <div className="ops-org-breadcrumb">
            Suối Việt <ChevronRight size={14} /> Miền Nam <ChevronRight size={14} /> {selectedNode}
          </div>
          <div className="ops-org-owner">
            <span className="ops-avatar">{owner.slice(0, 1)}</span>
            <span>
              <small>Người phụ trách</small>
              <strong>{owner}</strong>
            </span>
            <button
              className="ops-text-link"
              onClick={() => onToast({ message: 'Đã mở danh sách phân công' })}
            >
              Đổi người phụ trách
            </button>
          </div>
          <div className="ops-org-stats">
            <div>
              <small>Nhân viên Sales</small>
              <strong>{salesCount}</strong>
            </div>
            <div>
              <small>Điểm bán</small>
              <strong>{outletCount.toLocaleString('vi-VN')}</strong>
            </div>
            <div>
              <small>Vùng bán hàng</small>
              <strong>{selectedRegion?.territories.join(', ') ?? 'Theo vùng trực thuộc'}</strong>
            </div>
          </div>
          <div className="ops-org-responsibilities">
            <h3>Phạm vi phụ trách</h3>
            <p>
              <Check size={15} /> Khách hàng và NPP trong khu vực
            </p>
            <p>
              <Check size={15} /> Tuyến bán hàng và lịch MCP
            </p>
            <p>
              <Check size={15} /> Chỉ tiêu sell-in và sell-out
            </p>
          </div>
          <div className="ops-org-actions">
            <button
              className="ops-button secondary"
              disabled={!canEdit}
              onClick={() => onToast({ message: `Chỉnh sửa ${selectedNode}` })}
            >
              Chỉnh sửa
            </button>
            <button
              className="ops-button secondary"
              disabled={!canEdit}
              onClick={() => onToast({ message: `Chọn vị trí mới để chuyển ${selectedNode}` })}
            >
              Di chuyển
            </button>
          </div>
        </section>
      </div>
    </>
  );
}

export function RoutesScreen({ role, onToast }: Props) {
  const [selectedRoute, setSelectedRoute] = useState(routes[0].code);
  const [checkedVisits, setCheckedVisits] = useState<string[]>([
    'Tạp hóa Lan-0-0',
    'Quán Bình-0-1',
  ]);
  const [showDelegate, setShowDelegate] = useState(false);
  const [delegateName, setDelegateName] = useState('Nguyễn Minh');
  const [delegateVisit, setDelegateVisit] = useState('Cafe 24 · ghé điểm bán');
  const canAssign = ['leader', 'rsm', 'asm', 'admin'].includes(role);
  const route = routes.find((item) => item.code === selectedRoute) ?? routes[0];
  const days = ['T2', 'T3', 'T4', 'T5', 'T6', 'T7', 'CN'];
  const visitStores = stores;
  const toggleVisit = (key: string) =>
    setCheckedVisits((items) =>
      items.includes(key) ? items.filter((item) => item !== key) : [...items, key],
    );
  const moveVisit = (source: string, target: string) =>
    setCheckedVisits((items) => [...items.filter((item) => item !== source), target]);
  return (
    <>
      <PageHeading
        eyebrow="VÙNG, TUYẾN & MCP"
        title="Quản lý tuyến bán hàng"
        description="Lịch ghé điểm bán, người phụ trách và công việc dở dang trên cùng một tuyến."
        action={
          <button
            className="ops-button primary"
            disabled={!canAssign}
            title={!canAssign ? `Vai trò ${roleName[role]} không thể phân tuyến` : ''}
            onClick={() => onToast({ message: 'Tạo tuyến mới' })}
          >
            <Plus size={16} /> Tạo tuyến
          </button>
        }
      />
      <div className="ops-route-workspace">
        <section className="ops-panel ops-route-list">
          <PanelHeading eyebrow="DANH SÁCH TUYẾN" title={`${routes.length} tuyến`} />
          <label className="ops-inline-search">
            <Search size={16} />
            <input placeholder="Tìm mã tuyến, Sales" aria-label="Tìm tuyến" />
          </label>
          <div className="ops-route-items">
            {routes.map((item) => (
              <button
                key={item.code}
                className={`ops-route-item ${item.code === selectedRoute ? 'selected' : ''}`}
                onClick={() => setSelectedRoute(item.code)}
              >
                <span className="ops-route-list-icon">
                  <MapPin size={17} />
                </span>
                <span>
                  <strong>
                    {item.code} · {item.name}
                  </strong>
                  <small>
                    {item.sales} · {item.region}
                  </small>
                </span>
                <span className="ops-route-outlets">
                  {item.outlets}
                  <small>outlet</small>
                </span>
              </button>
            ))}
          </div>
        </section>
        <section className="ops-panel ops-route-detail">
          <div className="ops-route-detail-header">
            <div>
              <span className="ops-eyebrow">
                {route.code} · {route.region}
              </span>
              <h2>{route.name}</h2>
              <p>
                <UserRound size={15} /> {route.sales} · {route.outlets} điểm bán
              </p>
            </div>
            <button
              className="ops-button secondary"
              disabled={!canAssign}
              title={!canAssign ? `Vai trò ${roleName[role]} không thể đổi người đi tuyến` : ''}
              onClick={() => setShowDelegate(!showDelegate)}
            >
              <UsersRound size={16} /> Chuyển người đi thay
            </button>
          </div>
          {showDelegate && (
            <div className="ops-delegate-form">
              <label>
                Người đi thay
                <select
                  value={delegateName}
                  onChange={(event) => setDelegateName(event.target.value)}
                >
                  <option>Nguyễn Minh</option>
                  <option>Trần Hà</option>
                  <option>Nguyễn An</option>
                </select>
              </label>
              <label>
                Lượt ghé / việc dở
                <select
                  value={delegateVisit}
                  onChange={(event) => setDelegateVisit(event.target.value)}
                >
                  <option>Cafe 24 · ghé điểm bán</option>
                  <option>Quán Bình · xử lý giao thiếu TK-331</option>
                  <option>Tạp hóa Lan · hẹn chăm sóc</option>
                </select>
              </label>
              <span>
                <ShieldCheck size={14} /> Giữ lịch sử tuyến và người phụ trách ban đầu.
              </span>
              <button
                className="ops-button primary small-button"
                disabled={!canAssign}
                onClick={() => {
                  setShowDelegate(false);
                  onToast({
                    message: `Đã giao ${delegateVisit} cho ${delegateName}; lịch sử vẫn được lưu.`,
                  });
                }}
              >
                Xác nhận chuyển
              </button>
            </div>
          )}
          <div className="ops-route-tabs">
            <button className="selected">Lịch MCP</button>
            <button>Danh sách điểm bán</button>
            <button>Bản đồ</button>
          </div>
          <div className="ops-mcp-warning">
            <AlertTriangle size={15} /> Cafe 24 chưa được ghé trong tháng. Không phát hiện trùng
            tuyến trong phạm vi đang xem.
          </div>
          <div className="ops-mcp-scroll">
            <table className="ops-mcp-table">
              <thead>
                <tr>
                  <th rowSpan={2}>Điểm bán</th>
                  {[1, 2, 3, 4].map((week) => (
                    <th colSpan={7} key={week}>
                      Tuần {week}
                    </th>
                  ))}
                </tr>
                <tr>
                  {[0, 1, 2, 3].flatMap((week) =>
                    days.map((day) => <th key={`${week}-${day}`}>{day}</th>),
                  )}
                </tr>
              </thead>
              <tbody>
                {visitStores.map((store) => (
                  <tr key={store.name}>
                    <td>
                      <strong>{store.name}</strong>
                      <small>{store.address}</small>
                    </td>
                    {Array.from({ length: 28 }, (_, cell) => {
                      const week = Math.floor(cell / 7);
                      const day = cell % 7;
                      const key = `${store.name}-${week}-${day}`;
                      const checked = checkedVisits.includes(key);
                      return (
                        <td
                          key={key}
                          onDragOver={(event) => event.preventDefault()}
                          onDrop={(event) => {
                            const source = event.dataTransfer.getData('text/plain');
                            if (source && source !== key) moveVisit(source, key);
                          }}
                        >
                          <button
                            className={`ops-mcp-cell ${checked ? 'checked' : ''}`}
                            aria-label={`${store.name}, tuần ${week + 1}, ${days[day]}: ${checked ? 'đã lên lịch' : 'chưa lên lịch'}`}
                            onClick={() => toggleVisit(key)}
                            draggable={checked}
                            onDragStart={(event) => event.dataTransfer.setData('text/plain', key)}
                          >
                            {checked ? <Check size={12} /> : ''}
                          </button>
                        </td>
                      );
                    })}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="ops-route-map">
            <div className="ops-map-surface">
              <span className="ops-map-road road-a" />
              <span className="ops-map-road road-b" />
              <span className="ops-map-water" />
              {visitStores.map((store, index) => (
                <span className={`ops-map-pin pin-${index}`} key={store.name} title={store.name}>
                  <MapPin size={19} fill="currentColor" />
                </span>
              ))}
              <span className="ops-map-label">QUẬN 3 · TP.HCM</span>
            </div>
            <div className="ops-map-caption">
              <MapPin size={15} /> Bản đồ minh họa ·{' '}
              {visitStores.map((store) => store.name).join(' · ')}
            </div>
          </div>
        </section>
      </div>
    </>
  );
}

export function CatalogScreen({ role, onToast }: Props) {
  const [tab, setTab] = useState<'sku' | 'prices' | 'promotions' | 'vouchers'>('sku');
  const [showPriceForm, setShowPriceForm] = useState(false);
  const canManage = ['leader', 'rsm', 'trade', 'admin'].includes(role);
  const approverAllowed = !['leader', 'rsm'].includes(role);
  const tabs = [
    ['sku', 'SKU'],
    ['prices', 'Bảng giá'],
    ['promotions', 'CTKM'],
    ['vouchers', 'Voucher'],
  ] as const;
  return (
    <>
      <PageHeading
        eyebrow="DANH MỤC BÁN HÀNG"
        title="Sản phẩm, giá & ưu đãi"
        description="Giá được áp dụng theo bảng hiệu lực; không nhập tay đơn giá trong đơn hàng."
        action={
          <button
            className="ops-button primary"
            disabled={!canManage}
            onClick={() => setShowPriceForm(!showPriceForm)}
          >
            <Plus size={16} /> {showPriceForm ? 'Đóng biểu mẫu' : 'Tạo bảng giá'}
          </button>
        }
      />
      <section className="ops-panel ops-list-panel">
        <div className="ops-section-tabs" role="tablist">
          {tabs.map(([key, label]) => (
            <button
              role="tab"
              aria-selected={tab === key}
              className={tab === key ? 'selected' : ''}
              key={key}
              onClick={() => setTab(key)}
            >
              {label}
            </button>
          ))}
        </div>
        {tab === 'sku' && (
          <>
            <div className="ops-list-toolbar">
              <div className="ops-filter-chips">
                <button className="selected">Tất cả SKU</button>
                <button>Đang kinh doanh</button>
                <button>Ngừng bán</button>
              </div>
              <label className="ops-inline-search">
                <Search size={16} />
                <input placeholder="Tìm SKU, tên sản phẩm" />
              </label>
            </div>
            <div className="ops-table-scroll">
              <table className="ops-table">
                <thead>
                  <tr>
                    <th>SKU</th>
                    <th>Sản phẩm</th>
                    <th>Đơn vị tính</th>
                    <th>Giá Sell-in</th>
                    <th>Giá Sell-out</th>
                    <th>CTKM</th>
                    <th>Hiệu lực</th>
                  </tr>
                </thead>
                <tbody>
                  {products.map((product) => (
                    <tr key={product.sku}>
                      <td>
                        <strong>{product.sku}</strong>
                      </td>
                      <td>{product.name}</td>
                      <td>{product.pack}</td>
                      <td>{money(product.sellIn)}</td>
                      <td>{money(product.sellOut)}</td>
                      <td>{product.promotion || '—'}</td>
                      <td>
                        <Badge tone="success">Đến {overview.priceValidThrough}</Badge>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </>
        )}
        {tab === 'prices' && (
          <div className="ops-price-content">
            {showPriceForm && (
              <div className="ops-price-form">
                <div className="ops-price-form-heading">
                  <div>
                    <span className="ops-eyebrow">TẠO BẢNG GIÁ</span>
                    <h3>Thông tin chung</h3>
                  </div>
                  <button
                    className="ops-icon-button"
                    onClick={() => setShowPriceForm(false)}
                    aria-label="Đóng biểu mẫu"
                  >
                    <X size={17} />
                  </button>
                </div>
                <div className="ops-form-grid">
                  <label>
                    Tên bảng giá
                    <input defaultValue="Bảng giá Suối Việt tháng 11" />
                  </label>
                  <label>
                    Loại bảng giá
                    <select defaultValue="Cơ sở">
                      <option>Cơ sở</option>
                      <option>Theo phạm vi</option>
                      <option>Theo khách</option>
                      <option>Theo NPP</option>
                    </select>
                  </label>
                  <label>
                    Luồng giá
                    <select defaultValue="Sell-in">
                      <option>Sell-in</option>
                      <option>Sell-out</option>
                    </select>
                  </label>
                  <label>
                    Hiệu lực đến
                    <input defaultValue="30/11/2026" />
                  </label>
                </div>
                <div className="ops-price-form-heading">
                  <div>
                    <h3>Đối tượng áp dụng</h3>
                    <p>Chọn phạm vi vùng, NPP hoặc khách hàng.</p>
                  </div>
                </div>
                <div className="ops-form-grid">
                  <label>
                    Phạm vi
                    <select defaultValue="Toàn quốc">
                      <option>Toàn quốc</option>
                      <option>Miền Nam</option>
                      <option>TP.HCM</option>
                    </select>
                  </label>
                  <label>
                    Nhà phân phối
                    <select defaultValue="Tất cả NPP">
                      <option>Tất cả NPP</option>
                      <option>Minh Phát</option>
                      <option>An Khang</option>
                    </select>
                  </label>
                </div>
                <div className="ops-price-form-heading">
                  <h3>SKU × đơn vị tính</h3>
                  <Badge tone="info">Giá áp dụng tự động</Badge>
                </div>
                {products.map((product) => (
                  <div className="ops-form-sku-row" key={product.sku}>
                    <strong>
                      {product.sku} · {product.name}
                    </strong>
                    <span>{product.pack}</span>
                    <strong>{money(product.sellIn)}</strong>
                  </div>
                ))}
                <div className="ops-price-form-actions">
                  <button
                    className="ops-button secondary"
                    onClick={() => onToast({ message: 'Đã lưu bảng giá ở trạng thái Nháp' })}
                  >
                    <Save size={15} /> Lưu nháp
                  </button>
                  <button
                    className="ops-button primary"
                    onClick={() => onToast({ message: 'Bảng giá đã chuyển sang Chờ duyệt' })}
                  >
                    Gửi duyệt <ArrowRight size={15} />
                  </button>
                </div>
              </div>
            )}
            <div className="ops-price-flow">
              <span className="ops-flow-step done">
                <Check size={14} /> Nháp
              </span>
              <ChevronRight size={15} />
              <span className="ops-flow-step active">Chờ duyệt</span>
              <ChevronRight size={15} />
              <span className="ops-flow-step">Hiệu lực</span>
              <small>Không tự duyệt bảng giá do mình soạn.</small>
            </div>
            <div className="ops-price-list-row">
              <span>
                <strong>Bảng giá Suối Việt · 11/2026</strong>
                <small>
                  Cơ sở · Sell-in + Sell-out · Toàn quốc · Hiệu lực đến {overview.priceValidThrough}
                </small>
              </span>
              <Badge tone="success">Hiệu lực</Badge>
              <button
                className="ops-button secondary small-button"
                onClick={() => setShowPriceForm(true)}
              >
                <Eye size={14} /> Xem
              </button>
            </div>
            <div className="ops-price-list-row">
              <span>
                <strong>Bảng giá khu vực TP.HCM · bản dự thảo</strong>
                <small>Theo phạm vi · Sell-out · Soạn bởi {people.rsm.name}</small>
              </span>
              <Badge tone="warning">Chờ duyệt</Badge>
              {approverAllowed ? (
                <button
                  className="ops-button primary small-button"
                  onClick={() => onToast({ message: 'Đã duyệt bảng giá khu vực' })}
                >
                  Duyệt
                </button>
              ) : (
                <span className="ops-disabled-hint" title="Người soạn không thể tự duyệt bảng giá">
                  Đang chờ người duyệt
                </span>
              )}
            </div>
          </div>
        )}
        {tab === 'promotions' && (
          <div className="ops-promo-grid">
            {products
              .filter((product) => product.promotion)
              .map((product) => (
                <article className="ops-promo-card" key={product.sku}>
                  <span className="ops-promo-icon">%</span>
                  <Badge tone="info">Đang hiệu lực</Badge>
                  <h3>{product.promotion}</h3>
                  <p>
                    {product.sku} · {product.name}
                  </p>
                  <small>Điều kiện áp dụng theo bảng giá đến {overview.priceValidThrough}</small>
                  <div className="ops-promo-steps">
                    <span className="selected">Thông tin chung</span>
                    <span>Điều kiện đạt thưởng</span>
                    <span>Cấu hình</span>
                    <span>Nhóm sản phẩm</span>
                  </div>
                </article>
              ))}
            <button
              className="ops-add-card"
              onClick={() => onToast({ message: 'Tạo chương trình khuyến mãi' })}
            >
              <Plus size={20} /> Tạo CTKM
            </button>
          </div>
        )}
        {tab === 'vouchers' && (
          <div className="ops-voucher-panel">
            <div>
              <span className="ops-voucher-icon">V</span>
              <span>
                <strong>Voucher kênh phân phối</strong>
                <small>Phạm vi: dùng chung · Theo bảng giá hiệu lực</small>
              </span>
              <Badge tone="neutral">Chưa phát hành</Badge>
            </div>
            <div>
              <span className="ops-voucher-icon personal">C</span>
              <span>
                <strong>Voucher khách hàng cá nhân</strong>
                <small>Phạm vi: theo khách hàng · Có lịch sử sử dụng</small>
              </span>
              <Badge tone="neutral">Chưa phát hành</Badge>
            </div>
            <button
              className="ops-button secondary"
              onClick={() => onToast({ message: 'Tạo voucher dùng chung hoặc cá nhân' })}
            >
              <Plus size={15} /> Tạo voucher
            </button>
          </div>
        )}
      </section>
      <div className="ops-report-note">
        <ShieldCheck size={16} />
        <span>
          Giá bán tự động áp theo bảng giá hiệu lực. Hàng tặng hiển thị riêng và không tính vào số
          thu.
        </span>
      </div>
    </>
  );
}

export function FinanceScreen({ role, onToast }: Props) {
  const [tab, setTab] = useState<'balances' | 'exceptions' | 'closing' | 'adjustments' | 'ledger'>(
    'balances',
  );
  const [reason, setReason] = useState('');
  const [approver, setApprover] = useState('Lê Quang');
  const [adjustmentSubmitted, setAdjustmentSubmitted] = useState(false);
  const canWrite = role === 'accounting' || role === 'rsm';
  const creator = role === 'accounting' ? 'Thu Trang' : 'Lê Quang';
  const tabs = [
    ['balances', 'Hạn mức & công nợ'],
    ['exceptions', 'Ngoại lệ giao nhận'],
    ['closing', 'Lịch chốt'],
    ['adjustments', 'Phiếu điều chỉnh'],
    ['ledger', 'Sổ đã chốt'],
  ] as const;
  const onSubmit = () => {
    if (!reason.trim()) return;
    if (approver === creator) return;
    setAdjustmentSubmitted(true);
    onToast({ message: 'Đã gửi phiếu điều chỉnh chờ người duyệt độc lập' });
  };
  return (
    <>
      <PageHeading
        eyebrow="KẾ TOÁN · CÔNG NỢ & ĐỐI SOÁT"
        title="Công nợ & đối soát"
        description="Theo dõi hạn mức, ngoại lệ đặt–xuất–nhận và sổ đã chốt theo lịch."
        action={
          <button className="ops-button secondary">
            <CalendarDays size={16} /> Tháng 11/2026 <ChevronDown size={15} />
          </button>
        }
      />
      <section className="ops-panel ops-list-panel">
        <div className="ops-section-tabs" role="tablist">
          {tabs.map(([key, label]) => (
            <button
              role="tab"
              aria-selected={tab === key}
              className={tab === key ? 'selected' : ''}
              key={key}
              onClick={() => setTab(key)}
            >
              {label}
            </button>
          ))}
        </div>
        {tab === 'balances' && (
          <>
            <div className="ops-finance-metrics">
              <div>
                <small>Tổng công nợ theo dõi</small>
                <strong>{money(overview.creditTracked)}</strong>
              </div>
              <div>
                <small>Đơn chờ kiểm tra hạn mức</small>
                <strong>{overview.sellInWaiting} đơn</strong>
              </div>
              <div>
                <small>Phiếu điều chỉnh mở</small>
                <strong>0 phiếu</strong>
              </div>
            </div>
            <div className="ops-table-scroll">
              <table className="ops-table">
                <thead>
                  <tr>
                    <th>NPP</th>
                    <th>Công nợ hiện tại</th>
                    <th>Hạn mức</th>
                    <th>Còn lại</th>
                    <th>Trạng thái</th>
                    <th />
                  </tr>
                </thead>
                <tbody>
                  {financeRows.map((row) => (
                    <tr key={row.distributor}>
                      <td>
                        <strong>{row.distributor}</strong>
                        <span className="ops-table-secondary">
                          Phụ trách:{' '}
                          {row.distributor === 'Minh Phát' ? people.rsm.name : 'Miền Nam'}
                        </span>
                      </td>
                      <td>{row.debt == null ? 'Chưa có dữ liệu' : money(row.debt)}</td>
                      <td>{row.limit == null ? 'Chưa cấu hình' : money(row.limit)}</td>
                      <td>{row.remaining == null ? '—' : row.remaining}</td>
                      <td>
                        <Badge tone={row.status === 'Theo dõi' ? 'warning' : 'neutral'}>
                          {row.status}
                        </Badge>
                      </td>
                      <td>
                        <button
                          className="ops-text-link"
                          onClick={() => onToast({ message: `Mở công nợ ${row.distributor}` })}
                        >
                          Chi tiết <ArrowRight size={14} />
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </>
        )}
        {tab === 'exceptions' && (
          <div className="ops-exception-list">
            <div className="ops-exception-banner">
              <AlertTriangle size={17} /> Chênh lệch đặt – xuất – nhận cần đối soát.
            </div>
            {[
              {
                id: 'SO-24076',
                buyer: 'Quán Bình',
                ordered: 9,
                shipped: 7,
                received: 7,
                issue: 'Giao thiếu 2 thùng NS150',
              },
            ].map((row) => (
              <article className="ops-exception-row" key={row.id}>
                <span className="ops-exception-mark">
                  <Truck size={18} />
                </span>
                <span>
                  <strong>
                    {row.id} · {row.buyer}
                  </strong>
                  <small>{row.issue} · Hạn bù 13/11</small>
                </span>
                <span className="ops-exception-qty">
                  <small>Đặt</small>
                  <strong>{row.ordered}</strong>
                </span>
                <span className="ops-exception-qty">
                  <small>Xuất</small>
                  <strong>{row.shipped}</strong>
                </span>
                <span className="ops-exception-qty">
                  <small>Nhận</small>
                  <strong>{row.received}</strong>
                </span>
                <button
                  className="ops-button secondary small-button"
                  onClick={() => onToast({ message: `Đã ghi nhận xử lý ngoại lệ ${row.id}` })}
                >
                  Xử lý
                </button>
              </article>
            ))}
          </div>
        )}
        {tab === 'closing' && (
          <div className="ops-closing-list">
            {closeSchedule.map((item) => (
              <article key={item.name} className="ops-closing-row">
                <span className="ops-closing-icon">
                  <CalendarDays size={18} />
                </span>
                <span>
                  <strong>{item.name}</strong>
                  <small>
                    {item.cycle} · Lần chốt tiếp theo {item.next}
                  </small>
                </span>
                <Badge tone="success">{item.status}</Badge>
                <button
                  className="ops-icon-button small-button"
                  aria-label={`Cấu hình ${item.name}`}
                  onClick={() => onToast({ message: `Cấu hình ${item.name}` })}
                >
                  <MoreHorizontal size={17} />
                </button>
              </article>
            ))}
          </div>
        )}
        {tab === 'adjustments' && (
          <div className="ops-adjustment-form">
            <div className="ops-locked-note">
              <ShieldCheck size={17} />
              <span>
                Sổ đã chốt chỉ thay đổi bằng phiếu điều chỉnh. Người tạo không thể tự duyệt phiếu
                của mình.
              </span>
            </div>
            <div className="ops-form-grid">
              <label>
                Mã đơn / kỳ đối soát
                <input placeholder="Ví dụ: SO-24076" />
              </label>
              <label>
                Người duyệt
                <select value={approver} onChange={(event) => setApprover(event.target.value)}>
                  <option>Lê Quang</option>
                  <option>Trần Mỹ</option>
                  <option>Thu Trang</option>
                </select>
              </label>
              <label className="full">
                Lý do điều chỉnh
                <textarea
                  value={reason}
                  onChange={(event) => setReason(event.target.value)}
                  placeholder="Mô tả nguyên nhân và chứng từ liên quan"
                  rows={3}
                />
              </label>
            </div>
            <button
              className="ops-button primary"
              onClick={onSubmit}
              disabled={!canWrite || !reason.trim() || approver === creator || adjustmentSubmitted}
              title={!canWrite ? 'Chỉ Kế toán hoặc RSM được lập phiếu điều chỉnh' : ''}
            >
              <FileText size={16} /> Gửi phiếu điều chỉnh
            </button>
          </div>
        )}
        {tab === 'ledger' && (
          <div className="ops-ledger-lock">
            <span>
              <ShieldCheck size={22} />
            </span>
            <h3>Sổ đã chốt · Chỉ xem</h3>
            <p>
              Đã chốt đến ngày {overview.settledThrough}. Số liệu được khóa; nếu cần thay đổi, hãy
              tạo phiếu điều chỉnh.
            </p>
            <div className="ops-ledger-row">
              <span>Sell-out tuần</span>
              <strong>{overview.sellOutSettled} thùng</strong>
              <Badge tone="success">Đã chốt</Badge>
            </div>
            <div className="ops-ledger-row">
              <span>Công nợ NPP Minh Phát</span>
              <strong>{money(overview.creditTracked)}</strong>
              <Badge tone="success">Đã chốt</Badge>
            </div>
          </div>
        )}
      </section>
      <div className="ops-report-note">
        <HandCoins size={16} />
        <span>
          Đơn Sell-in được kiểm hạn mức ngay khi tiếp nhận; vượt ngưỡng sẽ giữ đơn và gửi yêu cầu
          duyệt cấp trên.
        </span>
      </div>
    </>
  );
}

export function SupportScreen({ role, onToast }: Props) {
  const [view, setView] = useState<'table' | 'kanban'>('table');
  const [statusFilter, setStatusFilter] = useState('Tất cả');
  const [selectedTicketId, setSelectedTicketId] = useState<string | null>(null);
  const departments = ['Kinh doanh', 'Trade marketing', 'Kế toán', 'CSKH / Chất lượng'];
  const extendedTickets = tickets.map((ticket, index) => ({
    ...ticket,
    status: index === 0 ? 'Đang xử lý' : 'Mới',
    department: index === 0 ? 'Kinh doanh' : 'Trade marketing',
    sla: index === 0 ? 'Còn 4h 20p' : 'Còn 1 ngày',
  }));
  const statuses = ['Mới', 'Đang xử lý', 'Chờ phản hồi', 'Đã đóng'];
  const canAssign = ['leader', 'rsm', 'asm', 'support', 'trade', 'admin'].includes(role);
  const filtered = extendedTickets.filter(
    (ticket) => statusFilter === 'Tất cả' || ticket.status === statusFilter,
  );
  const selectedTicket = extendedTickets.find((ticket) => ticket.id === selectedTicketId);
  return (
    <>
      <PageHeading
        eyebrow="CRM · HỖ TRỢ & PHẢN ÁNH"
        title="Ticket & công việc"
        description="Mỗi ticket gắn với khách hàng/đơn, có bộ phận phụ trách, SLA và lịch sử xử lý."
        action={
          <button
            className="ops-button primary"
            onClick={() => onToast({ message: 'Đã mở form tạo ticket mới' })}
          >
            <Plus size={16} /> Tạo ticket
          </button>
        }
      />
      <section className="ops-panel ops-list-panel">
        <div className="ops-list-toolbar">
          <div className="ops-filter-chips">
            {['Tất cả', ...statuses].map((status) => (
              <button
                key={status}
                className={statusFilter === status ? 'selected' : ''}
                onClick={() => setStatusFilter(status)}
              >
                {status}
              </button>
            ))}
          </div>
          <div className="ops-view-switch">
            <button className={view === 'table' ? 'selected' : ''} onClick={() => setView('table')}>
              Bảng
            </button>
            <button
              className={view === 'kanban' ? 'selected' : ''}
              onClick={() => setView('kanban')}
            >
              Kanban
            </button>
          </div>
        </div>
        {view === 'table' && (
          <div className="ops-ticket-table">
            {filtered.length === 0 ? (
              <div className="ops-empty-state">
                <span>
                  <TicketCheck size={20} />
                </span>
                <strong>Chưa có ticket ở trạng thái này</strong>
                <p>Đổi bộ lọc hoặc tạo ticket mới để bắt đầu.</p>
              </div>
            ) : (
              filtered.map((ticket) => (
                <article className="ops-ticket-row" key={ticket.id}>
                  <span className="ops-ticket-icon">
                    <TicketCheck size={18} />
                  </span>
                  <div className="ops-ticket-main">
                    <span className="ops-ticket-id">
                      {ticket.id} · {ticket.kind}
                    </span>
                    <h3>{ticket.title}</h3>
                    <p>
                      {ticket.customer} · {ticket.distributor}
                    </p>
                  </div>
                  <div className="ops-ticket-owner">
                    <small>Phụ trách · bộ phận</small>
                    <strong>{ticket.assignee}</strong>
                    <small>{ticket.department}</small>
                  </div>
                  <div className="ops-ticket-state">
                    <small>Trạng thái</small>
                    <Badge tone={ticket.status === 'Mới' ? 'info' : 'warning'}>
                      {ticket.status}
                    </Badge>
                  </div>
                  <div className="ops-ticket-deadline">
                    <small>SLA</small>
                    <strong className="ops-sla">
                      <Clock3 size={14} />
                      {ticket.sla}
                    </strong>
                    <small>Hạn {ticket.due}</small>
                  </div>
                  <button
                    className="ops-icon-button small-button"
                    aria-label={`Mở ticket ${ticket.id}`}
                    onClick={() => setSelectedTicketId(ticket.id)}
                  >
                    <ArrowUpRight size={16} />
                  </button>
                </article>
              ))
            )}
          </div>
        )}
        {view === 'kanban' && (
          <div className="ops-kanban">
            {statuses.map((status) => {
              const group = extendedTickets.filter((ticket) => ticket.status === status);
              return (
                <section className="ops-kanban-column" key={status}>
                  <header>
                    <span>{status}</span>
                    <Badge
                      tone={
                        status === 'Mới' ? 'info' : status === 'Đang xử lý' ? 'warning' : 'neutral'
                      }
                    >
                      {group.length}
                    </Badge>
                  </header>
                  {group.length ? (
                    group.map((ticket) => (
                      <article
                        key={ticket.id}
                        className="ops-kanban-card"
                        onClick={() => setSelectedTicketId(ticket.id)}
                      >
                        <span className="ops-ticket-id">{ticket.id}</span>
                        <strong>{ticket.title}</strong>
                        <small>{ticket.customer}</small>
                        <small>Phụ trách: {ticket.assignee}</small>
                        <Badge tone="warning">SLA {ticket.sla}</Badge>
                        <button
                          className="ops-text-link"
                          disabled={!canAssign}
                          onClick={() =>
                            onToast({
                              message: `Chuyển ${ticket.id} sang ${status === 'Mới' ? 'Đang xử lý' : 'Chờ phản hồi'}`,
                            })
                          }
                        >
                          Cập nhật trạng thái <ArrowRight size={13} />
                        </button>
                      </article>
                    ))
                  ) : (
                    <div className="ops-kanban-empty">Chưa có ticket</div>
                  )}
                </section>
              );
            })}
          </div>
        )}
      </section>
      <section className="ops-panel ops-department-panel">
        <PanelHeading eyebrow="PHÂN CÔNG LIÊN PHÒNG BAN" title="Đầu mối xử lý" />
        {departments.map((department) => (
          <div className="ops-department-row" key={department}>
            <span>
              <UsersRound size={16} />
              {department}
            </span>
            <strong>
              {department === 'Trade marketing'
                ? 'Hải Yến'
                : department === 'Kế toán'
                  ? 'Thu Trang'
                  : department === 'Kinh doanh'
                    ? people.rsm.name
                    : 'Ngọc Mai'}
            </strong>
            <button
              className="ops-text-link"
              disabled={!canAssign}
              onClick={() => onToast({ message: `Phân công bộ phận ${department}` })}
            >
              Phân công
            </button>
          </div>
        ))}
      </section>
      {selectedTicket && (
        <div className="ops-drawer-backdrop" onMouseDown={() => setSelectedTicketId(null)}>
          <aside
            className="ops-drawer"
            role="dialog"
            aria-modal="true"
            aria-labelledby="ops-ticket-detail-title"
            onMouseDown={(event) => event.stopPropagation()}
          >
            <div className="ops-drawer-header">
              <div>
                <span className="ops-eyebrow">CHI TIẾT PHIẾU · {selectedTicket.id}</span>
                <h2 id="ops-ticket-detail-title">{selectedTicket.title}</h2>
              </div>
              <button
                className="ops-icon-button"
                aria-label="Đóng ticket"
                onClick={() => setSelectedTicketId(null)}
              >
                <X size={18} />
              </button>
            </div>
            <div className="ops-drawer-body">
              <div className="ops-drawer-order-meta">
                <Badge tone={selectedTicket.status === 'Mới' ? 'info' : 'warning'}>
                  {selectedTicket.status}
                </Badge>
                <Badge>{selectedTicket.department}</Badge>
                <p>
                  <strong>{selectedTicket.customer}</strong>
                  <span>
                    Đầu mối: {selectedTicket.assignee} · Hạn xử lý: {selectedTicket.due} · SLA{' '}
                    {selectedTicket.sla}
                  </span>
                </p>
              </div>
              <div className="ops-drawer-section">
                <h3>Đối tượng liên quan</h3>
                <div className="ops-ticket-link-card">
                  <Truck size={16} />
                  <span>
                    <strong>
                      {selectedTicket.id === 'TK-331'
                        ? 'Đơn SO-24076 · Quán Bình'
                        : 'Tuyến Quận 3 · Điểm bán'}
                    </strong>
                    <small>
                      {selectedTicket.id === 'TK-331'
                        ? 'NPP Minh Phát · Thiếu 2 thùng NS150'
                        : 'Yêu cầu POSM kệ nước suối'}
                    </small>
                  </span>
                  <ArrowUpRight size={15} />
                </div>
                <div className="ops-ticket-link-card">
                  <Package size={16} />
                  <span>
                    <strong>
                      {selectedTicket.id === 'TK-331'
                        ? 'SKU NS150 · Nước suối 1.5L'
                        : 'SKU NS500 · Nước suối 500ml'}
                    </strong>
                    <small>Lô hàng: chưa đồng bộ từ NPP</small>
                  </span>
                </div>
              </div>
              <div className="ops-drawer-section">
                <h3>Ảnh & chứng từ</h3>
                <div className="ops-ticket-photo-placeholder">
                  <FileText size={22} />
                  <span>Chưa có ảnh đính kèm</span>
                  <small>Ảnh hiện trường sẽ hiển thị tại đây khi được tải lên.</small>
                </div>
              </div>
              <div className="ops-drawer-section ops-history">
                <h3>Dòng thời gian</h3>
                <p>
                  <i /> 12/11 · Ticket được ghi nhận · {selectedTicket.assignee}
                </p>
                <p>
                  <i /> 12/11 · Đã liên kết với đơn hàng và NPP phụ trách
                </p>
                <p>
                  <i /> Hạn xử lý · {selectedTicket.due}
                </p>
              </div>
              <div className="ops-drawer-section">
                <h3>Phân công</h3>
                <label className="ops-dialog-label">
                  Bộ phận phụ trách
                  <select defaultValue={selectedTicket.department}>
                    <option>Kinh doanh</option>
                    <option>Trade marketing</option>
                    <option>Kế toán</option>
                    <option>CSKH / Chất lượng</option>
                  </select>
                </label>
                <button
                  className="ops-button secondary"
                  disabled={!canAssign}
                  onClick={() => onToast({ message: `Đã lưu phân công ${selectedTicket.id}` })}
                >
                  Lưu phân công
                </button>
              </div>
            </div>
          </aside>
        </div>
      )}
    </>
  );
}

export function ReportScreen({ role, onToast }: Props) {
  const [tab, setTab] = useState<'operations' | 'settled'>('operations');
  const isClosed = tab === 'settled';
  const totalOut = products.reduce((sum, product) => sum + product.sales7d, 0);
  return (
    <>
      <PageHeading
        eyebrow="BÁO CÁO SELL-IN / SELL-OUT"
        title="Hiệu quả phân phối"
        description="Dữ liệu vận hành và dữ liệu đã chốt được tách lớp; không cộng gộp thành một chỉ số."
        action={
          <>
            <button
              className="ops-button secondary"
              onClick={() => onToast({ message: 'Chuẩn bị xuất Excel từ dữ liệu mẫu' })}
            >
              <Download size={15} /> Excel
            </button>
            <button
              className="ops-button secondary"
              onClick={() => onToast({ message: 'Chuẩn bị xuất PDF từ dữ liệu mẫu' })}
            >
              <Download size={15} /> PDF
            </button>
          </>
        }
      />
      <div className="ops-section-tabs report-tabs" role="tablist">
        <button
          role="tab"
          aria-selected={!isClosed}
          className={!isClosed ? 'selected' : ''}
          onClick={() => setTab('operations')}
        >
          Lớp 1 · Vận hành
        </button>
        <button
          role="tab"
          aria-selected={isClosed}
          className={isClosed ? 'selected' : ''}
          onClick={() => setTab('settled')}
        >
          Lớp 2 · Đã chốt
        </button>
      </div>
      <div className="ops-report-layer-caption">
        {isClosed ? (
          <>
            <ShieldCheck size={16} /> Đã chốt đến ngày {overview.settledThrough} · Số liệu chỉ xem
          </>
        ) : (
          <>
            <Clock3 size={16} /> Tạm tính, cập nhật lúc {overview.operationalUpdatedAt} · Chưa phải
            số chốt
          </>
        )}
      </div>
      <div className="ops-metrics-grid report-metrics">
        <div className="ops-panel ops-report-kpi">
          <small>Sell-in · tuần</small>
          <strong>
            {isClosed ? '64' : '64'} <i>thùng</i>
          </strong>
          <span>Nhà phân phối đặt từ NSX</span>
        </div>
        <div className="ops-panel ops-report-kpi">
          <small>Sell-out · tuần</small>
          <strong>
            {isClosed ? overview.sellOutSettled : overview.sellOutWeek} <i>thùng</i>
          </strong>
          <span>NPP bán cho điểm bán</span>
        </div>
        <div className="ops-panel ops-report-kpi">
          <small>Sell-through tham khảo</small>
          <strong>
            {Math.round(
              (totalOut / (totalOut + products.reduce((sum, product) => sum + product.stock, 0))) *
                100,
            )}
            <i>%</i>
          </strong>
          <span>Ước tính từ tồn hiện tại, chưa gồm nhập khác</span>
        </div>
      </div>
      <div className="ops-report-columns">
        <section className="ops-panel ops-report-panel">
          <PanelHeading eyebrow="XU HƯỚNG 30 NGÀY · MINH HỌA" title="Sell-in & Sell-out" />
          <div className="ops-chart-legend">
            <span>
              <i className="dot blue" /> Sell-in
            </span>
            <span>
              <i className="dot teal" /> Sell-out
            </span>
            <small>Dữ liệu mẫu</small>
          </div>
          <div className="ops-bars report-bars">
            {dailySellOut30d.map((value, index) => (
              <span className="ops-bar-pair" key={`${index}-${value}`}>
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
        <section className="ops-panel ops-report-panel">
          <PanelHeading eyebrow="CẢNH BÁO KÊNH" title="Nhập nhiều nhưng bán ra ít" />
          <div className="ops-channel-flag">
            <span>
              <AlertTriangle size={18} />
            </span>
            <div>
              <strong>Chưa phát hiện cờ đẩy hàng ảo</strong>
              <p>
                Dữ liệu Sell-in và Sell-out demo hiện chưa cho thấy NPP nhập nhiều nhưng bán ra
                thấp.
              </p>
            </div>
          </div>
          <div className="ops-rank-row">
            <span>#1</span>
            <strong>Minh Phát</strong>
            <small>Sell-out 7 ngày</small>
            <b>91 thùng</b>
          </div>
          <div className="ops-rank-row">
            <span>#2</span>
            <strong>An Khang</strong>
            <small>Dữ liệu bán ra chưa đồng bộ</small>
            <b>—</b>
          </div>
        </section>
      </div>
      <section className="ops-panel ops-reconcile-panel">
        <PanelHeading
          eyebrow="ĐỐI CHIẾU HAI LỚP"
          title="Chênh lệch vận hành và đã chốt"
          action={
            <Badge tone="warning">
              <AlertTriangle size={13} /> Lệch trên 5%
            </Badge>
          }
        />
        <div className="ops-reconcile-figures">
          <div>
            <small>Tạm tính, cập nhật lúc {overview.operationalUpdatedAt}</small>
            <strong>{overview.sellOutWeek} thùng</strong>
          </div>
          <ArrowDownRight size={18} />
          <div>
            <small>Đã chốt đến ngày {overview.settledThrough}</small>
            <strong>{overview.sellOutSettled} thùng</strong>
          </div>
          <div className="ops-reconcile-diff">
            <small>Chênh lệch</small>
            <strong>
              {overview.sellOutWeek - overview.sellOutSettled} thùng ·{' '}
              {(
                ((overview.sellOutWeek - overview.sellOutSettled) / overview.sellOutSettled) *
                100
              ).toFixed(1)}
              %
            </strong>
          </div>
        </div>
        <p className="ops-muted-note">
          Chỉ vai trò Kế toán/RSM được lập phiếu điều chỉnh. Vai trò hiện tại: {roleName[role]}.
        </p>
      </section>
    </>
  );
}

export function SettingsScreen({ role, onToast }: Props) {
  const [user, setUser] = useState(userDirectory);
  const canAdminister = role === 'leader' || role === 'admin';
  const rolesBelow: Record<Role, string[]> = {
    leader: ['RSM Miền Nam', 'ASM TP.HCM', 'Sales', 'CSKH', 'Trade marketing', 'Kế toán'],
    rsm: ['ASM TP.HCM', 'Sup · Quận 3', 'Sales', 'CSKH', 'Trade marketing'],
    asm: ['Sup · Quận 3', 'Sales'],
    supervisor: ['Sales'],
    sales: [],
    intake: [],
    support: [],
    trade: [],
    accounting: [],
    admin: ['RSM Miền Nam', 'ASM TP.HCM', 'Sales', 'CSKH', 'Trade marketing', 'Kế toán'],
  };
  return (
    <>
      <PageHeading
        eyebrow="QUẢN TRỊ HỆ THỐNG"
        title="Người dùng & cấu hình"
        description="Gán vai theo cấp quyền, phạm vi vùng/tuyến và theo dõi nhật ký thao tác."
        action={
          <button
            className="ops-button primary"
            disabled={!canAdminister}
            title={!canAdminister ? `Vai trò ${roleName[role]} không thể thêm người dùng` : ''}
            onClick={() => onToast({ message: 'Mời người dùng mới' })}
          >
            <Plus size={16} /> Thêm người dùng
          </button>
        }
      />
      <section className="ops-panel ops-list-panel">
        <PanelHeading
          eyebrow="TÀI KHOẢN & VAI TRÒ"
          title="Người dùng"
          action={
            <label className="ops-inline-search">
              <Search size={16} />
              <input placeholder="Tìm người dùng" />
            </label>
          }
        />
        <div className="ops-table-scroll">
          <table className="ops-table">
            <thead>
              <tr>
                <th>Người dùng</th>
                <th>Vai trò</th>
                <th>Phạm vi dữ liệu</th>
                <th>Trạng thái</th>
                <th>Quyền gán vai</th>
              </tr>
            </thead>
            <tbody>
              {user.map((item) => (
                <tr key={item.name}>
                  <td>
                    <strong>{item.name}</strong>
                  </td>
                  <td>
                    <select
                      disabled={!canAdminister || item.name === people.rsm.name}
                      value={item.role}
                      onChange={(event) => {
                        const next = event.target.value;
                        setUser((rows) =>
                          rows.map((row) =>
                            row.name === item.name ? { ...row, role: next } : row,
                          ),
                        );
                        onToast({ message: `Đã cập nhật vai trò ${item.name}` });
                      }}
                      aria-label={`Vai trò của ${item.name}`}
                    >
                      <option>{item.role}</option>
                      {rolesBelow[role].map((allowed) => (
                        <option key={allowed}>{allowed}</option>
                      ))}
                    </select>
                  </td>
                  <td>{item.scope}</td>
                  <td>
                    <Badge tone="success">{item.status}</Badge>
                  </td>
                  <td>
                    {canAdminister ? (
                      <span className="ops-muted-cell">Đến cấp {roleName[role]}</span>
                    ) : (
                      <span
                        className="ops-disabled-hint"
                        title="Chỉ có thể gán vai trò bằng hoặc thấp hơn cấp của mình"
                      >
                        <CircleHelp size={13} /> Không đủ cấp quyền
                      </span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <div className="ops-scope-explainer">
          <ShieldCheck size={15} /> Người dùng chỉ xem dữ liệu trong miền, vùng, khu vực hoặc tuyến
          được phân công.
        </div>
      </section>
      <div className="ops-report-columns settings-lower">
        <section className="ops-panel">
          <PanelHeading eyebrow="NHẬT KÝ THAO TÁC" title="Hoạt động gần đây" />
          {actionLog.map((row) => (
            <div className="ops-log-row" key={`${row.at}-${row.action}`}>
              <span className="ops-log-dot" />
              <span>
                <strong>{row.action}</strong>
                <small>
                  {row.actor} · {row.scope}
                </small>
              </span>
              <time>{row.at}</time>
            </div>
          ))}
        </section>
        <section className="ops-panel">
          <PanelHeading eyebrow="QUY TẮC VẬN HÀNH" title="Ngưỡng duyệt & lịch chốt" />
          <label className="ops-setting-row">
            <span>
              <strong>Ngưỡng cảnh báo chênh lệch</strong>
              <small>Cảnh báo khi hai lớp lệch trên ngưỡng.</small>
            </span>
            <select defaultValue="5%">
              <option>5%</option>
              <option>8%</option>
              <option>10%</option>
            </select>
          </label>
          <label className="ops-setting-row">
            <span>
              <strong>Duyệt vượt hạn mức</strong>
              <small>Chỉ RSM hoặc Lãnh đạo được duyệt.</small>
            </span>
            <Badge tone="info">RSM · Lãnh đạo</Badge>
          </label>
          {closeSchedule.slice(0, 2).map((row) => (
            <div className="ops-setting-row" key={row.name}>
              <span>
                <strong>{row.cycle}</strong>
                <small>{row.next}</small>
              </span>
              <Badge tone="success">{row.status}</Badge>
            </div>
          ))}
          <button
            className="ops-button secondary"
            onClick={() => onToast({ message: 'Đã lưu cấu hình vận hành' })}
          >
            <Save size={15} /> Lưu cấu hình
          </button>
        </section>
      </div>
    </>
  );
}
