import fs from 'node:fs';
import path from 'node:path';
const files = (directory) =>
  fs
    .readdirSync(directory, { withFileTypes: true })
    .flatMap((entry) =>
      entry.isDirectory()
        ? files(path.join(directory, entry.name))
        : [path.join(directory, entry.name)],
    );
const pattern = /packages[\\/]mock-data|Tạp hóa Lan|SI-1182|SO-24081/;
for (const file of [...files('apps/live'), ...files('packages/api')].filter(
  (file) => /\.[jt]sx?$/.test(file) && !file.endsWith('.test.ts'),
)) {
  if (pattern.test(fs.readFileSync(file, 'utf8')))
    throw new Error(`Demo data in live source: ${file}`);
}
if (!fs.existsSync('dist/assets')) throw new Error('Run npm run build before check:p4.');
for (const file of files('dist/assets').filter((file) => file.endsWith('.js'))) {
  if (pattern.test(fs.readFileSync(file, 'utf8')))
    throw new Error(`Demo data in production bundle: ${file}`);
}
console.log('P4: no mock imports or demo identifiers in live sources and production JS.');
