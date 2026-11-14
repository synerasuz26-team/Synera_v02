export const demoDate = 'Thứ Năm, 12/11/2026';

export const people = {
  rsm: { name: 'Lê Quang', title: 'RSM Miền Nam', initials: 'LQ' },
  asm: { name: 'Trần Mỹ', title: 'ASM TP.HCM', initials: 'TM' },
  sales: { name: 'Trần Hà', title: 'Sales · Tuyến Quận 3', initials: 'TH' },
  distributor: { name: 'chị Mai', title: 'Đầu mối NPP Minh Phát', initials: 'M' },
  storeOwner: { name: 'chị Lan', title: 'Chủ Tạp hóa Lan', initials: 'L' },
};

export const products = [
  {
    sku: 'NS500',
    name: 'Nước suối 500ml',
    pack: '24 chai / thùng',
    sellIn: 86000,
    sellOut: 96000,
    promotion: 'Mua 10 tặng 1',
    stock: 36,
    sales7d: 52,
    stockHealth: 'Ổn',
  },
  {
    sku: 'NS150',
    name: 'Nước suối 1.5L',
    pack: '12 chai / thùng',
    sellIn: 112000,
    sellOut: 128000,
    promotion: '',
    stock: 8,
    sales7d: 21,
    stockHealth: 'Cần nhập',
  },
  {
    sku: 'TX330',
    name: 'Trà xanh 330ml',
    pack: '24 chai / thùng',
    sellIn: 142000,
    sellOut: 158000,
    promotion: 'Chiết 3% từ 8 thùng',
    stock: 14,
    sales7d: 18,
    stockHealth: 'Sắp thấp',
  },
];

export const distributors = [
  { name: 'Minh Phát', area: 'Q.1, TP.HCM', contact: 'chị Mai', creditRemaining: 120 },
  { name: 'An Khang', area: 'TP.HCM', contact: 'Đầu mối An Khang', creditRemaining: null },
];

export const stores = [
  {
    name: 'Tạp hóa Lan',
    address: '12 Nguyễn Đình Chiểu, Q.3',
    contact: 'chị Lan',
    route: 'Tuyến Quận 3',
    lastVisit: 'Hôm nay, 08:15',
    status: 'Đã ghé',
  },
  {
    name: 'Quán Bình',
    address: 'Q.3, TP.HCM',
    contact: 'Anh Bình',
    route: 'Tuyến Quận 3',
    lastVisit: '11/11/2026',
    status: 'Cần chăm sóc',
  },
  {
    name: 'Cafe 24',
    address: 'Q.3, TP.HCM',
    contact: 'Quản lý Cafe 24',
    route: 'Tuyến Quận 3',
    lastVisit: 'Chưa ghé tháng này',
    status: 'Chưa ghé',
  },
];

export const orders = [
  {
    id: 'SI-1182',
    type: 'sell-in',
    customer: 'NPP Minh Phát',
    owner: 'chị Mai',
    createdAt: '12/11 · 07:40',
    lines: [{ sku: 'NS500', name: 'Nước suối 500ml', quantity: 40 }],
    ordered: 40,
    confirmed: 0,
    shipped: 0,
    received: 0,
    status: 'Chờ NSX duyệt',
    creditRemaining: 120,
    note: 'Đơn đặt trong hạn mức công nợ.',
  },
  {
    id: 'SI-1174',
    type: 'sell-in',
    customer: 'NPP An Khang',
    owner: 'Đầu mối An Khang',
    createdAt: '11/11 · 16:20',
    lines: [],
    ordered: 24,
    confirmed: 24,
    shipped: 24,
    received: 0,
    status: 'Đang giao',
    creditRemaining: null,
    note: 'Đơn đang trên đường đến nhà phân phối.',
  },
  {
    id: 'SO-24081',
    type: 'sell-out',
    customer: 'Tạp hóa Lan',
    owner: 'Trần Hà · Sales',
    createdAt: '12/11 · 08:22',
    lines: [
      { sku: 'NS500', name: 'Nước suối 500ml', quantity: 10 },
      { sku: 'TX330', name: 'Trà xanh 330ml', quantity: 2 },
    ],
    ordered: 12,
    confirmed: 0,
    shipped: 0,
    received: 0,
    freeGoods: [{ sku: 'NS500', name: 'Nước suối 500ml', quantity: 1 }],
    total: 1276000,
    status: 'Chờ NPP xác nhận',
    creditRemaining: null,
    note: 'Đơn do Sales Trần Hà tạo tại điểm bán.',
  },
  {
    id: 'SO-24076',
    type: 'sell-out',
    customer: 'Quán Bình',
    owner: 'Trần Hà · Sales',
    createdAt: '11/11 · 14:05',
    lines: [],
    ordered: 9,
    confirmed: 7,
    shipped: 7,
    received: 7,
    total: null,
    status: 'Giao thiếu · bù 13/11',
    creditRemaining: null,
    note: 'NPP xác nhận 7 thùng; còn 2 thùng đang chờ giao bù.',
  },
];

