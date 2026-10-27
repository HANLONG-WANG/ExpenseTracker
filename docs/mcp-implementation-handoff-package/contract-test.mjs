process.on('uncaughtException',e=>{console.error((e.stack||String(e)).replace(/data:text\/javascript;base64,[A-Za-z0-9+/=]+/g,'<module>'));process.exit(1);});
import {readFileSync} from 'node:fs';
import {fileURLToPath} from 'node:url';
process.chdir(fileURLToPath(new URL('./reference/', import.meta.url)));
import {stripTypeScriptTypes} from 'node:module';
import {DatabaseSync} from 'node:sqlite';
import assert from 'node:assert/strict';
const database=new DatabaseSync(':memory:');
database.exec(`
CREATE TABLE tasks(id TEXT PRIMARY KEY,owner TEXT,type TEXT,title TEXT,instructions TEXT,status TEXT,context TEXT,inputRevision INTEGER,idempotency TEXT,requestHash TEXT,createdAt TEXT,updatedAt TEXT,UNIQUE(owner,idempotency));
CREATE TABLE files(id TEXT PRIMARY KEY,taskId TEXT,owner TEXT,role TEXT,name TEXT,mime TEXT,size INTEGER,sha256 TEXT,objectKey TEXT,createdAt TEXT,UNIQUE(taskId,role,name,sha256));
CREATE TABLE results(id TEXT PRIMARY KEY,taskId TEXT,owner TEXT,version INTEGER,inputRevision INTEGER,fileId TEXT,summary TEXT,validation TEXT,state TEXT,createdAt TEXT,UNIQUE(taskId,version));
CREATE TABLE events(id TEXT PRIMARY KEY,taskId TEXT,owner TEXT,message TEXT,createdAt TEXT);
CREATE TABLE mcp_subscriptions(id TEXT PRIMARY KEY,owner TEXT,url TEXT,taskType TEXT,secret TEXT,previousSecret TEXT,previousUntil INTEGER,expiresAt INTEGER,verifiedAt INTEGER,active INTEGER);
CREATE TABLE mcp_deliveries(id TEXT PRIMARY KEY,subscriptionId TEXT,taskId TEXT,owner TEXT,inputRevision INTEGER,eventId TEXT,payload TEXT,state TEXT,attempts INTEGER,nextAttempt INTEGER,lastError TEXT);
CREATE TABLE devices(id TEXT PRIMARY KEY,owner TEXT,name TEXT,tokenHash TEXT,active INTEGER,createdAt TEXT,lastUsed TEXT);
`);
function statement(query,args=[]){return {bind(...args){return statement(query,args);},async all(){return {results:database.prepare(query).all(...args)};},async first(){return database.prepare(query).get(...args)||null;},async run(){const r=database.prepare(query).run(...args);return {meta:{changes:Number(r.changes)}};}};}
const objects=new Map();
const sha=async bytes=>Buffer.from(await crypto.subtle.digest('SHA-256',bytes)).toString('hex');
const BUCKET={async put(key,body,options={}){const bytes=new Uint8Array(await new Response(body).arrayBuffer());if(options.sha256&&await sha(bytes)!==options.sha256)throw Error('checksum mismatch');objects.set(key,bytes);},async delete(key){objects.delete(key);},async get(key,opts={}){const bytes=objects.get(key);if(!bytes)return null;const part=opts.range?bytes.slice(opts.range.offset,opts.range.offset+opts.range.length):bytes;return {arrayBuffer:async()=>part.slice().buffer,text:async()=>new TextDecoder().decode(part),body:new Response(part).body};}};
globalThis.__env={DB:{prepare:statement,async batch(ops){database.exec('BEGIN');try{const out=[];for(const op of ops)out.push(await op.run());database.exec('COMMIT');return out;}catch(e){database.exec('ROLLBACK');throw e;}}},BUCKET,SITES_SERVICE_ACCESS_TOKEN:'test-platform-only'};
globalThis.FixedLengthStream=class extends TransformStream{constructor(length){super();}};
const moduleURL=text=>'data:text/javascript;base64,'+Buffer.from(stripTypeScriptTypes(text,{mode:'transform'})).toString('base64');
const protocol=moduleURL(readFileSync('lib/protocol.ts','utf8').replace(/import (\w+) from '([^']+)';/g,(_,name,path)=>`const ${name}=${readFileSync('lib/'+path,'utf8')};`));
const zip=moduleURL(readFileSync('lib/zip.ts','utf8'));
let source=readFileSync('lib/service.ts','utf8').replace("import {env} from 'cloudflare:workers';",'const env=globalThis.__env;').replace("'./protocol'",JSON.stringify(protocol)).replace("'./zip'",JSON.stringify(zip)).replace(/import (\w+) from '(\.\/formats\/[^']+)';/g,(_,name,path)=>`const ${name}=${readFileSync('lib/'+path,'utf8')};`);
const eventModule=moduleURL(readFileSync('lib/mcp-events.ts','utf8').replace("import {env} from 'cloudflare:workers';",'const env=globalThis.__env;'));
source=source.replace("'./mcp-events'",JSON.stringify(eventModule));
const eventService=await import(eventModule);
const service=moduleURL(source);
const route=await import(moduleURL(readFileSync('app/mcp/route.ts','utf8').replace("import {env} from 'cloudflare:workers';",'const env=globalThis.__env;').replaceAll("'@/lib/mcp-events'",JSON.stringify(eventModule)).replaceAll("'@/lib/service'",JSON.stringify(service)).replaceAll("'@/lib/protocol'",JSON.stringify(protocol))));

