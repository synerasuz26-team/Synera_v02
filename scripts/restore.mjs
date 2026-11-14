import { createGunzip } from 'node:zlib';
import { spawn } from 'node:child_process';
import { createReadStream } from 'node:fs';
import { readFile } from 'node:fs/promises';
import { once } from 'node:events';
import path from 'node:path';
import { pipeline } from 'node:stream/promises';

const dumpPath = path.resolve(process.argv[2] || '');
if (!process.argv[2] || !dumpPath.endsWith('.sql.gz')) {
  throw new Error('Usage: npm run db:restore -- <backup.sql.gz>');
}
const database = process.env.RESTORE_DATABASE || `synera_restore_${Date.now()}`;
if (!/^synera_restore_[a-z0-9_]+$/.test(database)) {
  throw new Error('RESTORE_DATABASE must start with synera_restore_ and contain only lowercase letters, digits or _.');
}
const user = process.env.LOCAL_DB_USER || 'synera';
await runDocker(['compose', 'exec', '-T', 'db', 'createdb', '-U', user, database]);

const psql = spawn(
  'docker',
  ['compose', 'exec', '-T', 'db', 'psql', '-X', '-v', 'ON_ERROR_STOP=1', '-U', user, '-d', database],
  { stdio: ['pipe', 'ignore', 'pipe'], windowsHide: true },
);
let stderr = '';
psql.stderr.on('data', (chunk) => {
  if (stderr.length < 4000) stderr += chunk.toString();
});
try {
  const streamResult = pipeline(createReadStream(dumpPath), createGunzip(), psql.stdin);
  const [exitCode] = await once(psql, 'close');
  await streamResult;
  if (exitCode !== 0) throw new Error(`Local restore failed (${exitCode}): ${stderr.trim()}`);
} catch (error) {
  psql.kill();
  throw error;
}

const counts = await readCounts(database, user);
const manifestPath = dumpPath.replace(/\.sql\.gz$/, '.manifest.json');
let originalCounts = null;
try {
  originalCounts = JSON.parse(await readFile(manifestPath, 'utf8')).rowCounts;
} catch {
  // A dump remains restorable even if its optional source-count manifest is unavailable.
}
if (originalCounts && JSON.stringify(sorted(originalCounts)) !== JSON.stringify(sorted(counts))) {
  throw new Error(`Restore count comparison failed. Original=${JSON.stringify(originalCounts)} Restored=${JSON.stringify(counts)}`);
}
console.log(`Restore verified in local database ${database}.`);
console.log(`Main table counts: ${JSON.stringify(counts)}`);
console.log(originalCounts ? 'Counts match the backup manifest.' : 'No source count manifest; run npm run db:verify for a manual check.');
console.log('The restore database was retained; no database or volume was deleted.');

async function runDocker(args) {
  const child = spawn('docker', args, { stdio: ['ignore', 'ignore', 'pipe'], windowsHide: true });
  let errorText = '';
  child.stderr.on('data', (chunk) => {
    if (errorText.length < 4000) errorText += chunk.toString();
  });
  const [code] = await once(child, 'close');
  if (code !== 0) throw new Error(`Docker command failed (${code}): ${errorText.trim()}`);
}

async function readCounts(db, dbUser) {
  const query = `SELECT json_build_object(
    'tenant',(SELECT count(*) FROM tenant),
    'app_users',(SELECT count(*) FROM app_users),
    'product',(SELECT count(*) FROM product),
    'order_si',(SELECT count(*) FROM order_si),
    'order_so',(SELECT count(*) FROM order_so),
    'ticket',(SELECT count(*) FROM ticket),
    'audit_log',(SELECT count(*) FROM audit_log)
  )::text`;
  const child = spawn(
    'docker',
    ['compose', 'exec', '-T', 'db', 'psql', '-X', '-A', '-t', '-U', dbUser, '-d', db, '-c', query],
    { stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true },
  );
  let stdout = '';
  let errorText = '';
  child.stdout.on('data', (chunk) => { stdout += chunk.toString(); });
  child.stderr.on('data', (chunk) => { if (errorText.length < 4000) errorText += chunk.toString(); });
  const [code] = await once(child, 'close');
  if (code !== 0) throw new Error(`Cannot verify restored database: ${errorText.trim()}`);
  return JSON.parse(stdout.trim());
}

function sorted(value) {
  return Object.fromEntries(Object.entries(value).sort(([left], [right]) => left.localeCompare(right)));
}