export const tickets = [
  {
    id: 'TK-331',
    title: 'Giao thiếu 2 thùng NS150',
    customer: 'Quán Bình · SO-24076',
    distributor: 'NPP Minh Phát',
    assignee: 'Trần Hà',
    status: 'Đang xử lý',
    due: '13/11/2026',
    kind: 'Giao nhận',
  },
  {
    id: 'TK-328',
    title: 'Xin POSM kệ nước suối',
    customer: 'Tuyến Quận 3',
    distributor: 'Suối Việt · Trade',
    assignee: 'Trade marketing',
    status: 'Mới',
    due: '13/11/2026',
    kind: 'POSM',
  },
];

export const overview = {
  sellInWaiting: 1,
  mcpVisited: 4,
  mcpTotal: 5,
  openTickets: 2,
  sellOutWeek: 91,
  sellOutSettled: 86,
  sellInTarget: 420,
  sellInTargetTotal: 800,
  quarterDiscount: '2,5%',
  creditTracked: 186000000,
  creditRemainingCases: 120,
  priceValidThrough: '30/11/2026',
  operationalUpdatedAt: '09:42',
  settledThrough: '11/11/2026',
};

export const featureFlags = {
  aiSuggestionsEnabled: false,
};

export const salesRegions = [
  {
    name: 'Miền Bắc',
    regions: [
      {
        name: 'Hà Nội',
        owner: 'Nguyễn Hải',
        sales: 12,
        outlets: 1840,
        territories: ['VBH HN 01', 'VBH HN 02'],
      },
      {
        name: 'Hải Phòng',
        owner: 'Phạm Hương',
        sales: 6,
        outlets: 720,
        territories: ['VBH HP 01'],
      },
    ],
  },
  {
    name: 'Miền Trung',
    regions: [
      { name: 'Đà Nẵng', owner: 'Vũ Minh', sales: 7, outlets: 910, territories: ['VBH ĐN 01'] },
      { name: 'Nghệ An', owner: 'Lê Phương', sales: 5, outlets: 630, territories: ['VBH NA 01'] },
    ],
  },
  {
    name: 'Miền Nam',
    regions: [
      {
        name: 'TP.HCM',
        owner: people.asm.name,
        sales: 18,
        outlets: 2460,
        territories: ['VBH HCM 01', 'VBH HCM 02'],
      },
      { name: 'ĐN-BP', owner: 'Phan Đức', sales: 8, outlets: 980, territories: ['VBH ĐNBP 01'] },
      {
        name: 'Mekong 1–2',
        owner: 'Trần Vũ',
        sales: 11,
        outlets: 1370,
        territories: ['VBH MK 01', 'VBH MK 02'],
      },
    ],
  },
];

