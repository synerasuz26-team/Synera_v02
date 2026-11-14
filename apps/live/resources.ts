export type Field = {
  key: string;
  label: string;
  type?: 'number' | 'date' | 'datetime-local' | 'checkbox';
  optional?: boolean;
  min?: number;
  options?: string[];
};
export type FieldGroup = { key: string; label: string; fields: Field[] };
export type Resource = {
  path: string;
  label: string;
  permissions: string[];
  columns: string[];
  search?: string;
  status?: boolean;
  detail?: boolean;
  create?: { permission: string; fields: Field[]; groups?: FieldGroup[] };
};
const field = (key: string, label: string, type?: Field['type'], optional = false): Field => ({
  key,
  label,
  type,
  optional,
});
export const reason = field('reason', 'Lý do');
export const resources: Resource[] = [
  {
    path: '/tasks',
    label: 'Công việc và lời hẹn',
    permissions: ['task:view'],
    columns: ['title', 'status', 'dueAt', 'assigneeId'],
    status: true,
    create: {
      permission: 'task:create',
      fields: [
        field('title', 'Tiêu đề'),
        field('description', 'Nội dung', undefined, true),
        field('assigneeId', 'ID người nhận (bỏ trống để tự nhắc)', undefined, true),
        field('dueAt', 'Đến hạn', 'datetime-local'),
      ],
    },
  },
  {
    path: '/orders/sell-in',
    label: 'Đơn sell-in',
    permissions: ['order_si:view'],
    columns: ['orderCode', 'nppName', 'status', 'totalVnd', 'updatedAt'],
    status: true,
    detail: true,
  },
  {
    path: '/orders/sell-out',
    label: 'Đơn sell-out',
    permissions: ['order_so:view'],
    columns: ['orderCode', 'outletName', 'nppName', 'status', 'totalVnd', 'updatedAt'],
    status: true,
    detail: true,
  },
  {
    path: '/products',
    label: 'Sản phẩm',
    permissions: ['product:view'],
    columns: ['sku', 'name', 'baseUnitCode', 'status'],
    search: 'q',
    detail: true,
  },
  {
    path: '/price-lists',
    label: 'Bảng giá',
    permissions: ['price_list:manage', 'price_list:approve'],
    columns: ['code', 'name', 'status', 'effectiveFrom', 'effectiveTo'],
    status: true,
    detail: true,
  },
  {
    path: '/promotions',
    label: 'Khuyến mãi',
    permissions: ['promotion:view'],
    columns: ['code', 'name', 'status', 'effectiveFrom', 'effectiveTo'],
    status: true,
    detail: true,
  },
  {
    path: '/vouchers',
    label: 'Voucher',
    permissions: ['promotion:view'],
    columns: ['code', 'name', 'status', 'effectiveFrom', 'effectiveTo'],
    status: true,
    detail: true,
  },
  {
    path: '/org-units',
    label: 'Cơ cấu tổ chức',
    permissions: ['org_unit:view'],
    columns: ['code', 'name', 'unitType', 'path'],
    search: 'q',
    create: {
      permission: 'org_unit:manage',
      fields: [
        field('code', 'Mã'),
        field('name', 'Tên'),
        field('unitType', 'Loại đơn vị'),
        field('parentId', 'ID đơn vị cha', undefined, true),
        reason,
      ],
    },
  },
  {
    path: '/routes',
    label: 'Tuyến & MCP',
    permissions: ['route:view'],
    columns: ['code', 'name', 'orgUnitName', 'status'],
    search: 'q',
    create: {
      permission: 'route:manage',
      fields: [
        field('code', 'Mã tuyến'),
        field('name', 'Tên tuyến'),
        field('orgUnitId', 'ID đơn vị'),
        reason,
      ],
    },
  },
  {
    path: '/visits',
    label: 'Lượt ghé',
    permissions: ['visit:view'],
    columns: ['outletName', 'salesName', 'status', 'plannedAt', 'checkedInAt', 'checkedOutAt'],
    status: true,
  },
  {
    path: '/npps',
    label: 'Nhà phân phối',
    permissions: ['npp:view'],
    columns: ['code', 'name', 'status', 'outletCount'],
    search: 'q',
    detail: true,
  },
  {
    path: '/outlets',
    label: 'Điểm bán',
    permissions: ['outlet:view'],
    columns: ['code', 'name', 'address', 'status'],
    search: 'q',
    status: true,
    detail: true,
    create: {
      permission: 'outlet:create',
      fields: [
        field('code', 'Mã điểm bán'),
        field('name', 'Tên'),
        field('address', 'Địa chỉ'),
        field('nppTenantId', 'ID NPP', undefined, true),
        field('routeId', 'ID tuyến', undefined, true),
        field('contactName', 'Liên hệ', undefined, true),
        field('contactPhone', 'Điện thoại', undefined, true),
        reason,
      ],
    },
  },
  {
    path: '/inventory',
    label: 'Tồn kho',
    permissions: ['inventory:view'],
    columns: ['sku', 'productName', 'nppName', 'unitCode', 'quantity', 'recordedAt'],
    search: 'sku',
    create: {
      permission: 'inventory:update',
      fields: [
        field('nppTenantId', 'ID NPP', undefined, true),
        field('sku', 'SKU'),
        field('unitCode', 'Đơn vị'),
        field('quantity', 'Số lượng', 'number'),
        reason,
      ],
    },
  },
  {
    path: '/sellout-records',
    label: 'Dữ liệu sell-out',
    permissions: ['sellout_data:view'],
    columns: ['sku', 'outletName', 'nppName', 'unitCode', 'quantity', 'occurredAt'],
    search: 'sku',
    create: {
      permission: 'sellout_data:submit',
      fields: [
        field('nppTenantId', 'ID NPP', undefined, true),
        field('outletCode', 'Mã điểm bán'),
        field('sku', 'SKU'),
        field('unitCode', 'Đơn vị'),
        { ...field('quantity', 'Số lượng', 'number'), min: 1 },
        reason,
      ],
    },
  },
  {
    path: '/credit-limits',
    label: 'Hạn mức',
    permissions: ['credit_limit:view'],
    columns: ['nppName', 'remainingAmountVnd', 'remainingQty', 'status', 'effectiveFrom'],
    status: true,
  },
  {
    path: '/invoices',
    label: 'Hóa đơn',
    permissions: ['debt:view'],
    columns: ['invoiceCode', 'amountVnd', 'outstandingVnd', 'status', 'dueAt'],
    status: true,
  },
  {
    path: '/payments',
    label: 'Thanh toán',
    permissions: ['debt:view'],
    columns: ['referenceCode', 'amountVnd', 'status', 'paidAt'],
    status: true,
    create: {
      permission: 'payment:declare',
      fields: [
        field('nsxTenantId', 'ID NSX', undefined, true),
        field('invoiceId', 'ID hóa đơn', undefined, true),
        { ...field('amountVnd', 'Số tiền (VND)', 'number'), min: 1 },
        field('referenceCode', 'Mã giao dịch'),
        reason,
      ],
    },
  },
  {
    path: '/deductions',
    label: 'Khấu trừ',
    permissions: ['debt:view'],
    columns: ['amountVnd', 'status', 'reason', 'createdAt'],
  },
  {
    path: '/reconciliations',
    label: 'Đối soát',
    permissions: ['reconciliation:view'],
    columns: ['reconciliationCode', 'status', 'updatedAt'],
    status: true,
  },
  {
    path: '/periods',
    label: 'Kỳ đối soát',
    permissions: ['reconciliation:view'],
    columns: ['periodCode', 'periodType', 'startsOn', 'endsOn', 'status'],
  },
  {
    path: '/demand-plans',
    label: 'Kế hoạch nhu cầu',
    permissions: ['demand_plan:view'],
    columns: ['sku', 'productName', 'nppName', 'quantity', 'plannedFor', 'status'],
    create: {
      permission: 'demand_plan:submit',
      fields: [
        field('nppTenantId', 'ID NPP', undefined, true),
        field('sku', 'SKU'),
        field('unitCode', 'Đơn vị'),
        { ...field('quantity', 'Số lượng', 'number'), min: 1 },
        field('plannedFor', 'Ngày dự kiến', 'date'),
        reason,
      ],
    },
  },
  {
    path: '/targets',
    label: 'Chỉ tiêu',
    permissions: ['target:view'],
    columns: ['targetCode', 'targetType', 'targetQty', 'actualQty', 'completionPercent'],
    create: {
      permission: 'target:manage',
      fields: [
        field('targetCode', 'Mã chỉ tiêu'),
        field('targetType', 'Loại chỉ tiêu'),
        field('subjectId', 'ID đối tượng'),
        field('periodId', 'ID kỳ', undefined, true),
        field('targetQty', 'Số lượng chỉ tiêu', 'number', true),
        field('targetAmountVnd', 'Giá trị chỉ tiêu (VND)', 'number', true),
        reason,
      ],
    },
  },
];
// Forms follow the Java request records; prices and state transitions are calculated by the API.
const scopes: FieldGroup = {
  key: 'scopes',
  label: 'Phạm vi áp dụng',
  fields: [
    { key: 'scopeType', label: 'Loại phạm vi', options: ['ORG_UNIT', 'NPP', 'OUTLET'] },
    field('scopeId', 'Mã định danh phạm vi'),
  ],
};
const createForms: Record<string, NonNullable<Resource['create']>> = {
  '/products': {
    permission: 'product:manage',
    fields: [
      field('sku', 'SKU'),
      field('name', 'Tên sản phẩm'),
      field('baseUnitCode', 'Mã đơn vị cơ sở'),
      reason,
    ],
    groups: [
      {
        key: 'units',
        label: 'Đơn vị quy đổi',
        fields: [
          field('unitCode', 'Mã đơn vị'),
          field('unitName', 'Tên đơn vị'),
          { ...field('baseUnits', 'Hệ số cơ sở', 'number'), min: 1 },
          field('isBaseUnit', 'Là đơn vị cơ sở', 'checkbox', true),
        ],
      },
    ],
  },
  '/price-lists': {
    permission: 'price_list:manage',
    fields: [field('code', 'Mã bảng giá'), field('name', 'Tên bảng giá'), reason],
    groups: [
      {
        key: 'lines',
        label: 'Giá theo sản phẩm và đơn vị',
        fields: [
          field('productId', 'Mã định danh sản phẩm'),
          field('unitCode', 'Đơn vị'),
          field('sellinPriceVnd', 'Giá sell-in (VND)', 'number', true),
          field('selloutPriceVnd', 'Giá sell-out (VND)', 'number', true),
        ],
      },
      scopes,
    ],
  },
  '/promotions': {
    permission: 'promotion:manage',
    fields: [field('code', 'Mã khuyến mãi'), field('name', 'Tên khuyến mãi'), reason],
    groups: [
      {
        key: 'rules',
        label: 'Điều kiện áp dụng',
        fields: [
          field('productId', 'Mã định danh sản phẩm'),
          field('unitCode', 'Đơn vị'),
          { ...field('minOrderQty', 'Số lượng tối thiểu', 'number'), min: 1 },
          field('discountBasisPoints', 'Chiết khấu (100 điểm = 1%)', 'number', true),
          field('giftProductId', 'Mã định danh sản phẩm tặng', undefined, true),
          { ...field('giftQty', 'Số lượng tặng', 'number', true), min: 1 },
        ],
      },
      scopes,
    ],
  },
  '/vouchers': {
    permission: 'promotion:manage',
    fields: [
      field('code', 'Mã voucher'),
      field('name', 'Tên voucher'),
      field('discountAmountVnd', 'Giảm tiền (VND)', 'number', true),
      field('discountBasisPoints', 'Giảm theo tỷ lệ (100 điểm = 1%)', 'number', true),
      reason,
    ],
    groups: [scopes],
  },
  '/credit-limits': {
    permission: 'credit_limit:manage',
    fields: [
      field('nppTenantId', 'Mã định danh NPP'),
      field('limitAmountVnd', 'Hạn mức (VND)', 'number', true),
      field('limitQty', 'Hạn mức số lượng', 'number', true),
      field('unitCode', 'Đơn vị', undefined, true),
      field('effectiveFrom', 'Hiệu lực từ', 'datetime-local', true),
      field('effectiveTo', 'Hiệu lực đến', 'datetime-local', true),
      reason,
    ],
  },
  '/deductions': {
    permission: 'payment:record',
    fields: [
      field('nppTenantId', 'Mã định danh NPP'),
      field('invoiceId', 'Mã định danh hóa đơn', undefined, true),
      { ...field('amountVnd', 'Số tiền (VND)', 'number'), min: 1 },
      reason,
    ],
  },
  '/periods': {
    permission: 'period:close',
    fields: [
      { key: 'periodType', label: 'Loại kỳ', options: ['DAY', 'WEEK', 'MONTH', 'QUARTER'] },
      field('startsOn', 'Từ ngày', 'date'),
      field('endsOn', 'Đến ngày', 'date'),
      reason,
    ],
  },
};
resources.forEach((resource) => {
  if (createForms[resource.path]) resource.create = createForms[resource.path];
  if (
    [
      '/promotions',
      '/vouchers',
      '/credit-limits',
      '/invoices',
      '/payments',
      '/reconciliations',
    ].includes(resource.path)
  )
    resource.status = false;
  if (resource.path === '/orders/sell-out')
    resource.columns = ['orderCode', 'outletName', 'status', 'totalVnd', 'updatedAt'];
  if (resource.path === '/reconciliations')
    resource.columns = [
      'nppName',
      'status',
      'orderedAmountVnd',
      'shippedAmountVnd',
      'receivedAmountVnd',
      'variancePercent',
      'snapshotAt',
    ];
  if (resource.path === '/periods')
    resource.columns = ['periodType', 'startsOn', 'endsOn', 'status'];
});
export const labels: Record<string, string> = {
  id: 'ID',
  code: 'Mã',
  name: 'Tên',
  sku: 'SKU',
  status: 'Trạng thái',
  version: 'Phiên bản',
  orderCode: 'Mã đơn',
  nppName: 'Nhà phân phối',
  outletName: 'Điểm bán',
  totalVnd: 'Thành tiền',
  updatedAt: 'Cập nhật',
  createdAt: 'Ngày tạo',
  baseUnitCode: 'Đơn vị cơ sở',
  effectiveFrom: 'Hiệu lực từ',
  effectiveTo: 'Hiệu lực đến',
  unitType: 'Loại đơn vị',
  path: 'Đường dẫn',
  orgUnitName: 'Đơn vị',
  salesName: 'Nhân viên',
  plannedAt: 'Lịch ghé',
  checkedInAt: 'Vào lúc',
  checkedOutAt: 'Rời lúc',
  outletCount: 'Số điểm bán',
  address: 'Địa chỉ',
  productName: 'Sản phẩm',
  unitCode: 'Đơn vị',
  quantity: 'Số lượng',
  recordedAt: 'Ghi nhận lúc',
  occurredAt: 'Thời điểm',
  remainingAmountVnd: 'Hạn mức còn lại (VND)',
  remainingQty: 'Số lượng còn lại',
  invoiceCode: 'Mã hóa đơn',
  amountVnd: 'Số tiền',
  outstandingVnd: 'Còn nợ',
  dueAt: 'Hạn thanh toán',
  referenceCode: 'Mã giao dịch',
  paidAt: 'Ngày trả',
  reason: 'Lý do',
  reconciliationCode: 'Mã đối soát',
  periodCode: 'Mã kỳ',
  periodType: 'Loại kỳ',
  startsOn: 'Bắt đầu',
  endsOn: 'Kết thúc',
  plannedFor: 'Ngày dự kiến',
  targetCode: 'Mã chỉ tiêu',
  targetType: 'Loại chỉ tiêu',
  targetQty: 'Số lượng chỉ tiêu',
  actualQty: 'Thực hiện',
  completionPercent: 'Hoàn thành (%)',
  orderedQty: 'Đặt',
  confirmedQty: 'Xác nhận',
  shippedQty: 'Xuất',
  receivedQty: 'Nhận',
  isGift: 'Hàng tặng',
  lineAmountVnd: 'Thành tiền',
  note: 'Ghi chú',
  history: 'Lịch sử',
  lines: 'Chi tiết hàng',
  fromStatus: 'Trạng thái trước',
  toStatus: 'Trạng thái sau',
  subtotalVnd: 'Tạm tính',
  discountVnd: 'Chiết khấu',
  unitPriceVnd: 'Đơn giá',
  units: 'Quy đổi đơn vị',
  unitName: 'Tên đơn vị',
  baseUnits: 'Hệ số cơ sở',
  isBaseUnit: 'Đơn vị cơ sở',
  outlet: 'Điểm bán',
  product: 'Sản phẩm',
  timeline: 'Lịch sử tương tác',
  summary: 'Nội dung',
  type: 'Loại',
  orderCount: 'Số đơn',
  ticketCount: 'Số phiếu',
  openTaskCount: 'Việc đang mở',
  createdBy: 'Người tạo',
  approvedBy: 'Người duyệt',
  receiptLines: 'Thực nhận',
  damagedQty: 'Hỏng',
  orderLineId: 'Dòng hàng',
  tenantId: 'Đơn vị sở hữu',
  nppTenantId: 'ID NPP',
  outletId: 'ID điểm bán',
  productId: 'ID sản phẩm',
  enteredUnitCode: 'Đơn vị đặt',
  enteredQty: 'Số lượng đặt',
  grossAmountVnd: 'Trước chiết khấu',
  promotionCode: 'Mã khuyến mãi',
  currency: 'Tiền tệ',
  calculatedAt: 'Tính lúc',
  actorId: 'Người thực hiện',
  actorName: 'Người thực hiện',
  rowCount: 'Số dòng',
  validCount: 'Dòng hợp lệ',
  invalidCount: 'Dòng lỗi',
  lineNumber: 'Dòng',
  errors: 'Lỗi',
  valid: 'Hợp lệ',
  data: 'Dữ liệu',
  fileName: 'Tên tệp',
  importType: 'Loại import',
  committedAt: 'Đã nhập lúc',
  distanceM: 'Khoảng cách (m)',
  awaitingApproval: 'Chờ phê duyệt',
  visitId: 'ID lượt ghé',
  gpsExceptionId: 'ID ngoại lệ GPS',
};
