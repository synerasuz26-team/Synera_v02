import fs from 'node:fs/promises';
import path from 'node:path';
import { SpreadsheetFile, Workbook } from '@oai/artifact-tool';

const output = path.resolve('outputs/p5-import-templates');
const destination = path.resolve('backend/src/main/resources/import-templates');
await fs.mkdir(output, { recursive: true });
await fs.mkdir(destination, { recursive: true });
const sheets = {
  products: {
    headers: ['sku_code', 'name', 'category', 'base_unit', 'case_unit', 'units_per_case', 'note'],
    example: [
      'SKU-001',
      'Sản phẩm của doanh nghiệp',
      'Nhóm hàng',
      'hộp',
      'thùng',
      48,
      'Thay ví dụ bằng dữ liệu thật',
    ],
    widths: [22, 42, 24, 16, 16, 22, 44],
  },
  npp: {
    headers: [
      'npp_code',
      'name',
      'org_unit_code',
      'address',
      'credit_limit_vnd',
      'debt_current_vnd',
      'credit_remaining_vnd',
    ],
    example: [
      'NPP-001',
      'Nhà phân phối của doanh nghiệp',
      'ROOT',
      'Địa chỉ đầy đủ',
      100000000,
      20000000,
      80000000,
    ],
    widths: [20, 42, 22, 48, 26, 26, 28],
  },
  outlets: {
    headers: [
      'outlet_code',
      'name',
      'outlet_type',
      'address',
      'contact_name',
      'org_unit_code',
      'route_code',
      'status',
      'contact_phone',
      'contact_email',
      'npp_code',
    ],
    example: [
      'OL-001',
      'Điểm bán của doanh nghiệp',
      'Tạp hóa',
      'Địa chỉ đầy đủ',
      'Người liên hệ',
      'ROOT',
      'ROUTE-001',
      'PENDING',
      '0901 234 567',
      'contact@example.invalid',
      'NPP-001',
    ],
    widths: [20, 38, 20, 48, 28, 22, 22, 18, 24, 38, 20],
  },
  outlet_npp: {
    headers: ['outlet_code', 'npp_code'],
    example: ['OL-001', 'NPP-001'],
    widths: [22, 22],
  },
  org_units: {
    headers: ['org_code', 'parent_code', 'name', 'level', 'path'],
    example: ['ROOT', '', 'Đơn vị gốc', 0, '/ROOT'],
    widths: [22, 22, 42, 14, 50],
  },
  routes: {
    headers: ['route_code', 'name', 'org_unit_code', 'sales_username', 'supervisor_username'],
    example: ['ROUTE-001', 'Tên tuyến bán hàng', 'ROOT', '', ''],
    widths: [22, 40, 22, 30, 30],
  },
  route_outlets: {
    headers: ['route_code', 'outlet_code', 'visit_order', 'mcp_weekday', 'mcp_weeks'],
    example: ['ROUTE-001', 'OL-001', 1, 'Thứ 2', '1,2,3,4'],
    widths: [22, 22, 20, 22, 24],
  },
  users: {
    headers: [
      'username',
      'full_name',
      'role_code',
      'tenant_code',
      'org_unit_code',
      'route_code',
      'password',
      'email',
    ],
    example: [
      'sales.01',
      'Họ tên nhân viên',
      'NSX_SALES',
      'MA_TENANT',
      'ROOT',
      'ROUTE-001',
      '',
      'sales.01@example.invalid',
    ],
    widths: [26, 36, 24, 22, 22, 22, 32, 42],
  },
  inventory_npp: {
    headers: [
      'npp_code',
      'sku_code',
      'stock_case',
      'sellout_7d_case',
      'days_cover',
      'stock_status',
      'updated_at',
    ],
    example: ['NPP-001', 'SKU-001', 10, '', '', '', new Date('2026-11-13T08:00:00Z')],
    widths: [22, 22, 20, 24, 20, 24, 40],
  },
};
const definitions = {
  products: ['products'],
  npp: ['npp'],
  outlets: ['outlets', 'outlet_npp'],
  org_units: ['org_units'],
  routes: ['routes', 'route_outlets'],
  users: ['users'],
  inventory: ['inventory_npp'],
  credit: ['npp'],
};
const titles = {
  products: 'Sản phẩm và đơn vị quy đổi',
  npp: 'Nhà phân phối',
  outlets: 'Điểm bán và liên hệ',
  org_units: 'Cây tổ chức',
  routes: 'Tuyến và MCP',
  users: 'Người dùng và vai',
  inventory: 'Tồn đầu kỳ NPP',
  credit: 'Hạn mức và công nợ đầu kỳ',
};
for (const [kind, names] of Object.entries(definitions)) {
  const workbook = Workbook.create();
  for (const name of names) {
    const spec = sheets[name];
    const sheet = workbook.worksheets.add(name);
    sheet.showGridLines = false;
    const used = sheet.getRangeByIndexes(0, 0, 2, spec.headers.length);
    used.format.font = { name: 'Arial', size: 11 };
    used.format.verticalAlignment = 'center';
    used.format.rowHeight = 30;
    sheet.getRangeByIndexes(0, 0, 1, spec.headers.length).format = {
      fill: '#1769C2',
      font: { name: 'Arial', size: 11, bold: true, color: '#FFFFFF' },
      rowHeight: 34,
    };
    sheet.getRangeByIndexes(1, 0, 1, spec.headers.length).format.fill = '#FFF4D6';
    spec.widths.forEach((width, index) => {
      sheet.getRangeByIndexes(0, index, 2, 1).format.columnWidth = width;
      sheet
        .getRangeByIndexes(1, index, 1, 1)
        .setNumberFormat(
          spec.example[index] instanceof Date
            ? 'yyyy-mm-dd hh:mm'
            : typeof spec.example[index] === 'number'
              ? '0'
              : '@',
        );
    });
    used.values = [spec.headers, spec.example];
    sheet.freezePanes.freezeRows(1);
  }
  const guide = workbook.worksheets.add('huong_dan');
  guide.showGridLines = false;
  const notes = [
    ['Mẫu nhập', titles[kind]],
    ['Ví dụ', 'Dòng màu vàng là ví dụ. Thay toàn bộ ví dụ bằng dữ liệu thật trước khi tải lên.'],
    [
      'Tên sheet và cột',
      'Giữ nguyên tiêu đề dòng 1. Các sheet dữ liệu theo cấu trúc workbook Vinamilk được cung cấp.',
    ],
    [
      'Quy trình',
      'Tải lên, kiểm tra lỗi/cảnh báo từng dòng, sau đó xác nhận ghi cả lô. Có lỗi thì chưa ghi nghiệp vụ.',
    ],
    [
      'Mã nghiệp vụ',
      'Mã được trim, bỏ khoảng trắng, bỏ dấu và viết hoa. Tham chiếu bằng mã, không dùng UUID.',
    ],
    [
      'Số nguyên',
      'Số lượng và VND là số nguyên không âm, không dùng dấu phân cách nghìn. Hệ số quy đổi phải lớn hơn 0.',
    ],
    ['Thời gian', 'Dùng ISO 8601, ví dụ 2026-11-13T08:00:00+07:00. Ô ngày Excel cũng được hỗ trợ.'],
    ['Điện thoại', 'Để kiểu văn bản nhằm giữ số 0 đầu. Số Việt Nam sẽ chuẩn hóa thành +84.'],
    [
      'Người dùng',
      'Cần email đăng nhập. Nhập mật khẩu mới trong cột password; mật khẩu không hiện ở preview. Không reset mật khẩu tài khoản cũ qua import.',
    ],
    [
      'Duyệt',
      'Điểm bán mới chờ duyệt. Hạn mức đầu kỳ là nháp và cần người khác duyệt theo workflow.',
    ],
    [
      'Thứ tự',
      'Tổ chức, sản phẩm, NPP, tuyến (chưa gán người), điểm bán, người dùng, gán người cho tuyến, tồn đầu kỳ, hạn mức/công nợ.',
    ],
    [
      'Sheet bổ sung',
      'outlet_npp và route_outlets là tùy chọn trong XLSX. CSV chỉ nhập sheet chính của loại được chọn.',
    ],
    [
      'Phạm vi',
      'NPP chỉ nhập điểm bán/liên hệ, tồn và người dùng của chính mình theo vai. Bỏ cột tuyến/tổ chức NSX khi NPP nhập điểm bán.',
    ],
    [
      'Cột đối chiếu',
      'sellout_7d_case, days_cover, stock_status không sinh giao dịch. Các cột công nợ trong loại NPP chỉ đối chiếu; dùng loại Hạn mức để nhập.',
    ],
    ['Định dạng', 'Chỉ nhập giá trị. Không dùng công thức, macro hoặc file có mật khẩu bảo vệ.'],
  ];
  guide.getRange(`A1:B${notes.length}`).values = notes;
  guide.getRange(`A1:B${notes.length}`).format.font = { name: 'Arial', size: 11 };
  guide.getRange('A1:B1').format.font = { name: 'Arial', size: 13, bold: true };
  guide.getRange(`A1:A${notes.length}`).format.columnWidth = 24;
  guide.getRange(`B1:B${notes.length}`).format.columnWidth = 115;
  guide.getRange(`A1:B${notes.length}`).format.rowHeight = 42;
  guide.getRange(`B1:B${notes.length}`).format.wrapText = true;
  workbook.recalculate();
  const check = await workbook.inspect({
    kind: 'table',
    range: `${names[0]}!A1:D2`,
    tableMaxRows: 2,
    tableMaxCols: 4,
    maxChars: 800,
  });
  console.log(kind, check.ndjson);
  for (const name of [...names, 'huong_dan']) {
    const preview = await workbook.render({
      sheetName: name,
      range:
        name === 'huong_dan'
          ? 'A1:B6'
          : `A1:${String.fromCharCode(64 + sheets[name].headers.length)}2`,
      scale: 1,
      format: 'png',
    });
    await fs.writeFile(
      path.join(output, `${kind}-${name}.png`),
      new Uint8Array(await preview.arrayBuffer()),
    );
  }
  const exported = await SpreadsheetFile.exportXlsx(workbook);
  await exported.save(path.join(output, `${kind}.xlsx`));
  await fs.copyFile(path.join(output, `${kind}.xlsx`), path.join(destination, `${kind}.xlsx`));
}
