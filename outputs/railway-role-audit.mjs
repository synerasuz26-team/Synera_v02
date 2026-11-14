import { chromium } from '@playwright/test';
import fs from 'node:fs/promises';
const base='https://genuine-peace-production.up.railway.app';
const out='outputs/railway-audit-2026-11-15';
await fs.mkdir(out,{recursive:true});
const accounts=['chi.mai','an-khang-owner','chi.lan','nsx-leader','nsx-sup','nsx-admin','nsx-order','nsx-cs','nsx-trade','nsx-accounting','npp-staff','npp-accounting','outlet-staff','sys-admin','le.quang','tran.my','tran.ha','nguyen.an','le.phuc'];
const browser=await chromium.launch({channel:'msedge',headless:true});
const results=await fs.readFile(out+'/scan.json','utf8').then(JSON.parse).then(rs=>rs.filter(r=>r.me)).catch(()=>[]);
const todo=accounts.filter(a=>!results.some(r=>r.account===a+'@demo.synera.local'));
try {
async function audit(account){
 if(await fs.access(out+'/STOP').then(()=>true,()=>false))return;
 const context=await browser.newContext({viewport:{width:1440,height:1000}});
 const page=await context.newPage();
 page.setDefaultTimeout(7000);
 const result={account:account+'@demo.synera.local',requests:[],screens:[],errors:[]};
 let pending=[];
 page.on('pageerror',e=>result.errors.push(e.message));
 page.on('response',r=>{if(r.url().includes('/api/'))pending.push((async()=>{const item={method:r.request().method(),url:r.url(),status:r.status()};if(r.url().endsWith('/auth/me')&&r.ok())result.me=await r.json();else if(r.status()>=400)item.body=await r.text();result.requests.push(item);})().catch(()=>{}));});
 page.on('requestfailed',r=>result.errors.push({url:r.url(),failure:r.failure()}));
 await page.goto(base+'/#/login');
 await page.locator('input[type=email]').waitFor({timeout:45000});
 await page.locator('input[type=email]').fill(result.account);
 await page.locator('input[type=password]').fill(process.env.SYNERA_QA_PASSWORD);
 await page.getByRole('button',{name:'Đăng nhập',exact:true}).click();
 await page.waitForTimeout(2200);
 await Promise.all(pending);pending=[];
 result.url=page.url();
 result.loginText=await page.locator('body').innerText();
 if(result.me){
  const nav=page.locator('nav[aria-label="Chức năng"]');
  result.menus=await nav.getByRole('button').allTextContents();
  for(const menu of result.menus){
   try {
    await nav.getByRole('button',{name:menu,exact:true}).click();
    await page.waitForTimeout(850);
    const screen={menu,text:await page.locator('main').innerText()};
    const detail=page.getByRole('button',{name:'Chi tiết',exact:true}).first();
    if(await detail.isVisible().catch(()=>false)){
     await detail.click();await page.waitForTimeout(700);
     screen.detail=await page.getByRole('dialog').innerText().catch(()=>page.locator('main').innerText());
     const close=page.getByRole('button',{name:'Đóng',exact:true}).first();if(await close.isVisible().catch(()=>false))await close.click();
    }
    result.screens.push(screen);
    console.log(account+': '+menu);
    if(/không thể|không có quyền|lỗi|không hợp lệ/i.test(screen.text))await page.screenshot({path:out+'/'+account+'-'+result.screens.length+'.png',fullPage:true});
   }catch(e){result.errors.push({menu,message:e.message});await page.keyboard.press('Escape');}
  }
  await page.screenshot({path:out+'/'+account+'.png',fullPage:true});
  await page.getByRole('button',{name:'Đăng xuất',exact:true}).click();await page.waitForTimeout(450);
  result.logoutUrl=page.url();
 }
 await Promise.all(pending);
 results.push(result);await fs.writeFile(out+'/scan.json',JSON.stringify(results,null,2));
 console.log(JSON.stringify({account,roles:result.me?.roles.map(r=>r.code),url:result.url,menus:result.menus?.length,failed:result.requests.filter(r=>r.status>=400),errors:result.errors}));
 await context.close();
}
for(let i=0;i<todo.length;i+=3)await Promise.all(todo.slice(i,i+3).map(audit));
}finally{await browser.close();}
