// Minimal reader for OsmAnd .obf map data (vector map section only).
// Follows OsmAnd's open-source BinaryMapIndexReader. Runs entirely locally.
const OBF = (() => {
  const TWO31 = 2147483648;
  const lonTo31 = lon => Math.floor((lon + 180) / 360 * TWO31);
  const latTo31 = lat => { const r = lat * Math.PI / 180; return Math.floor((1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2 * TWO31); };
  const x31ToLon = x => x / TWO31 * 360 - 180;
  const y31ToLat = y => { const n = Math.PI * (1 - 2 * y / TWO31); return 180 / Math.PI * Math.atan(Math.sinh(n)); };

  class R {
    constructor(b) { this.b = b; this.p = 0; }
    vu() { let r = 0, s = 1, c; do { c = this.b[this.p++]; r += (c & 127) * s; s *= 128; } while (c & 128); return r; }
    vs() { const v = this.vu(); return (v % 2) ? -(v + 1) / 2 : v / 2; }
    i32() { const b = this.b, p = this.p; this.p += 4; return ((b[p] << 24) | (b[p + 1] << 16) | (b[p + 2] << 8) | b[p + 3]) >>> 0; }
    str(n) { const s = new TextDecoder().decode(this.b.subarray(this.p, this.p + n)); this.p += n; return s; }
    skip(wt) { if (wt === 0) this.vu(); else if (wt === 1) this.p += 8; else if (wt === 2) { const n = this.vu(); this.p += n; } else if (wt === 5) this.p += 4; else throw new Error("bad wire type " + wt); }
  }

  function readRule(r, end, id) {
    let tag = null, val = null, type = 0;
    while (r.p < end) { const t = r.vu(), f = t >>> 3, wt = t & 7;
      if (f === 3) tag = r.str(r.vu()); else if (f === 5) val = r.str(r.vu()); else if (f === 7) id = r.vu(); else if (f === 10) type = r.vu(); else r.skip(wt); }
    return { id, tag, val, type };
  }

  function readLevel(r, end) {
    const L = { boxes: [] };
    while (r.p < end) { const t = r.vu(), f = t >>> 3, wt = t & 7;
      if (f === 1) L.maxZoom = r.vu(); else if (f === 2) L.minZoom = r.vu();
      else if (f === 3) L.left = r.vu(); else if (f === 4) L.right = r.vu(); else if (f === 5) L.top = r.vu(); else if (f === 6) L.bottom = r.vu();
      else if (f === 7) { const n = r.i32(); L.boxes.push({ fp: r.p, len: n }); r.p += n; }
      else if (f === 15) { r.p = end; }
      else r.skip(wt); }
    return L;
  }

  function readIndex(r, end) {
    const idx = { name: "", rules: new Map(), levels: [] }; let defId = 1;
    while (r.p < end) { const t = r.vu(), f = t >>> 3, wt = t & 7;
      if (f === 2) idx.name = r.str(r.vu());
      else if (f === 4) { const n = r.vu(), e = r.p + n; const rule = readRule(r, e, defId++); r.p = e; if (!idx.rules.has(rule.id)) idx.rules.set(rule.id, rule); }
      else if (f === 5) { const n = r.i32(), s = r.p; idx.levels.push(readLevel(r, s + n)); r.p = s + n; }
      else r.skip(wt); }
    return idx;
  }

  function walkBoxes(r, fp, len, parent, q, out) {
    const end = fp + len, cur = { left: 0, right: 0, top: 0, bottom: 0 }; r.p = fp; let init = 0;
    while (r.p < end) {
      if (init === 15) { init = 16; if (cur.right < q.left || cur.left > q.right || cur.top > q.bottom || cur.bottom < q.top) return; }
      const t = r.vu(), f = t >>> 3, wt = t & 7;
      if (f === 1) { cur.left = r.vs() + parent.left; init |= 2; }
      else if (f === 2) { cur.right = r.vs() + parent.right; init |= 4; }
      else if (f === 3) { cur.top = r.vs() + parent.top; init |= 8; }
      else if (f === 4) { cur.bottom = r.vs() + parent.bottom; init |= 1; }
      else if (f === 5) { const sh = r.i32(); out.push({ block: sh + fp, left: cur.left, top: cur.top }); }
      else if (f === 6) r.vu();
      else if (f === 7) { const n = r.i32(), cfp = r.p; const save = r.p + n; walkBoxes(r, cfp, n, cur, q, out); r.p = save; }
      else r.skip(wt);
    }
  }

  function readCoords(r, end, px, py) {
    const pts = [];
    while (r.p < end) { const x = r.vs() * 32 + px, y = r.vs() * 32 + py; pts.push(x, y); px = x; py = y; }
    return pts;
  }

  function readBlock(r, box, idx, q, want, seen, results) {
    r.p = box.block; const n = r.vu(), end = r.p + n; let baseId = 0; const tmp = [];
    const px0 = Math.floor(box.left / 32) * 32, py0 = Math.floor(box.top / 32) * 32;
    while (r.p < end) { const t = r.vu(), f = t >>> 3, wt = t & 7;
      if (f === 10) baseId = r.vu();
      else if (f === 12) {
        const ln = r.vu(), oe = r.p + ln;
        const t0 = r.vu(), f0 = t0 >>> 3; const area = f0 === 2;
        const cl = r.vu(), ce = r.p + cl; const coords = readCoords(r, ce, px0, py0);
        let types = [], add = [], names = null, id = 0, inner = [];
        while (r.p < oe) { const t1 = r.vu(), f1 = t1 >>> 3, w1 = t1 & 7;
          if (f1 === 7 || f1 === 6) { const l = r.vu(), e = r.p + l, arr = []; while (r.p < e) arr.push(r.vu()); if (f1 === 7) types = arr; else add = arr; }
          else if (f1 === 10) { const l = r.vu(), e = r.p + l; names = []; while (r.p < e) { names.push([r.vu(), r.vu()]); } }
          else if (f1 === 12) id = r.vs();
          else if (f1 === 4) { const l = r.vu(); inner.push(readCoords(r, r.p + l, px0, py0)); }
          else r.skip(w1); }
        r.p = oe;
        let keep = false; const tags = {};
        for (const tid of types.concat(add)) { const rule = idx.rules.get(tid); if (!rule) continue; tags[rule.tag] = rule.val; if (want(rule.tag, rule.val)) keep = true; }
        if (keep) {
          let mnx = 1e12, mxx = -1, mny = 1e12, mxy = -1;
          for (let i = 0; i < coords.length; i += 2) { mnx = Math.min(mnx, coords[i]); mxx = Math.max(mxx, coords[i]); mny = Math.min(mny, coords[i + 1]); mxy = Math.max(mxy, coords[i + 1]); }
          if (mxx >= q.left && mnx <= q.right && mxy >= q.top && mny <= q.bottom) tmp.push({ id, area, coords, tags, names, inner });
        }
      }
      else if (f === 15) {
        const ln = r.vu(), se = r.p + ln, table = [];
        while (r.p < se) { const t2 = r.vu(); if ((t2 >>> 3) === 1) table.push(r.str(r.vu())); else r.skip(t2 & 7); }
        for (const o of tmp) if (o.names) { for (const [k, i] of o.names) { const rule = idx.rules.get(k); if (rule && table[i] != null) o.tags[rule.tag] = table[i]; } }
      }
      else r.skip(wt);
    }
    for (const o of tmp) { const key = (o.id + baseId) + ":" + o.coords.length + ":" + o.coords[0]; if (seen.has(key)) continue; seen.add(key);
      results.push({ id: o.id + baseId, area: o.area, tags: o.tags, pts: toLL(o.coords), inner: o.inner.map(toLL) }); }
  }
  const toLL = c => { const a = []; for (let i = 0; i < c.length; i += 2) a.push([y31ToLat(c[i + 1]), x31ToLon(c[i])]); return a; };

  // bbox: {south, west, north, east} in degrees. want(tag, value) -> boolean.
  function extract(bytes, bbox, want, opts) {
    opts = opts || {};
    const r = new R(bytes), indexes = [];
    while (r.p < bytes.length) { const t = r.vu(); if (t === 0) break; const f = t >>> 3, wt = t & 7;
      if (f === 6) { const n = r.i32(), s = r.p; indexes.push(readIndex(r, s + n)); r.p = s + n; }
      else if (f === 4 || f === 7 || f === 8 || f === 9 || f === 10) { const n = r.i32(); r.p += n; }
      else if (f === 1 || f === 18 || f === 32) r.vu();
      else r.skip(wt); }
    const q = { left: lonTo31(bbox.west), right: lonTo31(bbox.east), top: latTo31(bbox.north), bottom: latTo31(bbox.south) };
    const results = [], info = { indexes: indexes.map(i => ({ name: i.name, rules: i.rules.size, levels: i.levels.map(l => [l.minZoom, l.maxZoom]) })), bounds: null };
    for (const idx of indexes) {
      if (!idx.levels.length) continue;
      const lvl = idx.levels.reduce((a, b) => (b.maxZoom > a.maxZoom ? b : a));
      info.bounds = { south: y31ToLat(lvl.bottom), north: y31ToLat(lvl.top), west: x31ToLon(lvl.left), east: x31ToLon(lvl.right) };
      if (lvl.right < q.left || lvl.left > q.right || lvl.top > q.bottom || lvl.bottom < q.top) continue;
      const blocks = [];
      for (const b of lvl.boxes) walkBoxes(r, b.fp, b.len, lvl, q, blocks);
      const uniq = [...new Map(blocks.map(b => [b.block, b])).values()].sort((a, b) => a.block - b.block);
      const seen = new Set();
      for (const b of uniq) readBlock(r, b, idx, q, want, seen, results);
    }
    return { results, info };
  }

  // Reads the .obf map files and .gpx track/favourite files out of an OsmAnd .osf export (a zip). Uses the browser's built-in decompressor.
  async function unzip(buf, all) {
    const b = new Uint8Array(buf), dv = new DataView(buf instanceof ArrayBuffer ? buf : b.buffer);
    let eocd = -1; for (let i = b.length - 22; i >= Math.max(0, b.length - 70000); i--) if (dv.getUint32(i, true) === 0x06054b50) { eocd = i; break; }
    if (eocd < 0) throw new Error("not a zip");
    const cnt = dv.getUint16(eocd + 10, true); let cd = dv.getUint32(eocd + 16, true); const files = [];
    for (let k = 0; k < cnt; k++) {
      const method = dv.getUint16(cd + 10, true), csize = dv.getUint32(cd + 20, true), nlen = dv.getUint16(cd + 28, true), xlen = dv.getUint16(cd + 30, true), clen = dv.getUint16(cd + 32, true), lho = dv.getUint32(cd + 42, true);
      const name = new TextDecoder().decode(b.subarray(cd + 46, cd + 46 + nlen)); cd += 46 + nlen + xlen + clen;
      const lnl = dv.getUint16(lho + 26, true), lxl = dv.getUint16(lho + 28, true), start = lho + 30 + lnl + lxl, raw = b.subarray(start, start + csize);
      files.push({ name, method, raw });
    }
    const out = [];
    for (const f of files) {
      const kind = all ? "file" : /\.obf$/i.test(f.name) ? "obf" : /\.gpx$/i.test(f.name) ? "gpx"
        : /(^|\/)tiles\/[^/]+\/\d+\/\d+\/\d+\.(jpe?g|png|webp)(\.tile)?$/i.test(f.name) ? "tile" : /(^|\/)tiles\/[^/]+\/\.metainfo$/.test(f.name) ? "meta" : null;
      if (!kind || /(^|\/)(backup|\.trash|trash)\//i.test(f.name)) continue;
      let data;
      if (f.method === 0) data = f.raw;
      else if (f.method === 8) data = new Uint8Array(await new Response(new Blob([f.raw]).stream().pipeThrough(new DecompressionStream("deflate-raw"))).arrayBuffer());
      else throw new Error("unsupported zip method " + f.method);
      out.push({ name: kind === "tile" || kind === "meta" ? f.name : f.name.replace(/^.*\//, ""), path: f.name, kind, data });
    }
    return out;
  }
  return { extract, unzip, lonTo31, latTo31 };
})();
if (typeof module !== "undefined") module.exports = OBF;
