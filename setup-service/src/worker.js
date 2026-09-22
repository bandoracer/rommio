import { createRemoteJWKSet, jwtVerify } from "jose";

const TTL = 600;
const alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
const random = n => [...crypto.getRandomValues(new Uint8Array(n))].map(b => alphabet[b % alphabet.length]).join("");
const json = (body, status = 200) => new Response(JSON.stringify(body), { status, headers: {
  "Content-Type": "application/json", "Cache-Control": "no-store", "Referrer-Policy": "no-referrer",
}});
const escapeHTML = s => String(s).replace(/[&<>"']/g, c => ({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));

// One serialized owner makes approval, cancellation, and completion atomic.
// Delivery is replayable to the same polling secret until the app acknowledges
// durable storage; a dropped HTTP response must not lose the configuration.
export class CodeStore {
  constructor(ctx) {
    this.ctx = ctx;
    this.sql = ctx.storage.sql;
    this.sql.exec(`CREATE TABLE IF NOT EXISTS codes(code TEXT PRIMARY KEY, secret TEXT, nonce TEXT,
      device TEXT, state TEXT, expires INTEGER)`);
    this.sql.exec(`CREATE TABLE IF NOT EXISTS limits(key TEXT PRIMARY KEY, count INTEGER, expires INTEGER)`);
  }
  cleanup() {
    this.sql.exec("DELETE FROM codes WHERE expires <= ?", Date.now());
    this.sql.exec("DELETE FROM limits WHERE expires <= ?", Date.now());
  }
  async alarm() { this.cleanup(); }
  async fetch(request) {
    this.cleanup();
    const path = new URL(request.url).pathname;
    const data = await request.json();
    if (path === "/start") {
      const limit = this.sql.exec("SELECT count FROM limits WHERE key = ?", data.rateKey).toArray()[0];
      if (limit?.count >= 10) return json({ error: "Too many setup attempts. Try again in a minute." }, 429);
      this.sql.exec(`INSERT INTO limits VALUES (?, 1, ?) ON CONFLICT(key) DO UPDATE SET count=count+1`, data.rateKey, Date.now()+60000);
      let code;
      do { code = random(6); } while(this.sql.exec("SELECT code FROM codes WHERE code = ?", code).toArray().length);
      const secret = random(32), nonce = random(32);
      this.sql.exec("INSERT INTO codes VALUES (?, ?, ?, ?, 'pending', ?)", code, secret, nonce, data.deviceName, Date.now()+TTL*1000);
      await this.ctx.storage.setAlarm(Date.now() + TTL*1000);
      return json({ code, secret });
    }
    const row = this.sql.exec("SELECT * FROM codes WHERE code = ?", data.code).toArray()[0];
    if (!row) return json({ state: "expired" }, 404);
    if (path === "/info") return json({state:row.state, deviceName:row.device, nonce:row.nonce});
    if (path === "/approve" || path === "/deny") {
      if (data.nonce !== row.nonce) return json({error:"Invalid approval"},403);
      if (row.state !== "pending") return json({state:row.state},409);
      const state = path === "/approve" ? "approved" : "denied";
      this.sql.exec("UPDATE codes SET state = ? WHERE code = ?", state, data.code);
      return json({state});
    }
    if (data.secret !== row.secret) return json({state:"forbidden"},403);
    if (path === "/cancel" || (path === "/complete" && row.state === "approved")) {
      this.sql.exec("DELETE FROM codes WHERE code = ?", data.code);
      return json({state:"complete"});
    }
    if (path === "/poll") return json({state:row.state});
    return json({error:"Not found"},404);
  }
}

const keySets = new Map();
export async function verifyIdentity(request, env, keyResolver) {
  const issuer = `https://${env.ACCESS_TEAM_DOMAIN}`;
  const token = request.headers.get("Cf-Access-Jwt-Assertion");
  if (!token || !env.ACCESS_AUDIENCE) return null;
  try {
    if (!keyResolver) {
      if (!keySets.has(issuer)) keySets.set(issuer, createRemoteJWKSet(new URL(`${issuer}/cdn-cgi/access/certs`)));
      keyResolver = keySets.get(issuer);
    }
    const {payload} = await jwtVerify(token, keyResolver, {
      issuer, audience: env.ACCESS_AUDIENCE, algorithms: ["RS256"], requiredClaims:["exp","sub","email"],
    });
    return typeof payload.email === "string" && payload.email.length ? payload : null;
  } catch { return null; }
}

function page(title, content) {
  return new Response(`<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>${title} · Rommio</title><style>body{font:17px system-ui;background:#10131a;color:#f4f5f8;margin:0;padding:28px}main{max-width:440px;margin:12vh auto}h1{font-size:30px}p{line-height:1.5;color:#bfc6d5}code{font-size:28px;letter-spacing:5px}button{font:inherit;font-weight:600;padding:16px;border:0;border-radius:12px;background:#87a6ff;color:#10131a;width:100%;margin:8px 0}.secondary{background:#2b3240;color:white}</style><main><h1>${title}</h1>${content}</main></html>`, {headers:{
    "Content-Type":"text/html; charset=utf-8", "Cache-Control":"no-store", "Referrer-Policy":"same-origin",
    "Content-Security-Policy":"default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'",
    "X-Content-Type-Options":"nosniff",
  }});
}

export function createWorker(verify = verifyIdentity) {
  return {async fetch(request, env) {
    const url = new URL(request.url);
    const store = (path, data) => env.CODE_STORE.get(env.CODE_STORE.idFromName("codes")).fetch(`https://store${path}`, {
      method:"POST", body:JSON.stringify(data),
    });
    if (url.origin === env.PUBLIC_ORIGIN && url.pathname.startsWith("/device/")) {
      if (request.method !== "POST") return json({error:"Use POST"},405);
      if (Number(request.headers.get("Content-Length")) > 4096) return json({error:"Request too large"},413);
      let data;
      try { const body = await request.text(); if(body.length > 4096) return json({error:"Request too large"},413); data = JSON.parse(body); }
      catch { return json({error:"Invalid request"},400); }
      if (!data || typeof data !== "object") return json({error:"Invalid request"},400);
      const action = url.pathname.slice(8);
      if (action === "start") {
        if (!env.CF_ACCESS_CLIENT_ID || !env.CF_ACCESS_CLIENT_SECRET) return json({error:"Setup is not configured"},503);
        const rateKey = [...new Uint8Array(await crypto.subtle.digest("SHA-256",new TextEncoder().encode(request.headers.get("CF-Connecting-IP") || "unknown")))].map(b=>b.toString(16).padStart(2,"0")).join("");
        const result = await store("/start",{deviceName:String(data.deviceName || "Rommio device").slice(0,64),rateKey});
        if (!result.ok) return result;
        const pairing = await result.json();
        return json({...pairing, approveUrl:`${env.APPROVAL_ORIGIN}/a/${pairing.code}`, expiresInSeconds:TTL, pollIntervalSeconds:3});
      }
      if (!["poll","complete","cancel"].includes(action) || !/^[A-Z2-9]{6}$/.test(data.code || "") || !/^[A-Z2-9]{32}$/.test(data.secret || "")) return json({error:"Invalid request"},400);
      const result = await store(`/${action}`,data);
      if (!result.ok || action !== "poll") return result;
      const state = await result.json();
      if (state.state !== "approved") return json(state);
      if (!env.CF_ACCESS_CLIENT_ID || !env.CF_ACCESS_CLIENT_SECRET) return json({error:"Setup is not configured"},503);
      return json({state:"approved",rommUrl:env.ROMM_URL,accessProfile:{type:"serviceToken",clientId:env.CF_ACCESS_CLIENT_ID,clientSecret:env.CF_ACCESS_CLIENT_SECRET}});
    }
    const match = url.pathname.match(/^\/a\/([A-Z2-9]{6})(?:\/(approve|deny))?$/);
    if (url.origin !== env.APPROVAL_ORIGIN || !match) return json({error:"Not found"},404);
    const identity = await verify(request,env);
    if (!identity) { console.warn("Approval rejected: invalid Access identity"); return json({error:"Sign in through Cloudflare Access"},403); }
    const code = match[1];
    if (request.method === "GET" && !match[2]) {
      const result = await store("/info",{code});
      if(!result.ok) return page("Code expired","<p>Return to Rommio and start setup again.</p>");
      const info = await result.json();
      if(info.state !== "pending") return page("Request handled","<p>Return to Rommio to continue.</p>");
      return page("Set up Rommio",`<p>Only approve if this code is shown on your device.</p><p><code>${code}</code></p><p>${escapeHTML(info.deviceName)}</p><p>This gives this device access to your RomM server through Cloudflare. You’ll sign in to RomM in the app.</p><form method="post"><input type="hidden" name="nonce" value="${info.nonce}"><button formaction="/a/${code}/approve">Approve this device</button><button class="secondary" formaction="/a/${code}/deny">Deny</button></form>`);
    }
    if (request.method === "POST" && match[2]) {
      if(request.headers.get("Origin") !== env.APPROVAL_ORIGIN) { console.warn("Approval rejected: origin " + (request.headers.get("Origin") === "null" ? "null" : request.headers.has("Origin") ? "mismatch" : "missing")); return json({error:"Invalid origin"},403); }
      let form; try {form = await request.formData();} catch {return json({error:"Invalid form"},400);}
      const result = await store(`/${match[2]}`,{code,nonce:form.get("nonce")});
      if(!result.ok) return page("Request expired or handled","<p>Return to Rommio and start again if needed.</p>");
      return page(match[2] === "approve" ? "Device approved" : "Request denied","<p>You can return to Rommio now.</p>");
    }
    return json({error:"Method not allowed"},405);
  }};
}
export default createWorker();
