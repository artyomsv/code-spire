// Builds the architecture sheets: node docs/architecture/build.mjs
// Each file in sheets/ describes one drawing as data. This script turns it into
// a page (NN-slug.html). Pass --svg to also write a standalone NN-slug.svg.
import { readdirSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const CHECKED = 'Checked against the code at 0e6fbde3, 2026-11-15';
const WANT_SVG = process.argv.includes('--svg');

const C = {
  paper: '#16426b', panel: '#1d5080', ink: '#e6f0fb', dim: '#a3bfdb',
  amber: '#ffc857', coral: '#ff9a8a', mint: '#8fe3c4', grid: '#ffffff',
};
const SANS = `Bahnschrift, 'DIN Alternate', 'DIN Next', 'Roboto Condensed', 'Arial Narrow', 'Helvetica Neue', Arial, sans-serif`;

const esc = (s) => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
const rich = (s) => esc(s).replace(/`([^`]+)`/g, '<code>$1</code>');
const r1 = (n) => Math.round(n * 10) / 10;

const SVG_CSS = `
text{font-family:${SANS};fill:${C.ink}}
.nt{font-size:14.5px;font-weight:600;text-anchor:middle}
.nl{font-size:11.5px;fill:${C.dim};text-anchor:middle}
.lib .nt{font-size:13px;font-weight:500}
.topic .nt{font-size:13px;fill:${C.amber}}
.topic .nl{fill:${C.amber};opacity:.8}
.store .nt{fill:${C.mint}}
.note text{text-anchor:start;font-size:12px;fill:${C.dim}}
.note .nt{font-size:12.5px;fill:${C.ink};font-weight:600;text-anchor:start}
.box{fill:${C.panel};stroke:${C.ink};stroke-width:1.3}
.focus .box{stroke-width:2.4}
.service .box{stroke-width:2}
.lib .box{fill:none;stroke:${C.dim};stroke-width:1}
.ext .box{fill:none;stroke-dasharray:6 4}
.topic .box{fill:#3b4a4d;stroke:${C.amber};stroke-width:1.4}
.store .box{fill:#1b5672;stroke:${C.mint};stroke-width:1.4}
.grp{fill:none;stroke:${C.dim};stroke-width:1.1;stroke-dasharray:3 4}
.grp.subject{stroke:${C.ink};stroke-width:2.2;stroke-dasharray:none}
.grpl{font-size:12px;fill:${C.dim};text-anchor:start}
.grpl.subject{font-size:16px;font-weight:600;fill:${C.ink}}
.e{fill:none;stroke-linejoin:round}
.e.sync{stroke:${C.ink};stroke-width:1.5}
.e.async{stroke:${C.amber};stroke-width:1.8}
.e.dep{stroke:${C.dim};stroke-width:1.1;stroke-dasharray:4 4}
.e.file{stroke:${C.mint};stroke-width:1.5;stroke-dasharray:1.5 4;stroke-linecap:round}
.el{font-size:11.5px;text-anchor:middle;paint-order:stroke;stroke:${C.paper};stroke-width:6px;stroke-linejoin:round}
.el.sync{fill:${C.ink}} .el.async{fill:${C.amber}} .el.dep{fill:${C.dim}} .el.file{fill:${C.mint}}
.lg{font-size:11.5px;fill:${C.dim};text-anchor:start}
.tb{fill:${C.paper};stroke:${C.ink};stroke-width:1.2}
.tb1{font-size:11.5px;fill:${C.dim};text-anchor:start}
.tb2{font-size:19px;font-weight:600;text-anchor:start}
.lane{font-size:13.5px;font-weight:600;text-anchor:middle}
.life{stroke:${C.dim};stroke-width:1;stroke-dasharray:2 5}
.band{font-size:13px;font-weight:600;fill:${C.ink};text-anchor:start}
.bandl{stroke:${C.dim};stroke-width:.8}
.num{font-size:10.5px;font-weight:600;text-anchor:middle;fill:${C.paper}}
.sl{font-size:12px;text-anchor:middle;paint-order:stroke;stroke:${C.paper};stroke-width:6px;stroke-linejoin:round}
.sl.self{text-anchor:start}
.mh{font-size:12.5px;font-weight:600;text-anchor:start}
.ml{font-size:13px;text-anchor:start}
.mi{font-size:11.5px;fill:${C.dim};text-anchor:start}
.mr{stroke:${C.ink};stroke-opacity:.16;stroke-width:1}
`;

function defs() {
  const mk = (id, col) => `<marker id="a-${id}" viewBox="0 0 10 10" refX="8.5" refY="5" markerWidth="7.5" markerHeight="7.5" orient="auto-start-reverse"><path d="M0 1 L9 5 L0 9 z" fill="${col}"/></marker>`;
  return `<defs>
<pattern id="g1" width="20" height="20" patternUnits="userSpaceOnUse"><path d="M20 0H0V20" fill="none" stroke="${C.grid}" stroke-opacity=".045" stroke-width="1"/></pattern>
<pattern id="g2" width="100" height="100" patternUnits="userSpaceOnUse"><path d="M100 0H0V100" fill="none" stroke="${C.grid}" stroke-opacity=".08" stroke-width="1"/></pattern>
${mk('sync', C.ink)}${mk('async', C.amber)}${mk('dep', C.dim)}${mk('file', C.mint)}
</defs>`;
}

function keyBadge(x, y) {
  return `<g transform="translate(${x} ${y})"><circle r="10" fill="${C.paper}" stroke="${C.coral}" stroke-width="1.4"/><circle cx="-3" cy="0" r="2.6" fill="none" stroke="${C.coral}" stroke-width="1.4"/><path d="M-.4 0H6M3.6 0V2.6M5.8 0V2" fill="none" stroke="${C.coral}" stroke-width="1.4" stroke-linecap="round"/></g>`;
}

function shape(n) {
  const { x, y, w, h } = n;
  if (n.kind === 'store') {
    const ry = 7;
    return `<path class="box" d="M${x} ${y + ry}a${w / 2} ${ry} 0 0 1 ${w} 0v${h - 2 * ry}a${w / 2} ${ry} 0 0 1 ${-w} 0z"/><path d="M${x} ${y + ry}a${w / 2} ${ry} 0 0 0 ${w} 0" fill="none" stroke="${C.mint}" stroke-width="1.4"/>`;
  }
  const rx = n.kind === 'topic' ? Math.min(h / 2, 18) : 4;
  return `<rect class="box" x="${x}" y="${y}" width="${w}" height="${h}" rx="${rx}"/>`;
}

function node(n) {
  const lines = n.lines || [];
  if (n.kind === 'note') {
    let y = n.y + 14;
    const out = [];
    if (n.title) { out.push(`<text class="nt" x="${n.x}" y="${y}">${esc(n.title)}</text>`); y += 17; }
    for (const l of lines) { out.push(`<text x="${n.x}" y="${y}">${esc(l)}</text>`); y += 15.5; }
    return `<g class="note">${out.join('')}</g>`;
  }
  const lh = 14.5;
  const blockH = 16 + lines.length * lh;
  let ty = n.y + (n.h - blockH) / 2 + 14 + (n.kind === 'store' ? 5 : 0);
  const cx = n.x + n.w / 2;
  const out = [shape(n), `<text class="nt" x="${cx}" y="${r1(ty)}">${esc(n.title)}</text>`];
  ty += 4;
  for (const l of lines) { ty += lh; out.push(`<text class="nl" x="${cx}" y="${r1(ty)}">${esc(l)}</text>`); }
  if (n.key) out.push(keyBadge(n.x + n.w - 4, n.y + 4));
  return `<g class="${n.kind}">${out.join('')}</g>`;
}

function anchor(n, side, off = 0) {
  if (side === 'l') return [n.x, n.y + n.h / 2 + off];
  if (side === 'r') return [n.x + n.w, n.y + n.h / 2 + off];
  if (side === 't') return [n.x + n.w / 2 + off, n.y];
  return [n.x + n.w / 2 + off, n.y + n.h];
}

function inferSides(a, b) {
  if (b.x >= a.x + a.w) return ['r', 'l'];
  if (b.x + b.w <= a.x) return ['l', 'r'];
  if (b.y >= a.y + a.h) return ['b', 't'];
  return ['t', 'b'];
}

function route(a, b, e) {
  const [ia, ib] = inferSides(a, b);
  const as = e.as || ia, bs = e.bs || ib;
  const [ax, ay] = anchor(a, as, e.ao);
  const [bx, by] = anchor(b, bs, e.bo);
  const H = (s) => s === 'l' || s === 'r';
  let pts;
  if (H(as) && H(bs)) {
    if (as !== bs) {
      const mx = e.mid ?? (ax + bx) / 2;
      pts = ay === by ? [[ax, ay], [bx, by]] : [[ax, ay], [mx, ay], [mx, by], [bx, by]];
    } else {
      const mx = e.mid ?? (as === 'r' ? Math.max(ax, bx) + 28 : Math.min(ax, bx) - 28);
      pts = [[ax, ay], [mx, ay], [mx, by], [bx, by]];
    }
  } else if (!H(as) && !H(bs)) {
    if (as !== bs) {
      const my = e.mid ?? (ay + by) / 2;
      pts = ax === bx ? [[ax, ay], [bx, by]] : [[ax, ay], [ax, my], [bx, my], [bx, by]];
    } else {
      const my = e.mid ?? (as === 'b' ? Math.max(ay, by) + 28 : Math.min(ay, by) - 28);
      pts = [[ax, ay], [ax, my], [bx, my], [bx, by]];
    }
  } else if (H(as)) pts = [[ax, ay], [bx, ay], [bx, by]];
  else pts = [[ax, ay], [ax, by], [bx, by]];
  return pts;
}

function pathOf(pts) {
  const R = 7;
  let d = `M${r1(pts[0][0])} ${r1(pts[0][1])}`;
  for (let i = 1; i < pts.length; i++) {
    const [x, y] = pts[i];
    if (i === pts.length - 1) { d += `L${r1(x)} ${r1(y)}`; break; }
    const [px, py] = pts[i - 1], [nx, ny] = pts[i + 1];
    const l1 = Math.hypot(x - px, y - py), l2 = Math.hypot(nx - x, ny - y);
    const r = Math.min(R, l1 / 2, l2 / 2);
    const sx = x - ((x - px) / l1) * r, sy = y - ((y - py) / l1) * r;
    const ex = x + ((nx - x) / l2) * r, ey = y + ((ny - y) / l2) * r;
    d += `L${r1(sx)} ${r1(sy)}Q${r1(x)} ${r1(y)} ${r1(ex)} ${r1(ey)}`;
  }
  return d;
}

function edge(e, byId) {
  const a = byId[e.a], b = byId[e.b];
  if (!a || !b) throw new Error(`edge ${e.a} -> ${e.b}: unknown node`);
  const pts = route(a, b, e);
  const kind = e.kind || 'sync';
  const line = `<path class="e ${kind}" d="${pathOf(pts)}" marker-end="url(#a-${kind})"${e.both ? ` marker-start="url(#a-${kind})"` : ''}/>`;
  const out = [];
  if (e.label) {
    let best = 0, len = -1;
    for (let i = 0; i < pts.length - 1; i++) {
      const l = Math.hypot(pts[i + 1][0] - pts[i][0], pts[i + 1][1] - pts[i][1]);
      if (l > len) { len = l; best = i; }
    }
    const i = e.seg ?? best;
    const t = e.at ?? 0.5;
    const lx = pts[i][0] + (pts[i + 1][0] - pts[i][0]) * t + (e.dx || 0);
    const rows = e.label.split('\n');
    const gap = rows.length === 2 ? 19 : 13.5;
    let ly = pts[i][1] + (pts[i + 1][1] - pts[i][1]) * t + 4 + (e.dy || 0) - ((rows.length - 1) * gap) / 2;
    for (const row of rows) { out.push(`<text class="el ${kind}" x="${r1(lx)}" y="${r1(ly)}">${esc(row)}</text>`); ly += gap; }
  }
  return { line, labels: out.join('') };
}

const LEGEND = {
  subject: ['This module', (x, y) => `<rect x="${x}" y="${y - 9}" width="22" height="13" rx="2" fill="none" stroke="${C.ink}" stroke-width="2.2"/>`],
  part: ['Part inside it', (x, y) => `<rect x="${x}" y="${y - 9}" width="22" height="13" rx="2" fill="${C.panel}" stroke="${C.ink}" stroke-width="1.2"/>`],
  service: ['Service', (x, y) => `<rect x="${x}" y="${y - 9}" width="22" height="13" rx="2" fill="${C.panel}" stroke="${C.ink}" stroke-width="2"/>`],
  lib: ['Library', (x, y) => `<rect x="${x}" y="${y - 9}" width="22" height="13" rx="2" fill="none" stroke="${C.dim}"/>`],
  ext: ['Outside system', (x, y) => `<rect x="${x}" y="${y - 9}" width="22" height="13" rx="2" fill="none" stroke="${C.ink}" stroke-dasharray="4 3"/>`],
  store: ['Data at rest', (x, y) => `<rect x="${x}" y="${y - 9}" width="22" height="13" rx="5" fill="#1b5672" stroke="${C.mint}" stroke-width="1.3"/>`],
  topic: ['Kafka topic', (x, y) => `<rect x="${x}" y="${y - 9}" width="22" height="13" rx="6.5" fill="#3b4a4d" stroke="${C.amber}" stroke-width="1.3"/>`],
  async: ['Message (async)', (x, y) => `<path d="M${x} ${y - 3}h22" class="e async" marker-end="url(#a-async)"/>`],
  sync: ['Call (sync)', (x, y) => `<path d="M${x} ${y - 3}h22" class="e sync" marker-end="url(#a-sync)"/>`],
  file: ['Files', (x, y) => `<path d="M${x} ${y - 3}h22" class="e file" marker-end="url(#a-file)"/>`],
  dep: ['Uses (code)', (x, y) => `<path d="M${x} ${y - 3}h22" class="e dep" marker-end="url(#a-dep)"/>`],
  mp: ['Produces', (x, y) => `<circle cx="${x + 11}" cy="${y - 3}" r="6" fill="${C.amber}"/>`],
  mc: ['Consumes', (x, y) => `<circle cx="${x + 11}" cy="${y - 3}" r="5.5" fill="none" stroke="${C.amber}" stroke-width="2"/>`],
  md: ['Depends on', (x, y) => `<circle cx="${x + 11}" cy="${y - 3}" r="5" fill="${C.ink}"/>`],
  key: ['Holds a credential', (x, y) => keyBadge(x + 11, y - 3)],
};

function frame(s, W, Hh, n, total, used, body) {
  const tbW = 380, tbH = 78, tbX = W - 30 - tbW, tbY = Hh - 30 - tbH;
  const items = Object.keys(LEGEND).filter((k) => used.has(k)).map((k) => LEGEND[k]);
  const rowsOf = [[]];
  let run = 44;
  for (const it of items) {
    const wdt = 30 + it[0].length * 6.1 + 22;
    if (run + wdt > tbX - 16 && rowsOf.at(-1).length) { rowsOf.push([]); run = 44; }
    rowsOf.at(-1).push([it, run]);
    run += wdt;
  }
  const leg = [];
  rowsOf.forEach((row, ri) => {
    const ly = Hh - 44 - (rowsOf.length - 1 - ri) * 24;
    for (const [[label, draw], lx] of row) leg.push(draw(lx, ly) + `<text class="lg" x="${lx + 30}" y="${ly + 1}">${label}</text>`);
  });
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${W} ${Hh}" width="${W}" height="${Hh}" role="img" aria-label="${esc(s.title)}: ${esc(s.kicker)}">
<style>${SVG_CSS}</style>${defs()}
<rect width="${W}" height="${Hh}" fill="${C.paper}"/><rect width="${W}" height="${Hh}" fill="url(#g1)"/><rect width="${W}" height="${Hh}" fill="url(#g2)"/>
<rect x="12" y="12" width="${W - 24}" height="${Hh - 24}" fill="none" stroke="${C.ink}" stroke-width="1.6"/><rect x="18" y="18" width="${W - 36}" height="${Hh - 36}" fill="none" stroke="${C.ink}" stroke-width=".5"/>
${body}
${leg.join('')}
<g><rect class="tb" x="${tbX}" y="${tbY}" width="${tbW}" height="${tbH}"/><path d="M${tbX} ${tbY + 24}h${tbW}M${tbX} ${tbY + 54}h${tbW}M${tbX + tbW - 110} ${tbY}v24" stroke="${C.ink}" stroke-width=".8"/>
<text class="tb1" x="${tbX + 10}" y="${tbY + 16}">Spired architecture</text><text class="tb1" x="${tbX + tbW - 100}" y="${tbY + 16}">Sheet ${String(n).padStart(2, '0')} of ${String(total).padStart(2, '0')}</text>
<text class="tb2" x="${tbX + 10}" y="${tbY + 46}">${esc(s.short || s.title)}</text><text class="tb1" x="${tbX + 10}" y="${tbY + 70}">${CHECKED}</text></g>
</svg>`;
}

function drawBoard(s, n, total) {
  const [W, Hh] = s.size;
  const byId = Object.fromEntries([...(s.groups || []), ...s.nodes].map((x) => [x.id, x]));
  const used = new Set();
  const groups = (s.groups || []).map((g) => {
    if (g.subject) used.add('subject');
    const cls = g.subject ? ' subject' : '';
    const pad = g.label.length * (g.subject ? 8.6 : 6.3) + 14;
    return `<rect class="grp${cls}" x="${g.x}" y="${g.y}" width="${g.w}" height="${g.h}" rx="6"/><rect x="${g.x + 14}" y="${g.y - 10}" width="${r1(pad)}" height="20" fill="${C.paper}"/><text class="grpl${cls}" x="${g.x + 21}" y="${g.y + (g.subject ? 5.5 : 4)}">${esc(g.label)}</text>`;
  });
  for (const x of s.nodes) { if (x.kind !== 'note') used.add(x.kind); if (x.key) used.add('key'); }
  for (const e of s.edges) used.add(e.kind || 'sync');
  const drawn = s.edges.map((e) => edge(e, byId));
  const body = groups.join('') + drawn.map((d) => d.line).join('') + s.nodes.map(node).join('') + drawn.map((d) => d.labels).join('');
  return frame(s, W, Hh, n, total, used, body);
}

function drawFlow(s, n, total) {
  const [W] = s.size;
  const lanes = s.lanes;
  const left = 150, right = W - 110;
  const gap = (right - left) / (lanes.length - 1);
  const lx = Object.fromEntries(lanes.map((l, i) => [l.id, left + i * gap]));
  const top = 60, headH = 46;
  let y = top + headH + 34;
  const used = new Set();
  const rows = [];
  let num = 0;
  for (const st of s.steps) {
    if (st.band) {
      y += 8;
      rows.push(`<path class="bandl" d="M40 ${y}H${W - 40}"/><rect x="40" y="${y - 10}" width="${st.band.length * 7 + 16}" height="20" fill="${C.paper}"/><text class="band" x="46" y="${y + 4.5}">${esc(st.band)}</text>`);
      y += 40;
      continue;
    }
    num++;
    const kind = st.kind || 'sync';
    used.add(kind);
    const col = { sync: C.ink, async: C.amber, file: C.mint, dep: C.dim }[kind];
    const x1 = lx[st.a], x2 = lx[st.b];
    if (x1 === undefined || x2 === undefined) throw new Error(`step ${st.label}: unknown lane`);
    const badge = (bx) => `<circle cx="${r1(bx)}" cy="${y}" r="9" fill="${col}"/><text class="num" x="${r1(bx)}" y="${y + 3.7}">${num}</text>`;
    if (st.a === st.b) {
      const flip = x1 > W * 0.6;
      rows.push(`<rect x="${r1(x1 - 5)}" y="${y - 12}" width="10" height="24" fill="${C.panel}" stroke="${col}" stroke-width="1.3"/>${badge(flip ? x1 + 20 : x1 - 20)}<text class="sl self" style="text-anchor:${flip ? 'end' : 'start'}" fill="${col}" x="${r1(flip ? x1 - 13 : x1 + 13)}" y="${y + 4}">${esc(st.label)}</text>`);
    } else {
      const dir = x2 > x1 ? 1 : -1;
      rows.push(`<path class="e ${kind}" d="M${r1(x1 + dir * 10)} ${y}H${r1(x2 - dir * 3)}" marker-end="url(#a-${kind})"/>${badge(x1)}<text class="sl" fill="${col}" x="${r1((x1 + x2) / 2 + (st.dx || 0))}" y="${y - 12}">${esc(st.label)}</text>`);
    }
    y += 46;
  }
  const bottom = y - 10;
  const Hh = bottom + 130;
  const heads = lanes.map((l) => {
    const x = lx[l.id], w = l.w || 150;
    used.add(l.kind);
    const sub = l.sub ? `<text class="nl" x="${r1(x)}" y="${top + 33}">${esc(l.sub)}</text>` : '';
    return `<path class="life" d="M${r1(x)} ${top + headH}V${bottom}"/><g class="${l.kind}"><rect class="box" x="${r1(x - w / 2)}" y="${top}" width="${w}" height="${headH}" rx="4"/><text class="lane" x="${r1(x)}" y="${top + (l.sub ? 19 : 28)}">${esc(l.title)}</text>${sub}</g>`;
  });
  return frame(s, W, Hh, n, total, used, heads.join('') + rows.join(''));
}

function drawMatrix(s, n, total) {
  const m = s.matrix;
  const [W] = s.size;
  const rowH = 30, cw = m.cw || 46, lw = m.labelW || 260, x0 = 56, top = 56;
  const hh = m.rot ? 196 : 46;
  const gx = x0 + lw;
  const ix = gx + m.cols.length * cw + 26;
  const used = new Set();
  const out = [];
  m.cols.forEach((c, i) => {
    const cx = gx + i * cw + cw / 2;
    out.push(m.rot
      ? `<text class="mh" transform="translate(${cx + 3} ${top + hh - 12}) rotate(-52)">${esc(c)}</text>`
      : `<text class="mh" style="text-anchor:middle" x="${cx}" y="${top + hh - 16}">${esc(c)}</text>`);
  });
  let ox = ix;
  for (const inf of m.info || []) { out.push(`<text class="mh" x="${ox}" y="${top + hh - 16}">${esc(inf.title)}</text>`); ox += inf.w; }
  let y = top + hh;
  const y0 = y;
  out.push(`<path class="mr" d="M${x0} ${y}H${W - 56}" style="stroke-opacity:.5"/>`);
  for (const r of m.rows) {
    if (r.band) {
      y += 14;
      out.push(`<text class="band" x="${x0}" y="${y + 19}">${esc(r.band)}</text>`);
      y += rowH;
      out.push(`<path class="mr" d="M${x0} ${y}H${W - 56}" style="stroke-opacity:.5"/>`);
      continue;
    }
    out.push(`<text class="ml" x="${x0}" y="${y + 20}">${esc(r.label)}</text>`);
    for (const [ci, mark] of Object.entries(r.marks || {})) {
      const cx = gx + Number(ci) * cw + cw / 2, cy = y + rowH / 2;
      if (mark === 'd') { used.add('md'); out.push(`<circle cx="${cx}" cy="${cy}" r="5" fill="${C.ink}"/>`); }
      if (mark.includes('c')) { used.add('mc'); out.push(`<circle cx="${cx}" cy="${cy}" r="${mark === 'pc' ? 8.5 : 5.5}" fill="none" stroke="${C.amber}" stroke-width="2"/>`); }
      if (mark.includes('p')) { used.add('mp'); out.push(`<circle cx="${cx}" cy="${cy}" r="${mark === 'pc' ? 4 : 6}" fill="${C.amber}"/>`); }
    }
    ox = ix;
    (m.info || []).forEach((inf, k) => { out.push(`<text class="mi" x="${ox}" y="${y + 20}">${esc((r.info || [])[k] || '')}</text>`); ox += inf.w; });
    y += rowH;
    out.push(`<path class="mr" d="M${x0} ${y}H${W - 56}"/>`);
  }
  for (let i = 0; i <= m.cols.length; i++) out.push(`<path class="mr" d="M${gx + i * cw} ${y0}V${y}"/>`);
  return frame(s, W, y + 130, n, total, used, out.join(''));
}

function section(sec) {
  let body = '';
  if (sec.p) body += [].concat(sec.p).map((p) => `<p>${rich(p)}</p>`).join('');
  if (sec.items) body += `<ul>${sec.items.map((i) => `<li>${rich(i)}</li>`).join('')}</ul>`;
  if (sec.rows) {
    const head = sec.head ? `<thead><tr>${sec.head.map((h) => `<th>${rich(h)}</th>`).join('')}</tr></thead>` : '';
    body += `<div class="tw"><table>${head}<tbody>${sec.rows.map((r) => `<tr>${r.map((c) => `<td>${rich(c)}</td>`).join('')}</tr>`).join('')}</tbody></table></div>`;
  }
  return `<section class="${sec.wide || (sec.head && sec.head.length >= 3) ? 'wide' : ''}"><h2>${esc(sec.h)}</h2>${body}</section>`;
}

const file = (s, n) => `${String(n).padStart(2, '0')}-${s.slug}`;

function page(s, n, all, svg) {
  const prev = all[n - 2], next = all[n];
  const nav = `<nav><a href="index.html">All sheets</a><span>${prev ? `<a href="${file(prev, n - 1)}.html">Back: ${esc(prev.title)}</a>` : ''}${next ? `<a href="${file(next, n + 1)}.html">Next: ${esc(next.title)}</a>` : ''}</span></nav>`;
  return `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>${esc(s.title)} | Spired architecture</title><link rel="stylesheet" href="sheet.css"></head>
<body>${nav}
<header><p class="no">Sheet ${String(n).padStart(2, '0')} of ${String(all.length).padStart(2, '0')}</p><h1>${esc(s.title)}</h1><p class="kicker">${rich(s.kicker)}</p>${s.summary ? `<p class="summary">${rich(s.summary)}</p>` : ''}</header>
<div class="board">${svg}</div>
<main>${(s.sections || []).map(section).join('')}</main>
${nav}
</body></html>
`;
}

function index(all) {
  const rows = all.map((s, i) => `<li><a href="${file(s, i + 1)}.html"><span class="no">${String(i + 1).padStart(2, '0')}</span><span class="t">${esc(s.title)}</span><span class="k">${rich(s.kicker)}</span></a></li>`).join('');
  return `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Spired architecture</title><link rel="stylesheet" href="sheet.css"></head>
<body class="index">
<header><h1>Spired architecture</h1><p class="kicker">A set of drawings of the system: each deployable part on its own sheet, then the parts together.</p>
<p class="summary">Module names keep the <code>spire-</code> prefix. Every sheet was drawn from the code, not from the older documents. ${CHECKED}.</p></header>
<ol class="sheets">${rows}</ol>
</body></html>
`;
}

const files = readdirSync(join(HERE, 'sheets')).filter((f) => f.endsWith('.mjs')).sort();
const all = [];
for (const f of files) all.push((await import(pathToFileURL(join(HERE, 'sheets', f)).href)).default);
all.forEach((s, i) => {
  const n = i + 1;
  const svg = s.lanes ? drawFlow(s, n, all.length) : s.matrix ? drawMatrix(s, n, all.length) : drawBoard(s, n, all.length);
  writeFileSync(join(HERE, `${file(s, n)}.html`), page(s, n, all, svg));
  if (WANT_SVG) writeFileSync(join(HERE, `${file(s, n)}.svg`), svg + '\n');
});
writeFileSync(join(HERE, 'index.html'), index(all));
console.log(`${all.length} sheets written`);
