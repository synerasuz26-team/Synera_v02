import { createGzip } from 'node:zlib';
import { spawn } from 'node:child_process';
import { createWriteStream } from 'node:fs';
import { mkdir, rename, rm, stat, writeFile } from 'node:fs/promises';
import { once } from 'node:events';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { pipeline } from 'node:stream/promises';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const source = readSource();
const outputDirectory = path.resolve(
  process.env.BACKUP_DIR || path.join(os.homedir(), 'synera-backups'),
);
const toolsImage = process.env.PG_TOOLS_IMAGE;
if (!/^postgres:[0-9]+-alpine$/.test(toolsImage || '')) {
  throw new Error('Set PG_TOOLS_IMAGE to a postgres:<server-major>-alpine image matching the database major version.');
}
const dockerNetwork = process.env.PG_DOCKER_NETWORK;
if (dockerNetwork && !/^[a-zA-Z0-9][a-zA-Z0-9_.-]*$/.test(dockerNetwork)) {
  throw new Error('PG_DOCKER_NETWORK must be a Docker network name.');
}
const relative = path.relative(root, outputDirectory);
if (relative === '' || (!relative.startsWith('..') && !path.isAbsolute(relative))) {
  throw new Error('BACKUP_DIR must be outside the repository.');
}
await mkdir(outputDirectory, { recursive: true });

const stamp = new Date().toISOString().replaceAll(':', '-').replaceAll('.', '-');
const output = path.join(outputDirectory, `synera-${source.database}-${stamp}.sql.gz`);
const partial = `${output}.partial`;
const pgEnv = { ...process.env, ...source.env };
const dump = spawn(
  'docker',
  [
    'run', '--rm', ...(dockerNetwork ? ['--network', dockerNetwork] : []), '-e', 'PGPASSWORD', '-e', 'PGSSLMODE',
    toolsImage, 'pg_dump',
    '--host', source.host, '--port', source.port, '--username', source.user,
    '--dbname', source.database, '--no-owner', '--no-acl', '--format=plain',
  ],
  { env: pgEnv, stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true },
);
let stderr = '';
dump.stderr.on('data', (chunk) => {
  if (stderr.length < 4000) stderr += chunk.toString();
});
try {
  const streamResult = pipeline(
    dump.stdout,
    createGzip({ level: 6 }),
    createWriteStream(partial, { flags: 'wx' }),
  );
  const [exitCode] = await once(dump, 'close');
  await streamResult;
  if (exitCode !== 0) throw new Error(`pg_dump failed (${exitCode}): ${stderr.trim()}`);
  await rename(partial, output);
  const manifest = {
    createdAt: new Date().toISOString(),
    sourceHost: source.host,
    sourceDatabase: source.database,
    dumpFile: path.basename(output),
    bytes: (await stat(output)).size,
  };
  manifest.rowCounts = await readCounts(source);
  await writeFile(
    output.replace(/\.sql\.gz$/, '.manifest.json'),
    JSON.stringify(manifest, null, 2),
    { flag: 'wx' },
  );
  console.log(`Backup created outside the repository: ${output}`);
  console.log(`Database host: ${source.host}; row-count manifest: ${manifest.rowCounts ? 'available' : 'unavailable'}`);
} catch (error) {
  dump.kill();
  await rm(partial, { force: true });
  throw error;
}

function readSource() {
  let url;
  const rawUrl = process.env.DATABASE_URL || process.env.PGURL;
  if (rawUrl) {
    url = new URL(rawUrl);
  }
  const host = process.env.PGHOST || url?.hostname;
  const port = process.env.PGPORT || url?.port || '5432';
  const database = process.env.PGDATABASE || decodeURIComponent(url?.pathname.slice(1) || '');
  const user = process.env.PGUSER || decodeURIComponent(url?.username || '');
  const password = process.env.PGPASSWORD || decodeURIComponent(url?.password || '');
  if (!host || !database || !user || !password) {
    throw new Error('Set PGHOST, PGPORT, PGDATABASE, PGUSER and PGPASSWORD (or DATABASE_URL) locally.');
  }
  if (host.endsWith('.railway.internal')) {
    throw new Error('Use the database public hostname for a backup from this machine, not private networking.');
  }
  return {
    host,
    port,
    database,
    user,
    env: { PGPASSWORD: password, PGSSLMODE: process.env.PGSSLMODE || 'prefer' },
  };
}

async function readCounts(connection) {
  const query = `SELECT json_build_object(
    'tenant',(SELECT count(*) FROM tenant),
    'app_users',(SELECT count(*) FROM app_users),
    'product',(SELECT count(*) FROM product),
    'order_si',(SELECT count(*) FROM order_si),
    'order_so',(SELECT count(*) FROM order_so),
    'ticket',(SELECT count(*) FROM ticket),
    'audit_log',(SELECT count(*) FROM audit_log)
  )::text`;
  const result = spawn(
    'docker',
    [
      'run', '--rm', '-e', 'PGPASSWORD', '-e', 'PGSSLMODE',
      ...(dockerNetwork ? ['--network', dockerNetwork] : []),
      toolsImage, 'psql',
      '--host', connection.host, '--port', connection.port, '--username', connection.user,
      '--dbname', connection.database, '--no-psqlrc', '--tuples-only', '--no-align', '-c', query,
    ],
    { env: { ...process.env, ...connection.env }, stdio: ['ignore', 'pipe', 'ignore'], windowsHide: true },
  );
  let stdout = '';
  result.stdout.on('data', (chunk) => { stdout += chunk.toString(); });
  const [code] = await once(result, 'close');
  if (code !== 0) return null;
  try {
    return JSON.parse(stdout.trim());
  } catch {
    return null;
  }
}
