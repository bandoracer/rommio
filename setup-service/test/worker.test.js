import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { Miniflare, convertV4MiniflareOptions } from 'miniflare';
import { build } from 'esbuild';
import { generateKeyPair, SignJWT } from 'jose';
import { verifyIdentity } from '../src/worker.js';

let mf;
const publicOrigin = 'https://setup.example.com';
const approvalOrigin = 'https://approve.example.com';
before(async () => {
  // Only the test bundle injects an identity verifier. Production has no bypass.
  const bundle = await build({stdin:{contents:`import {createWorker, CodeStore as BaseStore} from './src/worker.js'; export class CodeStore extends BaseStore { async fetch(r) { if (new URL(r.url).pathname === '/test-expire') {this.sql.exec('UPDATE codes SET expires = 0'); return new Response('ok')} return super.fetch(r); } } export default createWorker(async r => r.headers.get('X-Test-Identity') === 'yes' ? {email:'test@example.com'} : null);`,resolveDir:process.cwd()},bundle:true,write:false,format:'esm',platform:'browser'});
  mf = new Miniflare(convertV4MiniflareOptions({workers:[{name:"setup",modules:true,script:bundle.outputFiles[0].text,compatibilityDate:'2026-09-01',durableObjects:{CODE_STORE:{className:'CodeStore',useSQLite:true}},bindings:{PUBLIC_ORIGIN:publicOrigin,APPROVAL_ORIGIN:approvalOrigin,ROMM_URL:'https://romm.example.com',CF_ACCESS_CLIENT_ID:'test-id',CF_ACCESS_CLIENT_SECRET:'test-secret'}}]}));
});
after(async()=>{await mf?.dispose()});
const post = (path,body,ip=crypto.randomUUID()) => mf.dispatchFetch(publicOrigin+path,{method:'POST',headers:{'Content-Type':'application/json','CF-Connecting-IP':ip},body:JSON.stringify(body)});
const start = async (deviceName='Phone',ip) => {const r = await post('/device/start',{deviceName},ip); assert.equal(r.status,200);return r.json()};
async function approve(pairing,action='approve') {
  const page = await mf.dispatchFetch(pairing.approveUrl,{headers:{'X-Test-Identity':'yes'}});
  assert.equal(page.headers.get("Referrer-Policy"),"same-origin");
  const html=await page.text();
  const nonce=html.match(/name="nonce" value="([A-Z2-9]+)"/)[1];
  return mf.dispatchFetch(pairing.approveUrl+'/'+action,{method:'POST',headers:{'X-Test-Identity':'yes',Origin:approvalOrigin,'Content-Type':'application/x-www-form-urlencoded'},body:new URLSearchParams({nonce}).toString()});
}

test('pending code requires identity and the device polling secret',async()=>{
 const p=await start();
 assert.equal((await mf.dispatchFetch(p.approveUrl)).status,403);
 assert.equal((await mf.dispatchFetch(publicOrigin+'/a/'+p.code,{headers:{'X-Test-Identity':'yes'}})).status,404);
 assert.equal((await post('/device/poll',{...p,secret:'B'.repeat(32)})).status,403);
 assert.deepEqual(await (await post('/device/poll',p)).json(),{state:'pending'});
});
test('approved payload survives a lost response until acknowledgement',async()=>{
 const p=await start(); assert.equal((await approve(p)).status,200);
 const results=await Promise.all([post('/device/poll',p),post('/device/poll',p)]);
 for(const result of results){const value=await result.json(); assert.equal(value.accessProfile.clientSecret,'test-secret');assert.equal(result.headers.get('Cache-Control'),'no-store')}
 assert.equal((await post('/device/complete',p)).status,200);
 assert.equal((await post('/device/poll',p)).status,404);
});
test('denial and cancellation never release credentials',async()=>{
 const denied=await start(); await approve(denied,'deny');
 assert.deepEqual(await (await post('/device/poll',denied)).json(),{state:'denied'});
 const cancelled=await start(); await post('/device/cancel',cancelled);
 assert.equal((await post('/device/poll',cancelled)).status,404);
});
test('approval rejects forged form and foreign origin; names are escaped',async()=>{
 const p=await start('<script>alert("x")</script>');
 const html=await(await mf.dispatchFetch(p.approveUrl,{headers:{'X-Test-Identity':'yes'}})).text();
 assert.ok(html.includes('&lt;script&gt;')); assert.ok(!html.includes('<script>'));
 for(const origin of [approvalOrigin,'https://evil.example.com']){
  const r=await mf.dispatchFetch(p.approveUrl+'/approve',{method:'POST',headers:{'X-Test-Identity':'yes',Origin:origin,'Content-Type':'application/x-www-form-urlencoded'},body:'nonce=wrong'});
  if(origin!==approvalOrigin)assert.equal(r.status,403);
 }
 assert.equal((await(await post('/device/poll',p)).json()).state,'pending');
});
test('expired codes are unavailable and start is rate limited',async()=>{
 const p=await start();
 const ns=await mf.getDurableObjectNamespace('CODE_STORE');
 await ns.get(ns.idFromName('codes')).fetch('https://store/test-expire');
 assert.equal((await post('/device/poll',p)).status,404);
 const ip=crypto.randomUUID();for(let i=0;i<10;i++)await start('Phone',ip);
 assert.equal((await post('/device/start',{deviceName:'Phone'},ip)).status,429);
});
test('Access JWT verifies signature, audience, issuer, expiry and user identity',async()=>{
 const {publicKey,privateKey}=await generateKeyPair('RS256');
 const env={ACCESS_TEAM_DOMAIN:'team.cloudflareaccess.com',ACCESS_AUDIENCE:'setup-audience'};
 for(const [overrides,valid] of [[{},true],[{aud:'other-app'},false],[{iss:'https://other.cloudflareaccess.com'},false],[{exp:1},false],[{email:undefined},false]]) {
  const claims={iss:'https://team.cloudflareaccess.com',aud:'setup-audience',sub:'user',email:'test@example.com',exp:Math.floor(Date.now()/1000)+300,...overrides};
  const token=await new SignJWT(claims).setProtectedHeader({alg:'RS256'}).sign(privateKey);
  const request=new Request(approvalOrigin,{headers:{'Cf-Access-Jwt-Assertion':token}});
  assert.equal(Boolean(await verifyIdentity(request,env,publicKey)),valid);
 }
 assert.equal(await verifyIdentity(new Request(approvalOrigin,{headers:{'Cf-Access-Jwt-Assertion':'invalid'}}),env,publicKey),null);
});
