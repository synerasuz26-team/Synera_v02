// Run only for deployment builds; ordinary local builds remain available.
const origin=process.env.VITE_API_URL;
let url;
try { url=new URL(origin); } catch { throw new Error('Set VITE_API_URL to the HTTPS API origin before building.'); }
if(url.protocol!=='https:'||url.username||url.password||url.pathname!=='/'||url.search||url.hash||['localhost','127.0.0.1'].includes(url.hostname))
  throw new Error('VITE_API_URL must be an exact public HTTPS API origin without credentials or paths.');
if(process.env.VITE_USE_MOCK!=='false')throw new Error('Deployment requires VITE_USE_MOCK=false.');
console.log('Deployment variables: HTTPS API origin and mocks disabled.');