export const routes = [
  {
    code: 'RS00301',
    name: 'Tuyến Quận 3',
    sales: people.sales.name,
    region: 'TP.HCM · Miền Nam',
    outlets: 5,
    visitsToday: 4,
  },
  {
    code: 'RS00302',
    name: 'Tuyến Quận 1',
    sales: 'Nguyễn An',
    region: 'TP.HCM · Miền Nam',
    outlets: 8,
    visitsToday: 6,
  },
  {
    code: 'RS00701',
    name: 'Tuyến Bình Thạnh',
    sales: 'Lê Phúc',
    region: 'TP.HCM · Miền Nam',
    outlets: 7,
    visitsToday: 5,
  },
];

export const salesRoute = [
  { outlet: 'Tạp hóa Lan', time: '08:30' },
  { outlet: 'Quán Bình', time: '10:30' },
  { outlet: 'Cafe 24', time: '12:00' },
];

export const salesAppointments = [
  {
    outlet: 'Tạp hóa Lan',
    title: 'Báo lại CTKM trà xanh nếu còn hiệu lực',
    time: '10:30',
    owner: people.sales.name,
    status: 'Đến hạn',
  },
  {
    outlet: 'Quán Bình',
    title: 'Theo dõi giao bù 2 thùng NS150',
    time: '13:00',
    owner: people.sales.name,
    status: 'Cần theo dõi',
  },
];

export const dailySellOut30d = [
  18, 22, 20, 25, 27, 24, 21, 28, 31, 26, 29, 33, 30, 25, 32, 35, 29, 31, 36, 34, 30, 38, 35, 33,
  40, 37, 34, 42, 39, 41,
];

export const dailySellIn30d = [
  25, 28, 22, 32, 29, 34, 27, 31, 38, 33, 36, 30, 41, 35, 39, 32, 43, 37, 40, 34, 45, 39, 42, 36,
  47, 41, 44, 38, 49, 43,
];

export const financeRows = [
  {
    distributor: 'Minh Phát',
    debt: 186000000,
    limit: null,
    remaining: '120 thùng',
    status: 'Theo dõi',
  },
  {
    distributor: 'An Khang',
    debt: null,
    limit: null,
    remaining: null,
    status: 'Chưa có dữ liệu',
  },
];

export const closeSchedule = [
  { name: 'Đối soát ngày', cycle: 'Ngày T+1', next: '13/11/2026 · 09:00', status: 'Đã lên lịch' },
  { name: 'Đối soát tuần', cycle: 'Tuần', next: '16/11/2026 · 09:00', status: 'Đã lên lịch' },
  { name: 'Đối soát tháng', cycle: 'Tháng', next: '01/12/2026 · 09:00', status: 'Đã lên lịch' },
  { name: 'Đối soát quý', cycle: 'Quý', next: '01/01/2027 · 09:00', status: 'Đã lên lịch' },
];

export const userDirectory = [
  { name: people.rsm.name, role: 'RSM Miền Nam', scope: 'Miền Nam', status: 'Đang hoạt động' },
  { name: people.asm.name, role: 'ASM TP.HCM', scope: 'TP.HCM', status: 'Đang hoạt động' },
  { name: people.sales.name, role: 'Sales', scope: 'Tuyến Quận 3', status: 'Đang hoạt động' },
  { name: 'Ngọc Mai', role: 'CSKH', scope: 'Toàn quốc', status: 'Đang hoạt động' },
  { name: 'Thu Trang', role: 'Kế toán', scope: 'Toàn quốc', status: 'Đang hoạt động' },
];

export const actionLog = [
  {
    at: '12/11/2026 · 08:42',
    actor: people.asm.name,
    action: 'Mở xem đơn SI-1182',
    scope: 'NPP Minh Phát',
  },
  {
    at: '12/11/2026 · 08:22',
    actor: people.sales.name,
    action: 'Tạo đơn SO-24081',
    scope: 'Tạp hóa Lan',
  },
  {
    at: '12/11/2026 · 08:15',
    actor: people.sales.name,
    action: 'Ghi nhận ghé điểm bán',
    scope: 'Tạp hóa Lan',
  },
];
