import { request } from '@playwright/test';
import fs from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
const base='https://synerav02-production.up.railway.app/api/v1';
const out='outputs/railway-audit-2026-11-15';
const run='QA-20261115-'+Date.now().toString(36);
const http=await request.newContext({timeout:25000});
const sessions={},logs=[];
const accounts=['chi.mai','an-khang-owner','chi.lan','nsx-leader','nsx-sup','nsx-admin','nsx-order','nsx-cs','nsx-trade','nsx-accounting','npp-staff','npp-accounting','outlet-staff','sys-admin','le.quang','tran.my','tran.ha','nguyen.an','le.phuc'];
const save=()=>fs.writeFile(out+'/business.json',JSON.stringify({run,logs},null,2));
async function call(account,path,method='GET',data,expect=200,headers={}){
 const r=await http.fetch(base+path,{method,headers:{Authorization:'Bearer '+sessions[account]?.accessToken,...headers},...(data===undefined?{}:{data})});
 const raw=await r.text();let body;try{body=JSON.parse(raw)}catch{body=raw.slice(0,150)}
 const log={account,path,method,input:data,status:r.status(),expect,pass:(Array.isArray(expect)?expect:[expect]).includes(r.status()),body};logs.push(log);await save();
 console.log(JSON.stringify({account,path,method,status:r.status(),pass:log.pass,code:body?.code,state:body?.status}));return body;
}
const can=(a,p)=>sessions[a].me.permissions.some(x=>x.code===p&&!['N','-'].includes(x.scope));
try{
 for(const a of accounts){const r=await http.post(base+'/auth/login',{data:{email:a+'@demo.synera.local',password:process.env.SYNERA_QA_PASSWORD}});if(!r.ok()){console.log(a+' LOGIN '+r.status());continue;}sessions[a]=await r.json();sessions[a].me=await call(a,'/auth/me');}
 const owner='chi.mai',leader='nsx-leader',staff='npp-staff',sales='tran.ha',outlet='chi.lan';
 const nsxId=sessions[leader].me.tenant.id,nppId=sessions[owner].me.tenant.id;
 const products=await call(owner,'/products?size=100');
 const product=products.items.find(p=>p.sku==='NS500')||products.items[0];
 const units=await call(owner,'/products/'+product.id);
 const unit=units.units.find(u=>u.unitCode==='THUNG')?.unitCode||product.baseUnitCode;
 const outlets=await call(owner,'/outlets?size=100');
 const outletRow=outlets.items.find(o=>o.name.includes('Lan'))||outlets.items[0];
 const line={productId:product.id,unitCode:unit,quantity:1};
 for(const a of accounts){if(!sessions[a])continue;
  await call(a,'/admin/storage','GET',undefined,a==='sys-admin'?200:403);
  await call(a,'/orders/sell-in?size=1','GET',undefined,can(a,'order_si:view')?200:403);
  if(can(a,'task:create')){const task=await call(a,'/tasks','POST',{title:run+' '+a,description:'Kiểm thử tự nhắc và hoàn tất',dueAt:new Date(Date.now()+86400000).toISOString()});if(task.id)await call(a,'/tasks/'+task.id+'/complete','POST',{version:task.version});}
  if(can(a,'route:view')){const routes=await call(a,'/routes?size=100');if(routes.items?.[0]){await call(a,'/routes/'+routes.items[0].id+'/outlets');await call(a,'/routes/'+routes.items[0].id+'/mcp');}}
  if(can(a,'daily_report:view'))await call(a,'/daily-report');
  if(can(a,'visit:gps_override'))await call(a,'/visit-gps-exceptions');
  if(can(a,'period:adjust_create')||can(a,'period:adjust_approve'))await call(a,'/period-adjustments');
 }
 // Tenant isolation, including valid IDs from the other distributor.
 const ownOrders=await call(owner,'/orders/sell-in?size=1');if(ownOrders.items[0])await call('an-khang-owner','/orders/sell-in/'+ownOrders.items[0].id,'GET',undefined,[403,404]);
 const prices={nppId,outletId:outletRow.id,lines:[line]};
 for(const a of [owner,staff,sales,'nguyen.an','le.phuc',outlet,'outlet-staff'])if(can(a,'price_sellout:view'))await call(a,'/prices/calculate','POST',prices,a==='le.phuc'?[403,404]:200);
 // Sell-in: draft, idempotency, submit, review, approve, shipment, receipt, invoice.
 const siInput={nsxTenantId:nsxId,lines:[line],note:run,reason:run,submit:false};const key=randomUUID();
 let si=await call(owner,'/orders/sell-in','POST',siInput,200,{'Idempotency-Key':key});
 if(si.id){const duplicate=await call(owner,'/orders/sell-in','POST',siInput,200,{'Idempotency-Key':key});logs.push({check:'sell-in idempotency',pass:duplicate.id===si.id,id:si.id});
  si=await call(owner,'/orders/sell-in/'+si.id+'/submit','POST',{reason:run});
  if(si.status==='SUBMITTED')si=await call('nsx-order','/orders/sell-in/'+si.id+'/review','POST',{reason:run,version:si.version});
  if(si.status==='PENDING_APPROVAL')si=await call(leader,'/orders/sell-in/'+si.id+'/approve','POST',{reason:run,version:si.version});
  if(si.status==='HELD_CREDIT')si=await call(leader,'/orders/sell-in/'+si.id+'/approve-over-limit','POST',{reason:run,version:si.version});
  if(si.status==='APPROVED')si=await call('nsx-order','/orders/sell-in/'+si.id+'/shipment','POST',{reason:run,version:si.version,lines:si.lines.map(l=>({orderLineId:l.id,quantity:l.orderedQty}))});
  if(si.status==='SHIPPED')si=await call(owner,'/orders/sell-in/'+si.id+'/receipt','POST',{reason:run,version:si.version,lines:si.lines.map(l=>({orderLineId:l.id,receivedQty:l.shippedQty,damagedQty:0}))});
  await call(owner,'/invoices?size=100');
 }
 // Credit hold and disallowed ASM approval. Reject the test order afterwards.
 let held=await call(owner,'/orders/sell-in','POST',{...siInput,lines:[{...line,quantity:10000}],submit:true},200,{'Idempotency-Key':randomUUID()});
 if(held.id){await call('tran.my','/orders/sell-in/'+held.id+'/approve-over-limit','POST',{reason:run,version:held.version},403);await call(leader,'/orders/sell-in/'+held.id+'/reject','POST',{reason:run+' kết thúc thử vượt hạn mức',version:held.version,needInfo:false});}
 // Sell-out: sales creates, distributor fulfills/ships, outlet receives shortage and requests replacement.
 let so=await call(sales,'/orders/sell-out','POST',{nppTenantId:nppId,outletId:outletRow.id,lines:[{...line,quantity:2}],reason:run,note:run,submit:true},200,{'Idempotency-Key':randomUUID()});
 if(so.id){
  const path='/orders/sell-out/'+so.id;
  await call('an-khang-owner',path,'GET',undefined,[403,404]);
  so=await call(staff,path+'/fulfillment','POST',{version:so.version,reason:run,lines:so.lines.map(l=>({orderLineId:l.id,quantity:l.orderedQty})),expectedDeliveryAt:null});
  if(so.id)so=await call(staff,path+'/shipment','POST',{version:so.version,reason:run,lines:so.lines.map(l=>({orderLineId:l.id,quantity:l.confirmedQty}))});
  if(so.id)so=await call(outlet,path+'/receipt','POST',{version:so.version,reason:run,attachmentIds:[],lines:so.lines.map(l=>({orderLineId:l.id,receivedQty:l.shippedQty-1,damagedQty:0}))});
  if(so.id){const ret=await call(outlet,path+'/returns','POST',{requestType:'REPLACEMENT',reason:run,lines:so.lines.map(l=>({orderLineId:l.id,quantity:1}))});
   if(ret.id){let current=await call(outlet,path);let rr=await call(owner,'/returns/'+ret.id+'/decision','POST',{approved:true,resolution:run,version:ret.version,orderVersion:current.version});
    const list=await call(owner,path+'/returns');const r=list.find?.(r=>r.id===ret.id);
    if(r){await call(staff,'/returns/'+ret.id+'/replacement-shipment','POST',{version:r.version,reason:run});const after=await call(outlet,path+'/returns');const row=after.find(r=>r.id===ret.id);await call(outlet,'/returns/'+ret.id+'/replacement-receipt','POST',{version:row.version,reason:run});await call(outlet,path);}
   }
  }
 }
 // Outlet owner and staff create their own drafts and cancel them.
 for(const a of [outlet,'outlet-staff',staff])if(can(a,'order_so:create')){const draft=await call(a,'/orders/sell-out','POST',{nppTenantId:nppId,outletId:outletRow.id,lines:[line],reason:run,note:run,submit:false},200,{'Idempotency-Key':randomUUID()});if(draft.id)await call(a,'/orders/sell-out/'+draft.id+'/cancel','POST',{version:draft.version,reason:run});}
 // Maker-checker for product pricing, using a test-only draft price list.
 const pl=await call('nsx-admin','/price-lists','POST',{code:run,name:run,reason:run,lines:[{productId:product.id,unitCode:unit,sellinPriceVnd:86000,selloutPriceVnd:92000}],scopes:[{scopeType:'NPP',scopeId:nppId}]});
 if(pl.id){let p=await call('nsx-admin','/price-lists/'+pl.id+'/submit','POST',{reason:run,version:pl.version});await call('nsx-admin','/price-lists/'+pl.id+'/approve','POST',{reason:run,version:p.version},[403,409]);await call(leader,'/price-lists/'+pl.id+'/reject','POST',{reason:run+' kết thúc kiểm tra',version:p.version});}
 for(const a of accounts)if(sessions[a])await http.post(base+'/auth/logout',{data:{refreshToken:sessions[a].refreshToken,deviceId:sessions[a].deviceId}});
 await save();
}catch(e){logs.push({fatal:e.message,stack:e.stack});await save();console.error(e);}finally{await http.dispose();}
