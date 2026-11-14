import { spawn } from 'node:child_process';
import { once } from 'node:events';

const database = process.argv[2] || 'synera';
if (!/^synera(?:_restore_[a-z0-9_]+)?$/.test(database)) {
  throw new Error('Database must be the local synera or synera_restore_* database.');
}
const user = process.env.LOCAL_DB_USER || 'synera';
const query = `SELECT table_name, row_count
FROM (
  SELECT 'tenant' AS table_name, count(*)::bigint AS row_count FROM tenant
  UNION ALL SELECT 'app_users', count(*) FROM app_users
  UNION ALL SELECT 'product', count(*) FROM product
  UNION ALL SELECT 'order_si', count(*) FROM order_si
  UNION ALL SELECT 'order_so', count(*) FROM order_so
  UNION ALL SELECT 'ticket', count(*) FROM ticket
  UNION ALL SELECT 'audit_log', count(*) FROM audit_log
) counts
ORDER BY table_name`;
const child = spawn(
  'docker',
  ['compose', 'exec', '-T', 'db', 'psql', '-X', '-U', user, '-d', database, '-c', query],
  { stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true },
);
let stdout = '';
let stderr = '';
child.stdout.on('data', (chunk) => { stdout += chunk.toString(); });
child.stderr.on('data', (chunk) => { if (stderr.length < 4000) stderr += chunk.toString(); });
const [code] = await once(child, 'close');
if (code !== 0) throw new Error(`Local database verification failed (${code}): ${stderr.trim()}`);
console.log(`Read-only row-count check for local database ${database}:`);
console.log(stdout.trim());