const svc=await import(service);
const routeSource=readFileSync('app/mcp/route.ts','utf8');
const {writeFileSync}=await import('node:fs');
const {runInNewContext}=await import('node:vm');
const defs=runInNewContext(routeSource.match(/const defs=([\s\S]*?);\nexport async/)[1]);
writeFileSync('../tools.json',JSON.stringify({tools:defs},null,2)+'\n');
let count=0;
async function test(name,fn){await fn();count++;console.log('PASS '+name);}
async function rpc(method,params={},opts={}) {
 const headers={'content-type':'application/json',...(opts.owner===null?{}:{'oai-authenticated-user-id':opts.owner||'alice'}),...opts.headers};
 const request=new Request('https://desk.example/mcp',{method:'POST',headers,body:opts.raw??JSON.stringify({jsonrpc:'2.0',id:7,method,params})});
 const response=await route.POST(request);
 return {status:response.status,body:response.status===202?null:await response.json()};
}
async function tool(name,args={},opts={}){return rpc('tools/call',{name,arguments:args},opts);}
function value(r){assert.equal(r.status,200);assert(!r.body.result.isError,JSON.stringify(r.body));return JSON.parse(r.body.result.content[0].text);}
let t=await svc.createTask('alice',{type:'general',title:'Synthetic MCP contract test'},'mcp-test');
const bytes=new TextEncoder().encode('人工资料 ☕ 合成测试文件');
const checksum=await sha(bytes);
const f=await svc.upload('alice',t,new Request('https://desk.example/files?name=source.txt',{method:'POST',body:bytes,headers:{'content-length':String(bytes.length),'x-content-sha256':checksum,'content-type':'text/plain'}}));
await svc.changeStatus('alice',await svc.task('alice',t.id),'queued','');
await test('discover and initialize expose current Sites contract',async()=>{
 let r=await rpc('server/discover');assert.deepEqual(r.body.result.supportedVersions,['2026-07-28']);
 r=await rpc('initialize',{protocolVersion:'unsupported'});assert.equal(r.body.result.protocolVersion,'2026-07-28');
});
await test('tools/list exact six definitions and initialized notification',async()=>{
 const r=await rpc('tools/list');assert.equal(r.body.result.tools.length,6);assert.equal((await rpc('notifications/initialized')).status,202);
});
await test('authentication and cross-origin failure remain transport errors',async()=>{
 assert.equal((await rpc('tools/list',{}, {owner:null})).status,401);
 assert.equal((await rpc('tools/list',{}, {headers:{Authorization:'Bearer synthetic'}})).status,403);
 assert.equal((await rpc('tools/list',{}, {headers:{Origin:'https://other.example'}})).status,403);
});
await test('unknown method differs from unknown tool',async()=>{
 let r=await rpc('unknown');assert.equal(r.status,400);assert.equal(r.body.error.code,-32601);
 r=await tool('unknown');assert.equal(r.status,200);assert.equal(r.body.result.isError,true);
});
await test('task list and detail use JSON text with parsed detail context',async()=>{
 const list=value(await tool('list_analysis_tasks',{status:'queued'}));assert.equal(list.tasks[0].id,t.id);
 const d=value(await tool('get_analysis_task',{taskId:t.id}));assert.equal(d.inputRevision,1);assert.equal(d.files.length,1);assert.equal(typeof d.context,'object');
});
await test('cross-owner task and file access rejected',async()=>{
 for(const [name,args] of [['get_analysis_task',{taskId:t.id}],['read_analysis_file',{fileId:f.fileId}]]){const r=await tool(name,args,{owner:'bob'});assert.equal(r.body.result.isError,true);}
});
await test('byte chunks reassemble UTF8 and whole-file SHA256',async()=>{
 const parts=[];let offset=0;
 for(;;){const c=value(await tool('read_analysis_file',{fileId:f.fileId,offset,length:5}));assert.equal(c.offset,offset);const b=Buffer.from(c.base64,'base64');assert.equal(c.nextOffset,offset+b.length);parts.push(b);offset=c.nextOffset;if(c.eof)break;}
 const merged=Buffer.concat(parts);assert.deepEqual(new Uint8Array(merged),bytes);assert.equal(await sha(merged),checksum);
});
await test('range boundaries and length limits reject invalid values',async()=>{
 for(const args of [{offset:-1},{offset:bytes.length},{length:0},{length:1000001},{offset:0.5}]){const r=await tool('read_analysis_file',{fileId:f.fileId,...args});assert.equal(r.body.result.isError,true);}
});
await test('claim state requires valid transition and needs_info note',async()=>{
 let r=await tool('set_analysis_status',{taskId:t.id,status:'needs_info'});assert.equal(r.body.result.isError,true);
 const d=value(await tool('set_analysis_status',{taskId:t.id,status:'analyzing'}));assert.equal(d.status,'analyzing');
 r=await tool('set_analysis_status',{taskId:t.id,status:'analyzing'});assert.equal(r.body.result.isError,true);
});
await test('stale input cannot submit and general result reaches ready',async()=>{
 let r=await tool('submit_analysis_result',{taskId:t.id,inputRevision:0,content:'done',summary:'test'});assert.equal(r.body.result.isError,true);
 let d=value(await tool('submit_analysis_result',{taskId:t.id,inputRevision:1,content:'synthetic result',summary:'Synthetic only'}));assert.equal(d.status,'review');assert.equal(d.results[0].state,'draft');assert(d.results[0].validation.warnings.length);
 d=value(await tool('publish_analysis_result',{taskId:t.id,resultId:d.results[0].id}));assert.equal(d.status,'ready');assert.equal(d.results[0].state,'ready');
});
await test('invalid receipt saved as review but cannot publish',async()=>{
 const rtask=await svc.createTask('alice',{type:'receipt',title:'Synthetic receipt'},'receipt-test');
 database.prepare("UPDATE tasks SET status='analyzing' WHERE id=?").run(rtask.id);
 const d=value(await tool('submit_analysis_result',{taskId:rtask.id,inputRevision:0,content:'{}',summary:'synthetic malformed receipt'}));assert(d.results[0].validation.errors.length);assert.equal(d.status,'review');
 const r=await tool('publish_analysis_result',{taskId:rtask.id,resultId:d.results[0].id});assert.equal(r.body.result.isError,true);
});
await test('invalid JSON and total request size fail outside tool envelope',async()=>{
 let r=await rpc('tools/list',{}, {raw:'{'});assert.equal(r.status,400);assert.equal(r.body.error.code,-32000);
 r=await rpc('tools/list',{}, {raw:' '.repeat(2500001)});assert.equal(r.status,413);
});
await test('MCP event subscription verifies signed callback, refreshes, and isolates owners',async()=>{
 const {createHmac}=await import('node:crypto');
 const secret='whsec_'+Buffer.alloc(32,7).toString('base64');
 const sent=[];globalThis.fetch=async(url,init)=>{
   const body=JSON.parse(init.body);sent.push(body);
   const h=init.headers;const expected='v1,'+createHmac('sha256',Buffer.alloc(32,7)).update(`${h['webhook-id']}.${h['webhook-timestamp']}.${init.body}`).digest('base64');
   assert.equal(h['webhook-signature'],expected);assert.equal(init.redirect,'manual');
   return Response.json(body.type==='verification'?{challenge:body.challenge}:{});
 };
 const params={name:'analysis_task.submitted',arguments:{type:'general'},delivery:{mode:'webhook',url:'https://connectors.api.openai.com/mcp-events/synthetic',secret}};
 assert.equal((await rpc('events/list')).body.result.events[0].name,params.name);
 assert.deepEqual((await rpc('server/discover')).body.result.capabilities.events,{});
 const subscription=(await rpc('events/subscribe',params)).body.result;assert(subscription.id);assert.equal(sent.length,1);
 assert.equal((await rpc('events/subscribe',params)).body.result.id,subscription.id);assert.equal(sent.length,1);
 await rpc('events/unsubscribe',params,{owner:'bob'});assert.equal(database.prepare('SELECT COUNT(*) n FROM mcp_subscriptions').get().n,1);
 const bad=await rpc('events/subscribe',{...params,delivery:{...params.delivery,url:'https://127.0.0.1/secret'}});assert.equal(bad.body.error.code,-32015);
 const pending=await svc.createTask('alice',{type:'general',title:'Event synthetic task'},'event-test');
 const uploadBytes=new TextEncoder().encode('source');await svc.upload('alice',pending,new Request('https://desk.example/files?name=test.txt',{method:'POST',body:uploadBytes,headers:{'content-length':String(uploadBytes.length),'x-content-sha256':await sha(uploadBytes)}}));
 assert.equal(database.prepare('SELECT COUNT(*) n FROM mcp_deliveries').get().n,0);
 await svc.changeStatus('alice',await svc.task('alice',pending.id),'queued','');
 assert.equal(database.prepare('SELECT COUNT(*) n FROM mcp_deliveries').get().n,1);
 await eventService.deliverPending();assert.equal(sent[1].data.taskId,pending.id);assert.equal(sent[1].data.inputRevision,1);
 assert.equal(database.prepare('SELECT state FROM mcp_deliveries').get().state,'delivered');
 await eventService.deliverPending();assert.equal(sent.length,2);
 await svc.changeStatus('alice',await svc.task('alice',pending.id),'analyzing','');
 await svc.changeStatus('alice',await svc.task('alice',pending.id),'queued','');
 assert.equal(database.prepare('SELECT COUNT(*) n FROM mcp_deliveries').get().n,1);
 await rpc('events/unsubscribe',params);await rpc('events/unsubscribe',params);
 assert.equal(database.prepare('SELECT COUNT(*) n FROM mcp_subscriptions').get().n,0);
 let redirectCalls=0;
 globalThis.fetch=async(url,init)=>{redirectCalls++;assert.equal(init.redirect,'manual');return new Response(null,{status:302,headers:{Location:'https://example.com/redirected'}});};
 const redirected=await rpc('events/subscribe',params);
 assert.equal(redirected.body.error.code,-32015);assert.equal(redirectCalls,1);
 assert.equal(database.prepare('SELECT COUNT(*) n FROM mcp_subscriptions').get().n,0);
});
await test('webhook retries preserve event ID; permanent failure is recorded',async()=>{
 const id='sub_retry',secret='whsec_'+Buffer.alloc(32,3).toString('base64');
 database.prepare('INSERT INTO mcp_subscriptions VALUES(?,?,?,?,?,?,?,?,?,?)').run(id,'alice','https://api.chatgpt.com/mcp-events/synthetic','general',secret,null,0,Date.now()+60000,Date.now(),1);
 const pending=await svc.createTask('alice',{type:'general',title:'Retry synthetic'},'retry-test');
 database.prepare("UPDATE tasks SET status='queued',inputRevision=1 WHERE id=?").run(pending.id);
 const payload={eventId:'evt_test',name:'analysis_task.submitted',timestamp:new Date().toISOString(),data:{taskId:pending.id,inputRevision:1,type:'general'},cursor:null};
 database.prepare('INSERT INTO mcp_deliveries VALUES(?,?,?,?,?,?,?,?,?,?,?)').run('delivery_retry',id,pending.id,'alice',1,'evt_test',JSON.stringify(payload),'pending',0,0,null);
 const calls=[];globalThis.fetch=async(url,init)=>{calls.push(JSON.parse(init.body).eventId);return new Response('',{status:calls.length===1?503:200});};
 await eventService.deliverPending();assert.deepEqual(calls,['evt_test','evt_test']);
 database.prepare("UPDATE mcp_deliveries SET state='pending',attempts=0,nextAttempt=0 WHERE id='delivery_retry'").run();
 let redirectCalls=0;
 globalThis.fetch=async(url,init)=>{redirectCalls++;assert.equal(init.redirect,'manual');return new Response(null,{status:307,headers:{Location:'https://example.com/redirected'}});};
 await eventService.deliverPending();assert.equal(redirectCalls,1);
 const redirected=database.prepare("SELECT state,lastError FROM mcp_deliveries WHERE id='delivery_retry'").get();
 assert.equal(redirected.state,'failed');assert.equal(redirected.lastError,'webhook 307');
 database.prepare("UPDATE mcp_deliveries SET state='pending',attempts=0,nextAttempt=0 WHERE id='delivery_retry'").run();
 globalThis.fetch=async()=>new Response('',{status:410});await eventService.deliverPending();
 assert.equal(database.prepare("SELECT state FROM mcp_deliveries WHERE id='delivery_retry'").get().state,'failed');
 assert.equal(database.prepare('SELECT active FROM mcp_subscriptions WHERE id=?').get(id).active,0);
 assert(database.prepare("SELECT message FROM events WHERE taskId=? AND message LIKE '自动通知%' ").get(pending.id));
});
console.log(`${count} contract checks passed; isolated SQLite/R2 only. Hosted gateway/OAuth not simulated.`);
