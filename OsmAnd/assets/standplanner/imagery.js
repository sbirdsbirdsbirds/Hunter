// Stand Planner imagery: reads Sentinel-2 downloads (GeoTIFF or KMZ) from the Copernicus Browser,
// reprojects and crops them to the plan area. Everything runs on this phone.
const IMG = (() => {
  /* ---------------- TIFF ---------------- */
  function parseTIFF(buf) {
    const dv = new DataView(buf), b = new Uint8Array(buf);
    const le = dv.getUint16(0) === 0x4949; const u16 = o => dv.getUint16(o, le), u32 = o => dv.getUint32(o, le);
    const magic = u16(2);
    if (magic === 43) throw new Error("This is a BigTIFF. Download a smaller area or 8-bit TIFF.");
    if (magic !== 42) throw new Error("Not a TIFF file");
    const ifd = u32(4), n = u16(ifd), tags = {};
    const SZ = { 1: 1, 2: 1, 3: 2, 4: 4, 5: 8, 6: 1, 7: 1, 8: 2, 9: 4, 10: 8, 11: 4, 12: 8, 16: 8 };
    for (let i = 0; i < n; i++) {
      const e = ifd + 2 + i * 12, tag = u16(e), type = u16(e + 2), cnt = u32(e + 4), size = (SZ[type] || 1) * cnt;
      const off = size <= 4 ? e + 8 : u32(e + 8), vals = [];
      for (let k = 0; k < cnt; k++) {
        const o = off + k * (SZ[type] || 1);
        if (type === 3) vals.push(u16(o)); else if (type === 4) vals.push(u32(o));
        else if (type === 12) vals.push(dv.getFloat64(o, le)); else if (type === 11) vals.push(dv.getFloat32(o, le));
        else if (type === 5) vals.push(u32(o) / u32(o + 4)); else if (type === 16) vals.push(Number(dv.getBigUint64(o, le)));
        else if (type === 2) { vals.push(new TextDecoder().decode(b.subarray(off, off + cnt))); break; }
        else vals.push(b[o]);
      }
      tags[tag] = vals;
    }
    return { tags, le, dv, b };
  }

  function lzw(src) {
    const out = []; let dict = [], bits = 9, pos = 0, prev = null;
    const reset = () => { dict = []; for (let i = 0; i < 256; i++) dict[i] = [i]; dict[256] = null; dict[257] = null; bits = 9; };
    const read = () => { let v = 0; for (let i = 0; i < bits; i++) { const byte = src[(pos + i) >> 3]; if (byte === undefined) return 257; v = (v << 1) | ((byte >> (7 - ((pos + i) & 7))) & 1); } pos += bits; return v; };
    reset();
    for (;;) {
      const code = read();
      if (code === 257) break;
      if (code === 256) { reset(); prev = null; continue; }
      let entry;
      if (code < dict.length && dict[code]) entry = dict[code];
      else if (prev) entry = prev.concat(prev[0]);
      else break;
      for (const v of entry) out.push(v);
      if (prev) dict.push(prev.concat(entry[0]));
      prev = entry;
      if (dict.length + 1 >= (1 << bits) && bits < 12) bits++;
    }
    return new Uint8Array(out);
  }
  function packbits(src) {
    const out = []; let i = 0;
    while (i < src.length) { const n = (src[i] << 24) >> 24; i++;
      if (n >= 0) { for (let k = 0; k <= n; k++) out.push(src[i++]); } else if (n !== -128) { const v = src[i++]; for (let k = 0; k < 1 - n; k++) out.push(v); } }
    return new Uint8Array(out);
  }
  async function inflate(src) {
    const fmt = (src[0] & 0x0f) === 8 && ((src[0] << 8) | src[1]) % 31 === 0 ? "deflate" : "deflate-raw";
    return new Uint8Array(await new Response(new Blob([src]).stream().pipeThrough(new DecompressionStream(fmt))).arrayBuffer());
  }

  async function decodeTIFF(buf) {
    const { tags, le } = parseTIFF(buf), b = new Uint8Array(buf);
    const W = tags[256][0], H = tags[257][0], spp = (tags[277] || [1])[0], bps = (tags[258] || [8])[0];
    const comp = (tags[259] || [1])[0], planar = (tags[284] || [1])[0], pred = (tags[317] || [1])[0], fmt = (tags[339] || [1])[0];
    if (![1, 5, 8, 32946, 32773].includes(comp)) throw new Error(comp === 7 ? "This TIFF uses JPEG compression. In the Copernicus Browser, choose TIFF without compression." : "Unsupported TIFF compression (" + comp + ")");
    if (![8, 16, 32].includes(bps)) throw new Error("Unsupported bit depth " + bps);
    const tiled = !!tags[322];
    const bw = tiled ? tags[322][0] : W, bh = tiled ? tags[323][0] : (tags[278] ? tags[278][0] : H);
    const offs = tiled ? tags[324] : tags[273], cnts = tiled ? tags[325] : tags[279];
    const across = Math.ceil(W / bw), down = Math.ceil(H / bh), Bps = bps / 8;
    const planes = planar === 2 ? spp : 1, chunkSpp = planar === 2 ? 1 : spp;
    const out = new Float32Array(W * H * spp);
    for (let k = 0; k < offs.length; k++) {
      const plane = Math.floor(k / (across * down)), idx = k % (across * down);
      const tx = tiled ? idx % across : 0, ty = tiled ? Math.floor(idx / across) : idx;
      let raw = b.subarray(offs[k], offs[k] + cnts[k]);
      if (comp === 5) raw = lzw(raw); else if (comp === 8 || comp === 32946) raw = await inflate(raw); else if (comp === 32773) raw = packbits(raw);
      const rowLen = bw * chunkSpp, rows = tiled ? bh : Math.min(bh, H - ty * bh);
      const dv = new DataView(raw.buffer, raw.byteOffset, raw.byteLength);
      const get = (i) => bps === 8 ? raw[i] : bps === 16 ? (fmt === 2 ? dv.getInt16(i * 2, le) : dv.getUint16(i * 2, le)) : (fmt === 3 ? dv.getFloat32(i * 4, le) : dv.getUint32(i * 4, le));
      for (let r = 0; r < rows; r++) {
        const y = ty * bh + r; if (y >= H) break;
        let prevv = new Array(chunkSpp).fill(0);
        for (let c = 0; c < bw; c++) {
          const x = tx * bw + c;
          for (let s = 0; s < chunkSpp; s++) {
            const i = r * rowLen + c * chunkSpp + s; if ((i + 1) * Bps > raw.length) continue;
            let v = get(i);
            if (pred === 2 && bps <= 16) { v = (v + prevv[s]) & (bps === 8 ? 0xff : 0xffff); prevv[s] = v; }
            if (x < W) out[(y * W + x) * spp + (planar === 2 ? plane : s)] = v;
          }
        }
      }
    }
    // georeferencing
    let tr;
    if (tags[34264]) { const m = tags[34264]; tr = { x0: m[3], sx: m[0], y0: m[7], sy: m[5] }; }
    else if (tags[33922] && tags[33550]) { const t = tags[33922], s = tags[33550]; tr = { x0: t[3] - t[0] * s[0], sx: s[0], y0: t[4] + t[1] * s[1], sy: -s[1] }; }
    else throw new Error("This TIFF has no map position. Download it as a GeoTIFF from the Copernicus Browser.");
    let epsg = null, geographic = false, rasterPoint = false;
    if (tags[34735]) { const g = tags[34735];
      for (let i = 4; i + 3 < g.length; i += 4) { const key = g[i], loc = g[i + 1], val = g[i + 3];
        if (loc !== 0) continue;
        if (key === 3072) epsg = val; else if (key === 2048 && !epsg) { epsg = val; geographic = true; } else if (key === 1024) geographic = val === 2 || geographic; else if (key === 1025) rasterPoint = val === 2; } }
    if (rasterPoint) { tr.x0 -= tr.sx / 2; tr.y0 -= tr.sy / 2; }
    return { W, H, spp, bps, fmt, data: out, tr, crs: crsFromEPSG(epsg, geographic) };
  }

  /* ---------------- projections ---------------- */
  function crsFromEPSG(e, geographic) {
    if (!e || e === 4326 || e === 4269 || e === 4617 || (geographic && e < 5000 && e >= 4000)) return { type: "ll" };
    if (e === 3857 || e === 900913 || e === 102100) return { type: "merc" };
    let z = null, south = false;
    if (e >= 32601 && e <= 32660) z = e - 32600; else if (e >= 32701 && e <= 32760) { z = e - 32700; south = true; }
    else if (e >= 26901 && e <= 26923) z = e - 26900; else if (e >= 2955 && e <= 2962) z = { 2955: 11, 2956: 12, 2957: 13, 2958: 17, 2959: 18, 2960: 19, 2961: 20, 2962: 21 }[e];
    else if (e >= 3154 && e <= 3160) z = { 3154: 7, 3155: 8, 3156: 9, 3157: 10, 3158: 14, 3159: 15, 3160: 16 }[e];
    if (z) return { type: "tm", lon0: -183 + 6 * z, k0: 0.9996, fe: 500000, fn: south ? 10000000 : 0, label: "UTM " + z + (south ? "S" : "N") };
    throw new Error("Unsupported map projection (EPSG " + e + "). In the Copernicus Browser, choose WGS84 or UTM.");
  }
  const A = 6378137, F = 1 / 298.257223563, E2 = F * (2 - F), EP2 = E2 / (1 - E2);
  function tmForward(lat, lon, c) {
    const phi = lat * Math.PI / 180, lam = (lon - c.lon0) * Math.PI / 180;
    const N = A / Math.sqrt(1 - E2 * Math.sin(phi) ** 2), T = Math.tan(phi) ** 2, C = EP2 * Math.cos(phi) ** 2, Aa = Math.cos(phi) * lam;
    const e4 = E2 * E2, e6 = e4 * E2;
    const M = A * ((1 - E2 / 4 - 3 * e4 / 64 - 5 * e6 / 256) * phi - (3 * E2 / 8 + 3 * e4 / 32 + 45 * e6 / 1024) * Math.sin(2 * phi) + (15 * e4 / 256 + 45 * e6 / 1024) * Math.sin(4 * phi) - (35 * e6 / 3072) * Math.sin(6 * phi));
    const x = c.k0 * N * (Aa + (1 - T + C) * Aa ** 3 / 6 + (5 - 18 * T + T * T + 72 * C - 58 * EP2) * Aa ** 5 / 120);
    const y = c.k0 * (M + N * Math.tan(phi) * (Aa * Aa / 2 + (5 - T + 9 * C + 4 * C * C) * Aa ** 4 / 24 + (61 - 58 * T + T * T + 600 * C - 330 * EP2) * Aa ** 6 / 720));
    return [x + c.fe, y + c.fn];
  }
  function toCRS(lat, lon, c) {
    if (c.type === "ll") return [lon, lat];
    if (c.type === "merc") return [A * lon * Math.PI / 180, A * Math.log(Math.tan(Math.PI / 4 + lat * Math.PI / 360))];
    return tmForward(lat, lon, c);
  }

  /* ---------------- render to plan box ---------------- */
  // Stretch: 8-bit is used as is; 16-bit/float reflectance gets the usual Sentinel-2 true-colour gain.
  function toRGB(img, i) {
    const s = img.spp, d = img.data, o = i * s;
    let r = d[o], g = s >= 3 ? d[o + 1] : d[o], bl = s >= 3 ? d[o + 2] : d[o];
    const alpha = s === 4 ? d[o + 3] : s === 2 ? d[o + 1] : 255;
    if (img.bps === 8) return [r, g, bl, img.bps === 8 && (s === 4 || s === 2) ? alpha : 255];
    const scale = img.fmt === 3 ? (Math.max(r, g, bl) <= 1.5 ? 255 * 2.5 : 2.5 * 255 / 10000) : 2.5 * 255 / 10000;
    if (r === 0 && g === 0 && bl === 0) return [0, 0, 0, 0];
    return [r * scale, g * scale, bl * scale, 255];
  }
  function render(img, box, maxPx) {
    const midLat = (box.south + box.north) / 2, mPerDegLat = 110540, mPerDegLon = 111320 * Math.cos(midLat * Math.PI / 180);
    const srcRes = img.crs.type === "ll" ? Math.abs(img.tr.sx) * mPerDegLon : Math.abs(img.tr.sx);
    const wM = (box.east - box.west) * mPerDegLon, hM = (box.north - box.south) * mPerDegLat;
    let res = Math.max(srcRes / 2, Math.max(wM, hM) / maxPx);
    const W = Math.max(1, Math.round(wM / res)), H = Math.max(1, Math.round(hM / res));
    const cv = document.createElement("canvas"); cv.width = W; cv.height = H;
    const ctx = cv.getContext("2d"), id = ctx.createImageData(W, H), px = id.data;
    let hits = 0;
    for (let j = 0; j < H; j++) {
      const lat = box.north - (j + 0.5) / H * (box.north - box.south);
      for (let i = 0; i < W; i++) {
        const lon = box.west + (i + 0.5) / W * (box.east - box.west);
        const [X, Y] = toCRS(lat, lon, img.crs);
        const c = Math.floor((X - img.tr.x0) / img.tr.sx), r = Math.floor((Y - img.tr.y0) / img.tr.sy);
        if (c < 0 || r < 0 || c >= img.W || r >= img.H) continue;
        const [R, G, B, Al] = toRGB(img, r * img.W + c), o = (j * W + i) * 4;
        px[o] = R; px[o + 1] = G; px[o + 2] = B; px[o + 3] = Al; if (Al) hits++;
      }
    }
    ctx.putImageData(id, 0, 0);
    return { canvas: cv, coverage: hits / (W * H), resM: res, srcRes };
  }

  /* ---------------- KMZ (GroundOverlay) ---------------- */
  async function decodeKMZ(entries) {
    const kml = entries.find(e => /\.kml$/i.test(e.name)); if (!kml) throw new Error("No KML inside this KMZ");
    const doc = new DOMParser().parseFromString(new TextDecoder().decode(kml.data), "application/xml");
    const ov = doc.getElementsByTagName("GroundOverlay")[0]; if (!ov) throw new Error("This KMZ has no image overlay");
    const g = t => parseFloat((ov.getElementsByTagName(t)[0] || {}).textContent);
    const href = ((ov.getElementsByTagName("href")[0] || {}).textContent || "").trim();
    const imgE = entries.find(e => e.name === href || e.name.endsWith("/" + href)) || entries.find(e => /\.(png|jpe?g)$/i.test(e.name));
    if (!imgE) throw new Error("No image inside this KMZ");
    const blob = new Blob([imgE.data], { type: /\.png$/i.test(imgE.name) ? "image/png" : "image/jpeg" });
    const bmp = await createImageBitmap(blob);
    const cv = document.createElement("canvas"); cv.width = bmp.width; cv.height = bmp.height; cv.getContext("2d").drawImage(bmp, 0, 0);
    const d = cv.getContext("2d").getImageData(0, 0, bmp.width, bmp.height).data, data = new Float32Array(bmp.width * bmp.height * 4);
    for (let i = 0; i < d.length; i++) data[i] = d[i];
    const n = g("north"), s = g("south"), e = g("east"), w = g("west");
    if (![n, s, e, w].every(isFinite)) throw new Error("This KMZ has no map position");
    return { W: bmp.width, H: bmp.height, spp: 4, bps: 8, fmt: 1, data, tr: { x0: w, sx: (e - w) / bmp.width, y0: n, sy: -(n - s) / bmp.height }, crs: { type: "ll" } };
  }

  /* ---------------- OsmAnd tile exports (tiles/<source>/<z>/<x>/<y>.jpeg.tile) ---------------- */
  // Standard web map tiles. Picks the sharpest zoom that covers the plan area, fills gaps from lower zooms,
  // and places every tile by its exact edges, so the result lines up with the app's map.
  const tileLon = (x, z) => x / 2 ** z * 360 - 180;
  const tileLat = (y, z) => { const n = Math.PI * (1 - 2 * y / 2 ** z); return 180 / Math.PI * Math.atan(Math.sinh(n)); };
  async function stitchTiles(tiles, box, maxPx, invertedY) {
    const byZ = new Map();
    for (const t of tiles) {
      const m = /\/(\d+)\/(\d+)\/(\d+)\.(jpe?g|png|webp)(\.tile)?$/i.exec(t.name); if (!m) continue;
      const z = +m[1], x = +m[2]; let y = +m[3]; if (invertedY) y = 2 ** z - 1 - y;
      const n = tileLat(y, z), s = tileLat(y + 1, z), w = tileLon(x, z), e = tileLon(x + 1, z);
      if (e < box.west || w > box.east || n < box.south || s > box.north) continue;
      const type = /png/i.test(m[4]) ? "image/png" : /webp/i.test(m[4]) ? "image/webp" : "image/jpeg";
      (byZ.get(z) || byZ.set(z, []).get(z)).push({ z, x, y, n, s, w, e, data: t.data, type });
    }
    if (!byZ.size) return null;
    const midLat = (box.south + box.north) / 2, mLon = 111320 * Math.cos(midLat * Math.PI / 180), mLat = 110540;
    const area = (box.east - box.west) * mLon * (box.north - box.south) * mLat;
    // coverage of the plan box at each zoom
    const zs = [...byZ.keys()].sort((a, b) => b - a), cover = {};
    for (const z of zs) { let a = 0; for (const t of byZ.get(z)) { const w = Math.max(t.w, box.west), e = Math.min(t.e, box.east), n = Math.min(t.n, box.north), s = Math.max(t.s, box.south); if (e > w && n > s) a += (e - w) * mLon * (n - s) * mLat; } cover[z] = a / area; }
    const best = zs.find(z => cover[z] > 0.005) ?? zs[0];   // sharpest zoom with any real coverage
    const groundRes = 156543.034 * Math.cos(midLat * Math.PI / 180) / 2 ** best;
    const wM = (box.east - box.west) * mLon, hM = (box.north - box.south) * mLat;
    const res = Math.max(groundRes, Math.max(wM, hM) / maxPx);
    const W = Math.round(wM / res), H = Math.round(hM / res);
    const cv = document.createElement("canvas"); cv.width = W; cv.height = H; const ctx = cv.getContext("2d");
    ctx.imageSmoothingQuality = "high";
    const X = lon => (lon - box.west) / (box.east - box.west) * W, Y = lat => (box.north - lat) / (box.north - box.south) * H;
    // draw coarse first so sharper tiles paint over them
    const order = zs.slice().sort((a, b) => a - b);
    for (const z of order) for (const t of byZ.get(z)) {
      try { const bmp = await createImageBitmap(new Blob([t.data], { type: t.type })); ctx.drawImage(bmp, X(t.w), Y(t.n), X(t.e) - X(t.w), Y(t.s) - Y(t.n)); bmp.close && bmp.close(); } catch (e) {}
    }
    const id = ctx.getImageData(0, 0, W, H).data; let hits = 0; for (let i = 3; i < id.length; i += 4) if (id[i]) hits++;
    return { canvas: cv, coverage: hits / (W * H), srcRes: groundRes, zoom: best, tiles: byZ.get(best).length, sharpCover: cover[best] };
  }

  function dateFromName(name) { const m = /(20\d\d-\d\d-\d\d)/.exec(name || ""); return m ? m[1] : ""; }

  /* ---------------- storage (IndexedDB, this phone only) ---------------- */
  function db() { return new Promise((res, rej) => { const r = indexedDB.open("standplanner", 1); r.onupgradeneeded = () => r.result.createObjectStore("files"); r.onsuccess = () => res(r.result); r.onerror = () => rej(r.error); }); }
  async function put(key, val) { const d = await db(); return new Promise((res, rej) => { const t = d.transaction("files", "readwrite"); t.objectStore("files").put(val, key); t.oncomplete = () => res(); t.onerror = () => rej(t.error); }); }
  async function get(key) { const d = await db(); return new Promise((res, rej) => { const t = d.transaction("files", "readonly"), q = t.objectStore("files").get(key); q.onsuccess = () => res(q.result); q.onerror = () => rej(q.error); }); }
  async function del(key) { const d = await db(); return new Promise((res, rej) => { const t = d.transaction("files", "readwrite"); t.objectStore("files").delete(key); t.oncomplete = () => res(); t.onerror = () => rej(t.error); }); }

  return { decodeTIFF, decodeKMZ, render, stitchTiles, dateFromName, put, get, del, toCRS, crsFromEPSG };
})();
if (typeof module !== "undefined") module.exports = IMG;
