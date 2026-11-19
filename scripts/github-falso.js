// GitHub simulado para scripts/pruebas.mjs: un repositorio en memoria (qa/boveda) con la parte de la API que usa la app
// para sincronizar (rama, commits, árboles y blobs). Se inyecta en la página y sustituye a fetch solo para ese repositorio.
// Imita también la caché del navegador: GitHub responde «Cache-Control: max-age=60», así que una lectura repetida en menos
// de un minuto sale de la caché y no ve lo que ha cambiado, salvo que se pida con cache:'no-store'.
(() => {
  const REPO = 'https://api.github.com/repos/qa/boveda', real = window.fetch, enc = new TextEncoder();
  const hex = b => [...new Uint8Array(b)].map(x => x.toString(16).padStart(2, '0')).join('');
  const un64 = s => Uint8Array.from(atob(String(s).replace(/\s/g, '')), c => c.charCodeAt(0));
  const b64 = u => { let s = ''; for (let i = 0; i < u.length; i += 8192) s += String.fromCharCode.apply(null, u.subarray(i, i + 8192)); return btoa(s) };
  const blobSha = async u => { const h = enc.encode('blob ' + u.length + '\0'), a = new Uint8Array(h.length + u.length); a.set(h); a.set(u, h.length); return hex(await crypto.subtle.digest('SHA-1', a)) };
  let n = 0;
  const id = p => p + (++n).toString(16).padStart(36, '0');
  const G = window.__gh = {
    blobs: new Map(), trees: new Map(), subs: new Map(), commits: new Map(), head: '', log: [], guardado: new Map(), alCommit: null,
    async blob(u) { const s = await blobSha(u); G.blobs.set(s, u); return s },
    arbol(m) { const t = id('a0b0'); G.trees.set(t, m); return t },
    commit(tree, parent) { const c = id('c0de'); G.commits.set(c, { tree, parents: parent ? [parent] : [] }); return c },
    actual() { return G.trees.get(G.commits.get(G.head).tree) },
    archivos() { return [...G.actual().keys()].sort() },
    texto(p) { const s = G.actual().get(p); return s ? new TextDecoder().decode(G.blobs.get(s)) : null },
    /* Otro dispositivo sube un archivo: la rama avanza sin que la app lo sepa */
    async ajeno(p, txt) { const m = new Map(G.actual()); m.set(p, await G.blob(enc.encode(txt))); G.head = G.commit(G.arbol(m), G.head) },
    quita() { window.fetch = real; delete window.__gh }
  };
  const subId = (t, dir) => { const k = t + '|' + dir; for (const [s, v] of G.subs) if (v.k === k) return s; const s = id('5b00'); G.subs.set(s, { k, t, dir }); return s };
  /* Entradas de un árbol: sin «recursive» solo el primer nivel, con las carpetas como árboles */
  const lista = (t, dir, rec) => {
    const out = [], vistos = new Set();
    for (const [p, s] of G.trees.get(t)) {
      if (dir && !p.startsWith(dir + '/')) continue;
      const rel = dir ? p.slice(dir.length + 1) : p, parts = rel.split('/');
      for (let i = 1; i < parts.length; i++) {
        const d = parts.slice(0, i).join('/');
        if (vistos.has(d)) continue;
        vistos.add(d);
        if (i === 1 || rec) out.push({ path: d, mode: '040000', type: 'tree', sha: subId(t, (dir ? dir + '/' : '') + d) });
      }
      if (parts.length === 1 || rec) out.push({ path: rel, mode: '100644', type: 'blob', sha: s });
    }
    return out;
  };
  async function atiende(m, ruta, body) {
    let x;
    if (m === 'GET' && ruta === '/git/ref/heads/main') return [200, { ref: 'refs/heads/main', object: { sha: G.head, type: 'commit' } }];
    if (m === 'GET' && (x = ruta.match(/^\/git\/commits\/([0-9a-f]{40})$/))) { const c = G.commits.get(x[1]); return c ? [200, { sha: x[1], tree: { sha: c.tree }, parents: c.parents.map(sha => ({ sha })) }] : [404, { message: 'Not Found' }] }
    if (m === 'GET' && (x = ruta.match(/^\/git\/trees\/([0-9a-f]{40})(\?recursive=1)?$/))) {
      const s = G.subs.get(x[1]);
      if (s) return [200, { sha: x[1], tree: lista(s.t, s.dir, !!x[2]), truncated: false }];
      return G.trees.has(x[1]) ? [200, { sha: x[1], tree: lista(x[1], '', !!x[2]), truncated: false }] : [404, { message: 'Not Found' }];
    }
    if (m === 'GET' && (x = ruta.match(/^\/git\/blobs\/([0-9a-f]{40})$/))) { const u = G.blobs.get(x[1]); return u ? [200, { sha: x[1], encoding: 'base64', content: b64(u), size: u.length }] : [404, { message: 'Not Found' }] }
    if (m === 'POST' && ruta === '/git/blobs') return [201, { sha: await G.blob(body.encoding === 'base64' ? un64(body.content) : enc.encode(body.content)) }];
    if (m === 'POST' && ruta === '/git/trees') {
      const base = body.base_tree ? G.trees.get(body.base_tree) : new Map();
      if (!base) return [422, { message: 'base_tree is not a valid tree oid' }];
      const mm = new Map(base);
      for (const e of body.tree) {
        if (e.content != null) mm.set(e.path, await G.blob(enc.encode(e.content)));
        else if (e.sha === null) mm.delete(e.path);
        else { if (!G.blobs.has(e.sha)) return [422, { message: 'tree.sha ' + e.sha + ' is not a valid blob' }]; mm.set(e.path, e.sha) }
      }
      return [201, { sha: G.arbol(mm) }];
    }
    if (m === 'POST' && ruta === '/git/commits') {
      if (G.alCommit) { const f = G.alCommit; G.alCommit = null; await f() }
      return [201, { sha: G.commit(body.tree, body.parents[0]) }];
    }
    if (m === 'PATCH' && ruta === '/git/refs/heads/main') {
      /* Como GitHub: solo se acepta si la rama actual es antepasada del commit nuevo */
      let c = body.sha, avance = false;
      for (let i = 0; c && i < 10000; i++) { if (c === G.head) { avance = true; break } c = (G.commits.get(c) || { parents: [] }).parents[0] }
      if (!avance) return [422, { message: 'Update is not a fast forward' }];
      G.head = body.sha;
      return [200, { ref: 'refs/heads/main', object: { sha: G.head, type: 'commit' } }];
    }
    return [404, { message: 'Not Found' }];
  }
  window.fetch = async (url, init = {}) => {
    const u = String(url && url.url || url);
    if (!u.startsWith(REPO)) return real(url, init);
    const m = (init.method || 'GET').toUpperCase(), ruta = u.slice(REPO.length), modo = init.cache || 'default', et = () => '"' + G.head + '"';
    const g = m === 'GET' && modo !== 'no-store' && G.guardado.get(ruta);
    let r;
    if (g && Date.now() - g.t < 60000) { r = g.r; G.log.push({ m, ruta, modo, st: r[0], cache: true }) }
    else {
      if (m === 'GET' && ruta === '/git/ref/heads/main' && (init.headers || {})['If-None-Match'] === et()) { G.log.push({ m, ruta, modo, st: 304 }); return new Response(null, { status: 304, headers: { ETag: et() } }) }
      r = await atiende(m, ruta, init.body ? JSON.parse(init.body) : null);
      if (m === 'GET' && modo !== 'no-store' && r[0] === 200) G.guardado.set(ruta, { t: Date.now(), r: JSON.parse(JSON.stringify(r)) });
      G.log.push({ m, ruta, modo, st: r[0] });
    }
    return new Response(JSON.stringify(r[1]), { status: r[0], headers: { 'Content-Type': 'application/json', ETag: et() } });
  };
  G.listo = (async () => { G.head = G.commit(G.arbol(new Map([['README.md', await G.blob(enc.encode('# Notas\n'))]])), null) })();
})();
