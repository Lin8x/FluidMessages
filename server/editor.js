'use strict';
// The photo editor, as on the phone (MediaEditor.kt): crop with zoom, rotate, flip, and resize; light and color
// adjustments; filters; drawing with a pen, pencil, marker, highlighter, or eraser; text in eight fonts; other
// photos and your stickers on top; stretching and space around the picture; and background removal, which runs
// in this page with no cloud service (a port of BackgroundCut.kt). Everything is drawn by one function, so what
// you see is what gets sent. Undo and redo cover every step, sliders included.

// Color matrices, the same 4×5 form as Android's ColorMatrix (offsets in 0–255).

const IDENTITY = [1, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 1, 0];

function saturationMatrix(amount) {
  const r = .213 * (1 - amount), g = .715 * (1 - amount), b = .072 * (1 - amount);
  return [r + amount, g, b, 0, 0, r, g + amount, b, 0, 0, r, g, b + amount, 0, 0, 0, 0, 0, 1, 0];
}

function contrastMatrix(amount) {
  const shift = 128 * (1 - amount);
  return [amount, 0, 0, 0, shift, 0, amount, 0, 0, shift, 0, 0, amount, 0, shift, 0, 0, 0, 1, 0];
}

/** [post] applied after [pre], like Android's postConcat. */
function concatMatrix(post, pre) {
  const out = new Array(20);
  for (let row = 0; row < 4; row++) {
    for (let col = 0; col < 5; col++) {
      let sum = col === 4 ? post[row * 5 + 4] : 0;
      for (let k = 0; k < 4; k++) sum += post[row * 5 + k] * pre[k * 5 + col];
      out[row * 5 + col] = sum;
    }
  }
  return out;
}

const EDIT_FILTERS = [['Original', null], ['Vivid', saturationMatrix(1.55)], ['Mono', saturationMatrix(0)],
  ['Noir', concatMatrix(contrastMatrix(1.5), saturationMatrix(0))],
  ['Sepia', [.393, .769, .189, 0, 0, .349, .686, .168, 0, 0, .272, .534, .131, 0, 0, 0, 0, 0, 1, 0]],
  ['Warm', [1.12, 0, 0, 0, 8, 0, 1.02, 0, 0, 0, 0, 0, .88, 0, 0, 0, 0, 0, 1, 0]],
  ['Cool', [.9, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 1.15, 0, 10, 0, 0, 0, 1, 0]],
  ['Fade', [.8, 0, 0, 0, 40, 0, .8, 0, 0, 40, 0, 0, .8, 0, 40, 0, 0, 0, 1, 0]],
  ['Drama', concatMatrix(saturationMatrix(1.2), contrastMatrix(1.35))],
  ['Intense', concatMatrix(contrastMatrix(1.9), saturationMatrix(3))],
  ['Invert', [-1, 0, 0, 0, 255, 0, -1, 0, 0, 255, 0, 0, -1, 0, 255, 0, 0, 0, 1, 0]]];
const ADJUSTMENTS = [['brightness', 'Brightness', -1], ['contrast', 'Contrast', -1], ['saturation', 'Saturation', -1], ['warmth', 'Warmth', -1],
  ['tint', 'Tint', -1], ['fade', 'Fade', 0]];
const NO_ADJUST = {brightness: 0, contrast: 0, saturation: 0, warmth: 0, tint: 0, fade: 0};

/** The filter, then the sliders, as one matrix (or null when the photo's colors are unchanged). */
function editMatrix(filter, adjust) {
  const base = EDIT_FILTERS.find(([name]) => name === filter)?.[1] || null;
  const untouched = Object.values(adjust).every((value) => value === 0);
  if (!base && untouched) return null;
  let out = base || IDENTITY;
  if (adjust.saturation) out = concatMatrix(saturationMatrix(1 + adjust.saturation), out);
  if (adjust.contrast) out = concatMatrix(contrastMatrix(1 + adjust.contrast * .6), out);
  if (adjust.warmth || adjust.tint || adjust.brightness) {
    const b = adjust.brightness * 70, r = 1 + adjust.warmth * .14, g = 1 - adjust.tint * .12, bl = 1 - adjust.warmth * .14;
    out = concatMatrix([r, 0, 0, 0, b, 0, g, 0, 0, b, 0, 0, bl, 0, b, 0, 0, 0, 1, 0], out);
  }
  if (adjust.fade > 0) {
    const keep = 1 - adjust.fade * .3, lift = adjust.fade * 60;
    out = concatMatrix([keep, 0, 0, 0, lift, 0, keep, 0, 0, lift, 0, 0, keep, 0, lift, 0, 0, 0, 1, 0], out);
  }
  return out;
}

function applyMatrix(data, m) {
  for (let i = 0; i < data.length; i += 4) {
    const r = data[i], g = data[i + 1], b = data[i + 2], a = data[i + 3];
    data[i] = m[0] * r + m[1] * g + m[2] * b + m[3] * a + m[4];
    data[i + 1] = m[5] * r + m[6] * g + m[7] * b + m[8] * a + m[9];
    data[i + 2] = m[10] * r + m[11] * g + m[12] * b + m[13] * a + m[14];
    data[i + 3] = m[15] * r + m[16] * g + m[17] * b + m[18] * a + m[19];
  }
}

// Background removal: the same method as the phone's BackgroundCut.kt. Colors are compared in Oklab; the edge's
// main colors are the background; it grows inward from the edge and any spot you tap; small specks go; and the
// outline is feathered with the background's color taken out of the soft edge.

const CUT_LINEAR = Float32Array.from({length: 256}, (_, index) => {
  const c = index / 255;
  return c <= .04045 ? c / 12.92 : ((c + .055) / 1.055) ** 2.4;
});
const pause = () => new Promise((resolve) => setTimeout(resolve, 0));
class CutStopped extends Error {}

function cutThreshold(tolerance) { return .03 + Math.min(1, Math.max(0, tolerance)) * .17; }

function toOklab(r, g, b, out, at) {
  const lr = CUT_LINEAR[r], lg = CUT_LINEAR[g], lb = CUT_LINEAR[b];
  const l = Math.cbrt(.4122214708 * lr + .5363325363 * lg + .0514459929 * lb);
  const m = Math.cbrt(.2119034982 * lr + .6806995451 * lg + .1073969566 * lb);
  const s = Math.cbrt(.0883024619 * lr + .2817188376 * lg + .6299787005 * lb);
  out[at] = .2104542553 * l + .793617785 * m - .0040720468 * s;
  out[at + 1] = 1.9779984951 * l - 2.428592205 * m + .4505937099 * s;
  out[at + 2] = .0259040371 * l + .7827717662 * m - .808675766 * s;
}

function fromOklab([L, A, B]) {
  const l = L + .3963377774 * A + .2158037573 * B, m = L - .1055613458 * A - .0638541728 * B, s = L - .0894841775 * A - 1.291485548 * B;
  const l3 = l * l * l, m3 = m * m * m, s3 = s * s * s;
  const encode = (v) => {
    const c = Math.min(1, Math.max(0, v));
    return Math.round(Math.min(255, Math.max(0, (c <= .0031308 ? 12.92 * c : 1.055 * c ** (1 / 2.4) - .055) * 255)));
  };
  return [encode(4.0767416621 * l3 - 3.3077115913 * m3 + .2309699292 * s3), encode(-1.2684380046 * l3 + 2.6097574011 * m3 - .3413193965 * s3),
    encode(-.0041960863 * l3 - .7034186147 * m3 + 1.707614701 * s3)];
}

function labDistance(lab, i, color) {
  const dl = lab[i * 3] - color[0], da = lab[i * 3 + 1] - color[1], db = lab[i * 3 + 2] - color[2];
  return Math.sqrt(dl * dl + da * da + db * db);
}

/** The edge's background colors: k-means groups of edge pixels that are the biggest, run along three sides, or cover the top. */
function cutEdgeColors(lab, clear, width, height, limit) {
  const samples = [], sides = [];
  const stride = Math.max(1, Math.floor((width + height) / 600));
  const add = (i, side) => { if (!clear[i]) { samples.push(i); sides.push(side); } };
  for (let x = 0; x < width; x += stride) { add(x, 0); add((height - 1) * width + x, 2); }
  for (let y = 0; y < height; y += stride) { add(y * width, 3); add(y * width + width - 1, 1); }
  if (!samples.length) return [];
  const k = Math.min(5, samples.length);
  const centers = [Array.from(lab.subarray(samples[0] * 3, samples[0] * 3 + 3))];
  while (centers.length < k) {
    let bestIndex = -1, bestDistance = -1;
    for (const s of samples) {
      let d = Infinity;
      for (const c of centers) d = Math.min(d, labDistance(lab, s, c));
      if (d > bestDistance) { bestDistance = d; bestIndex = s; }
    }
    if (bestDistance < limit * .5) break;
    centers.push(Array.from(lab.subarray(bestIndex * 3, bestIndex * 3 + 3)));
  }
  const owner = new Int32Array(samples.length);
  for (let round = 0; round < 8; round++) {
    const sums = centers.map(() => [0, 0, 0, 0]);
    samples.forEach((s, index) => {
      let best = 0, bestDistance = Infinity;
      centers.forEach((center, c) => { const d = labDistance(lab, s, center); if (d < bestDistance) { bestDistance = d; best = c; } });
      owner[index] = best;
      const sum = sums[best];
      sum[0] += lab[s * 3]; sum[1] += lab[s * 3 + 1]; sum[2] += lab[s * 3 + 2]; sum[3]++;
    });
    sums.forEach((sum, c) => { if (sum[3] > 0) centers[c] = [sum[0] / sum[3], sum[1] / sum[3], sum[2] / sum[3]]; });
  }
  const total = centers.map(() => 0), perSide = centers.map(() => [0, 0, 0, 0]), sideTotal = [0, 0, 0, 0];
  owner.forEach((c, index) => { total[c]++; perSide[c][sides[index]]++; sideTotal[sides[index]]++; });
  const biggest = total.indexOf(Math.max(...total));
  return centers.filter((_, c) => {
    const share = total[c] / samples.length;
    const along = [0, 1, 2, 3].filter((side) => sideTotal[side] > 0 && perSide[c][side] >= sideTotal[side] * .15).length;
    const top = sideTotal[0] > 0 ? perSide[c][0] / sideTotal[0] : 0;
    return c === biggest || (share >= .08 && (along >= 3 || top >= .25));
  });
}

function cutClearSpecks(gone, width, height, least, queue) {
  const n = width * height;
  const seen = new Uint8Array(n);
  for (let start = 0; start < n; start++) {
    if (gone[start] || seen[start]) continue;
    let head = 0, tail = 0;
    queue[tail++] = start; seen[start] = 1;
    while (head < tail) {
      const i = queue[head++], x = i % width, y = (i - x) / width;
      if (x > 0 && !gone[i - 1] && !seen[i - 1]) { seen[i - 1] = 1; queue[tail++] = i - 1; }
      if (x < width - 1 && !gone[i + 1] && !seen[i + 1]) { seen[i + 1] = 1; queue[tail++] = i + 1; }
      if (y > 0 && !gone[i - width] && !seen[i - width]) { seen[i - width] = 1; queue[tail++] = i - width; }
      if (y < height - 1 && !gone[i + width] && !seen[i + width]) { seen[i + width] = 1; queue[tail++] = i + width; }
    }
    if (tail < least) for (let index = 0; index < tail; index++) gone[queue[index]] = 1;
  }
}

function cutFeather(gone, far, width, height, softness, limit) {
  const n = width * height;
  const radius = Math.floor(Math.min(1, Math.max(0, softness)) * 4);
  const out = new Uint8Array(n);
  if (radius === 0) { for (let i = 0; i < n; i++) out[i] = gone[i] ? 0 : 255; return out; }
  const horizontal = new Float32Array(n);
  for (let y = 0; y < height; y++) {
    const row = y * width;
    for (let x = 0; x < width; x++) {
      let sum = 0;
      for (let dx = -radius; dx <= radius; dx++) if (!gone[row + Math.min(width - 1, Math.max(0, x + dx))]) sum++;
      horizontal[row + x] = sum;
    }
  }
  const span = 2 * radius + 1;
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      let sum = 0;
      for (let dy = -radius; dy <= radius; dy++) sum += horizontal[Math.min(height - 1, Math.max(0, y + dy)) * width + x];
      const i = y * width + x;
      let a = (sum / (span * span) - .35) / .65;
      if (a < 1) {
        const look = Math.min(1, Math.max(0, (far[i] - limit * .6) / (limit * .9)));
        a = Math.min(a, look * look * (3 - 2 * look) + (gone[i] ? 0 : .25));
      }
      out[i] = Math.round(Math.min(1, Math.max(0, a)) * 255);
    }
  }
  return out;
}

/**
 * Finds the background of RGBA [pixels] ([width] × [height]). Returns the kept alpha (0 removed, 255 kept), the
 * background colors, and how much was removed. [stop] is checked now and then, so a newer request can take over.
 */
async function findBackground(pixels, width, height, {tolerance = .4, softness = .35, fromEdges = true, taps = []}, progress, stop) {
  const n = width * height;
  const limit = cutThreshold(tolerance);
  const lab = new Float32Array(n * 3);
  const clear = new Uint8Array(n);
  const check = async (fraction) => { progress(fraction); await pause(); if (stop()) throw new CutStopped(); };
  for (let i = 0; i < n; i++) {
    toOklab(pixels[i * 4], pixels[i * 4 + 1], pixels[i * 4 + 2], lab, i * 3);
    clear[i] = pixels[i * 4 + 3] < 128 ? 1 : 0;
    if ((i & 0x3FFFF) === 0) await check(.25 * i / n);
  }
  const palette = fromEdges ? cutEdgeColors(lab, clear, width, height, limit) : [];
  const tapSeeds = [];
  for (const [x, y] of taps) {
    if (x < 0 || y < 0 || x >= width || y >= height) continue;
    const sum = [0, 0, 0];
    let count = 0;
    for (let dy = -2; dy <= 2; dy++) for (let dx = -2; dx <= 2; dx++) {
      const px = x + dx, py = y + dy;
      if (px < 0 || py < 0 || px >= width || py >= height) continue;
      const at = (py * width + px) * 3;
      sum[0] += lab[at]; sum[1] += lab[at + 1]; sum[2] += lab[at + 2]; count++;
    }
    palette.push([sum[0] / count, sum[1] / count, sum[2] / count]);
    tapSeeds.push(y * width + x);
  }
  await check(.3);
  const far = new Float32Array(n);
  for (let i = 0; i < n; i++) {
    let best = Infinity;
    const at = i * 3;
    for (const color of palette) {
      const dl = lab[at] - color[0], da = lab[at + 1] - color[1], db = lab[at + 2] - color[2];
      const d = dl * dl + da * da + db * db;
      if (d < best) best = d;
    }
    far[i] = palette.length ? Math.sqrt(best) : Infinity;
    if ((i & 0x3FFFF) === 0) await check(.3 + .2 * i / n);
  }
  const gone = new Uint8Array(n);
  const queue = new Int32Array(n);
  let head = 0, tail = 0;
  const seed = (i) => { if (!gone[i]) { gone[i] = 1; queue[tail++] = i; } };
  for (let i = 0; i < n; i++) if (clear[i]) seed(i);
  if (fromEdges && palette.length) {
    for (let x = 0; x < width; x++) { if (far[x] < limit) seed(x); const b = (height - 1) * width + x; if (far[b] < limit) seed(b); }
    for (let y = 0; y < height; y++) { const l = y * width; if (far[l] < limit) seed(l); const r = l + width - 1; if (far[r] < limit) seed(r); }
  }
  for (const i of tapSeeds) seed(i);
  const step = limit * .3, near = limit * 1.7;
  let visited = 0;
  while (head < tail) {
    const i = queue[head++], x = i % width, y = (i - x) / width;
    for (let k = 0; k < 4; k++) {
      const j = k === 0 ? (x > 0 ? i - 1 : -1) : k === 1 ? (x < width - 1 ? i + 1 : -1) : k === 2 ? (y > 0 ? i - width : -1) : (y < height - 1 ? i + width : -1);
      if (j < 0 || gone[j]) continue;
      let join = clear[j] || far[j] < limit;
      if (!join && far[j] < near) {
        const a = i * 3, b = j * 3;
        const dl = lab[a] - lab[b], da = lab[a + 1] - lab[b + 1], db = lab[a + 2] - lab[b + 2];
        join = Math.sqrt(dl * dl + da * da + db * db) < step;
      }
      if (join) { gone[j] = 1; queue[tail++] = j; }
    }
    if ((++visited & 0x3FFFF) === 0) await check(.5 + .25 * tail / n);
  }
  await check(.75);
  cutClearSpecks(gone, width, height, Math.max(6, Math.floor(n / 2500)), queue);
  await check(.85);
  const alpha = cutFeather(gone, far, width, height, softness, limit);
  let removed = 0;
  for (let i = 0; i < n; i++) removed += 255 - alpha[i];
  progress(1);
  return {alpha, width, height, background: palette.map(fromOklab), removed: removed / (255 * Math.max(1, n))};
}

function maskSample(mask, x, y) {
  const cx = Math.min(mask.width - 1, Math.max(0, x)), cy = Math.min(mask.height - 1, Math.max(0, y));
  const x0 = Math.floor(cx), y0 = Math.floor(cy), x1 = Math.min(x0 + 1, mask.width - 1), y1 = Math.min(y0 + 1, mask.height - 1);
  const fx = cx - x0, fy = cy - y0, at = (px, py) => mask.alpha[py * mask.width + px] / 255;
  return (at(x0, y0) * (1 - fx) + at(x1, y0) * fx) * (1 - fy) + (at(x0, y1) * (1 - fx) + at(x1, y1) * fx) * fy;
}

/** Takes the background's color back out of soft edge pixels (full size), so there's no light or dark rim. */
function cutDecontaminate(pixels, width, height, mask) {
  if (!mask.background.length) return;
  const sx = mask.width / width, sy = mask.height / height;
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      const a = maskSample(mask, (x + .5) * sx - .5, (y + .5) * sy - .5);
      if (a <= .03 || a >= .97) continue;
      const i = (y * width + x) * 4;
      let bg = mask.background[0], best = Infinity;
      for (const color of mask.background) {
        const d = (color[0] - pixels[i]) ** 2 + (color[1] - pixels[i + 1]) ** 2 + (color[2] - pixels[i + 2]) ** 2;
        if (d < best) { best = d; bg = color; }
      }
      for (let c = 0; c < 3; c++) pixels[i + c] = (pixels[i + c] - (1 - a) * bg[c]) / a;
    }
  }
}

// Pictures as canvases

function canvasOf(width, height) {
  return Object.assign(document.createElement('canvas'), {width: Math.max(1, Math.round(width)), height: Math.max(1, Math.round(height))});
}

function scaledCanvas(source, longest) {
  const scale = Math.min(1, longest / Math.max(source.width, source.height));
  const out = canvasOf(source.width * scale, source.height * scale);
  const context = out.getContext('2d');
  context.imageSmoothingQuality = 'high';
  context.drawImage(source, 0, 0, out.width, out.height);
  return out;
}

async function loadPicture(blob, longest) {
  let bitmap;
  try { bitmap = await createImageBitmap(blob); } catch (error) { throw new Error('This picture can’t be opened for editing.'); }
  const out = scaledCanvas(bitmap, longest);
  bitmap.close?.();
  // Files from the server come without their type, so anything but a JPEG is checked for see-through pixels.
  out.hasAlpha = !/jpe?g/i.test(blob.type || '') && canvasHasAlpha(out);
  return out;
}

function canvasHasAlpha(canvas) {
  const data = canvas.getContext('2d').getImageData(0, 0, canvas.width, canvas.height).data;
  for (let i = 3; i < data.length; i += 4) if (data[i] < 255) return true;
  return false;
}

const previewCache = new WeakMap();
function previewOf(canvas) {
  if (Math.max(canvas.width, canvas.height) <= 1600) return canvas;
  if (!previewCache.has(canvas)) previewCache.set(canvas, scaledCanvas(canvas, 1600));
  return previewCache.get(canvas);
}

const colorCache = new WeakMap();
/** The photo (or its cut-out) with the filter and sliders applied, at preview or full size. Cached. */
function coloredSource(state, preview) {
  const raw = state.cutout?.source || state.base;
  const source = preview ? previewOf(raw) : raw;
  const matrix = editMatrix(state.filter, state.adjust);
  if (!matrix) return source;
  const key = JSON.stringify(matrix);
  const cached = colorCache.get(source);
  if (cached?.key === key) return cached.canvas;
  const out = canvasOf(source.width, source.height);
  const context = out.getContext('2d');
  context.drawImage(source, 0, 0);
  const image = context.getImageData(0, 0, out.width, out.height);
  applyMatrix(image.data, matrix);
  context.putImageData(image, 0, 0);
  colorCache.set(source, {key, canvas: out});
  return out;
}

// The picture's state. Edits make a new one, which is what undo goes back through.

function newEditState(base) {
  return {base, stretchX: 1, stretchY: 1, padTop: 0, padBottom: 0, padSides: 0, padColor: '#ffffff', filter: 'Original', adjust: {...NO_ADJUST},
    strokes: [], layers: [], cutout: null};
}
const imageWidth = (state) => state.base.width * state.stretchX;
const imageHeight = (state) => state.base.height * state.stretchY;
const pictureWidth = (state) => Math.max(1, Math.round(imageWidth(state) + state.padSides * 2));
const pictureHeight = (state) => Math.max(1, Math.round(imageHeight(state) + state.padTop + state.padBottom));
const imageRect = (state) => ({x: state.padSides, y: state.padTop, w: imageWidth(state), h: imageHeight(state)});

const EDIT_BRUSHES = ['Pen', 'Pencil', 'Marker', 'Highlighter', 'Eraser'];
const EDIT_SWATCHES = ['#ffffff', '#000000', '#ff3b30', '#ff9500', '#ffcc00', '#34c759', '#00c7be', '#007aff', '#5856d6', '#af52de', '#ff2d55', '#a2845e'];
const EDIT_FONTS = {Bold: ['', '700', '"Roboto Condensed", "Arial Narrow", "Helvetica Neue", Arial, sans-serif'], Classic: ['', '700', 'Inter, "Helvetica Neue", Arial, sans-serif'],
  Heavy: ['', '900', '"Arial Black", "Helvetica Neue", sans-serif'], Serif: ['', '700', 'Georgia, "Times New Roman", serif'],
  Typewriter: ['', '400', '"Courier New", ui-monospace, monospace'], Handwritten: ['', '700', '"Segoe Script", "Bradley Hand", "Brush Script MT", cursive'],
  Casual: ['', '400', '"Comic Sans MS", "Comic Neue", "Chalkboard SE", cursive'], 'Small caps': ['small-caps', '400', '"Helvetica Neue", Arial, sans-serif']};
const TEXT_LOOKS = ['Outline', 'Box', 'Plain'];

function textFont(layer) {
  const [variant, weight, family] = EDIT_FONTS[layer.font] || EDIT_FONTS.Bold;
  return (variant ? variant + ' ' : '') + weight + ' ' + Math.max(1, layer.size * layer.scale) + 'px ' + family;
}

const measureContext = canvasOf(1, 1).getContext('2d');

function textBounds(layer) {
  measureContext.font = textFont(layer);
  const lines = layer.text.split('\n');
  const width = Math.max(...lines.map((line) => measureContext.measureText(line).width), 1);
  const height = layer.size * layer.scale * 1.2 * lines.length;
  return {x: layer.center.x - width / 2 - 12, y: layer.center.y - height / 2 - 8, w: width + 24, h: height + 16};
}

function layerBounds(layer) {
  if (layer.type === 'text') return textBounds(layer);
  const w = layer.picture.width * layer.scale, h = layer.picture.height * layer.scale;
  return {x: layer.center.x - w / 2, y: layer.center.y - h / 2, w, h};
}

/** Whether a point (picture coordinates) is on a layer, taking its turn into account. */
function layerHit(layer, point) {
  const angle = -layer.rotation * Math.PI / 180;
  const dx = point.x - layer.center.x, dy = point.y - layer.center.y;
  const x = layer.center.x + dx * Math.cos(angle) - dy * Math.sin(angle), y = layer.center.y + dx * Math.sin(angle) + dy * Math.cos(angle);
  const box = layerBounds(layer);
  return x >= box.x && x <= box.x + box.w && y >= box.y && y <= box.y + box.h;
}

const luminanceOf = (hex) => { const [r, g, b] = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255); return .2126 * r + .7152 * g + .0722 * b; };

function strokePath(context, stroke) {
  let points = stroke.points;
  if (stroke.brush === 'Pencil') {
    // A pencil's slightly rough line, the same every time it's drawn.
    const rough = [];
    const wobble = stroke.width * .12;
    for (let index = 0; index < points.length; index++) {
      const noise = (seed) => { const value = Math.sin(seed * 12.9898 + index * 78.233) * 43758.5453; return (value - Math.floor(value) - .5) * 2 * wobble; };
      rough.push({x: points[index].x + noise(1), y: points[index].y + noise(2)});
    }
    points = rough;
  }
  context.beginPath();
  context.moveTo(points[0].x, points[0].y);
  if (points.length === 1) context.lineTo(points[0].x + .1, points[0].y);
  for (let index = 1; index < points.length; index++) {
    const previous = points[index - 1], point = points[index];
    context.quadraticCurveTo(previous.x, previous.y, (previous.x + point.x) / 2, (previous.y + point.y) / 2);
  }
}

/** A see-through layer the same size and transform as [context]. */
function layerLike(context) {
  const layer = canvasOf(context.canvas.width, context.canvas.height).getContext('2d');
  layer.setTransform(context.getTransform());
  return layer;
}

function composite(context, layer) {
  context.save();
  context.setTransform(1, 0, 0, 1, 0, 0);
  context.drawImage(layer.canvas, 0, 0);
  context.restore();
}

/** Draws the whole picture in picture coordinates: for the preview ([preview] true) and for the file that's sent. */
function renderEdit(context, state, preview) {
  const rect = imageRect(state);
  const width = pictureWidth(state), height = pictureHeight(state);
  if (state.padTop || state.padBottom || state.padSides) {
    // Only around the photo, so a removed background stays see-through.
    context.fillStyle = state.padColor;
    context.fillRect(0, 0, width, rect.y);
    context.fillRect(0, rect.y + rect.h, width, height - rect.y - rect.h);
    context.fillRect(0, rect.y, rect.x, rect.h);
    context.fillRect(rect.x + rect.w, rect.y, width - rect.x - rect.w, rect.h);
  }
  const photo = coloredSource(state, preview);
  const cut = state.cutout;
  context.imageSmoothingQuality = 'high';
  if (!cut) context.drawImage(photo, rect.x, rect.y, rect.w, rect.h);
  else {
    // What's kept is drawn as alpha first (the mask, then Erase and Keep strokes), and the photo goes in through it.
    const keep = layerLike(context);
    keep.beginPath();
    keep.rect(rect.x, rect.y, rect.w, rect.h);
    keep.clip();
    if (cut.mask) keep.drawImage(cut.mask, rect.x, rect.y, rect.w, rect.h);
    else { keep.fillStyle = '#000'; keep.fillRect(rect.x, rect.y, rect.w, rect.h); }
    for (const touch of cut.touches) {
      if (!touch.points.length) continue;
      keep.globalCompositeOperation = touch.brush === 'Eraser' ? 'destination-out' : 'source-over';
      keep.lineWidth = touch.width;
      keep.lineCap = keep.lineJoin = 'round';
      keep.strokeStyle = '#000';
      strokePath(keep, touch);
      keep.stroke();
    }
    keep.globalCompositeOperation = 'source-in';
    keep.drawImage(photo, rect.x, rect.y, rect.w, rect.h);
    composite(context, keep);
  }
  if (state.strokes.length) {
    // Strokes go on their own layer, so the eraser and highlighter behave like real ones.
    const ink = layerLike(context);
    for (const stroke of state.strokes) {
      if (!stroke.points.length) continue;
      ink.save();
      ink.lineCap = ink.lineJoin = 'round';
      ink.strokeStyle = stroke.color;
      ink.lineWidth = stroke.width;
      ink.globalCompositeOperation = 'source-over';
      if (stroke.brush === 'Pencil') { ink.globalAlpha = 210 / 255; ink.lineWidth = stroke.width * .55; }
      else if (stroke.brush === 'Marker') ink.lineWidth = stroke.width * 1.8;
      else if (stroke.brush === 'Highlighter') { ink.globalAlpha = 110 / 255; ink.lineWidth = stroke.width * 3.2; ink.lineCap = 'square'; ink.globalCompositeOperation = 'multiply'; }
      else if (stroke.brush === 'Eraser') { ink.lineWidth = stroke.width * 2.4; ink.globalCompositeOperation = 'destination-out'; }
      strokePath(ink, stroke);
      ink.stroke();
      ink.restore();
    }
    composite(context, ink);
  }
  for (const layer of state.layers) {
    context.save();
    context.translate(layer.center.x, layer.center.y);
    context.rotate(layer.rotation * Math.PI / 180);
    context.translate(-layer.center.x, -layer.center.y);
    if (layer.type === 'picture') {
      const box = layerBounds(layer);
      context.drawImage(layer.picture, box.x, box.y, box.w, box.h);
    } else {
      const size = layer.size * layer.scale;
      const lines = layer.text.split('\n');
      const lineHeight = size * 1.2;
      context.font = textFont(layer);
      context.textAlign = 'center';
      context.textBaseline = 'middle';
      const bright = luminanceOf(layer.color) > .5;
      if (layer.look === 'Box') {
        const box = textBounds(layer);
        context.fillStyle = bright ? 'rgba(0,0,0,.8)' : 'rgba(255,255,255,.93)';
        context.beginPath();
        context.roundRect ? context.roundRect(box.x, box.y, box.w, box.h, size * .25) : context.rect(box.x, box.y, box.w, box.h);
        context.fill();
      }
      let y = layer.center.y - lineHeight * (lines.length - 1) / 2;
      for (const line of lines) {
        if (layer.look === 'Outline') {
          context.lineWidth = size * .13;
          context.lineJoin = 'round';
          context.strokeStyle = bright ? '#000' : '#fff';
          context.strokeText(line, layer.center.x, y);
        }
        context.fillStyle = layer.color;
        context.fillText(line, layer.center.x, y);
        y += lineHeight;
      }
    }
    context.restore();
  }
}

/** The finished picture, at most 4096 pixels on a side and 16 megapixels. */
function flattenEdit(state, maxPixels = 16000000) {
  const width = pictureWidth(state), height = pictureHeight(state);
  const shrink = Math.min(1, Math.sqrt(maxPixels / (width * height)), 4096 / Math.max(width, height));
  const out = canvasOf(width * shrink, height * shrink);
  const context = out.getContext('2d');
  context.scale(out.width / width, out.height / height);
  renderEdit(context, state, false);
  out.hasAlpha = !!state.cutout || !!state.base.hasAlpha;
  return out;
}

function trimmedSticker(canvas) {
  const data = canvas.getContext('2d').getImageData(0, 0, canvas.width, canvas.height).data;
  let left = canvas.width, top = canvas.height, right = -1, bottom = -1;
  for (let y = 0; y < canvas.height; y++) for (let x = 0; x < canvas.width; x++) {
    if (data[(y * canvas.width + x) * 4 + 3] >= 16) { left = Math.min(left, x); right = Math.max(right, x); top = Math.min(top, y); bottom = Math.max(bottom, y); }
  }
  if (right < 0) return canvas;
  const room = Math.floor(Math.max(right - left, bottom - top) * .03);
  left = Math.max(0, left - room); top = Math.max(0, top - room);
  right = Math.min(canvas.width, right + 1 + room); bottom = Math.min(canvas.height, bottom + 1 + room);
  const scale = Math.min(1, 512 / Math.max(right - left, bottom - top));
  const out = canvasOf((right - left) * scale, (bottom - top) * scale);
  out.getContext('2d').drawImage(canvas, left, top, right - left, bottom - top, 0, 0, out.width, out.height);
  return out;
}

function transformed(canvas, turn) {
  const out = turn ? canvasOf(canvas.height, canvas.width) : canvasOf(canvas.width, canvas.height);
  const context = out.getContext('2d');
  if (turn) { context.translate(out.width, 0); context.rotate(Math.PI / 2); } else { context.translate(out.width, 0); context.scale(-1, 1); }
  context.drawImage(canvas, 0, 0);
  out.hasAlpha = canvas.hasAlpha;
  return out;
}

const RATIOS = [['Free', null], ['Square', 1], ['4:3', 4 / 3], ['3:4', 3 / 4], ['16:9', 16 / 9], ['9:16', 9 / 16]];

function centeredCrop(width, height, ratio) {
  if (!ratio) return {l: 0, t: 0, r: width, b: height};
  const w = Math.min(width, height * ratio), h = w / ratio;
  return {l: (width - w) / 2, t: (height - h) / 2, r: (width + w) / 2, b: (height + h) / 2};
}

/** The box after dragging [handle] (move, or edges l/t/r/b and corners like 'lt') by [dx], [dy] picture units. */
function resizeCrop(start, handle, dx, dy, width, height, ratio) {
  const least = Math.min(Math.max(16, Math.min(width, height) * .05), width, height);
  if (handle === 'move') {
    const ox = Math.min(Math.max(dx, -start.l), width - start.r), oy = Math.min(Math.max(dy, -start.t), height - start.b);
    return {l: start.l + ox, t: start.t + oy, r: start.r + ox, b: start.b + oy};
  }
  let {l, t, r, b} = start;
  if (handle.includes('l')) l = Math.min(Math.max(0, l + dx), r - least);
  if (handle.includes('r')) r = Math.max(Math.min(width, r + dx), l + least);
  if (handle.includes('t')) t = Math.min(Math.max(0, t + dy), b - least);
  if (handle.includes('b')) b = Math.max(Math.min(height, b + dy), t + least);
  if (ratio) {
    // Keep the shape: the dragged side leads, anchored at the opposite side or corner, and the box stays on the picture.
    const horizontal = handle.includes('l') || handle.includes('r');
    let w = r - l, h = b - t;
    if (horizontal && (!(handle.includes('t') || handle.includes('b')) || Math.abs(dx) >= Math.abs(dy))) h = w / ratio; else w = h * ratio;
    const ax = handle.includes('l') ? start.r : handle.includes('r') ? start.l : (start.l + start.r) / 2;
    const ay = handle.includes('t') ? start.b : handle.includes('b') ? start.t : (start.t + start.b) / 2;
    const roomX = handle.includes('l') ? ax : handle.includes('r') ? width - ax : 2 * Math.min(ax, width - ax);
    const roomY = handle.includes('t') ? ay : handle.includes('b') ? height - ay : 2 * Math.min(ay, height - ay);
    const fit = Math.min(1, roomX / w, roomY / h);
    w *= fit; h *= fit;
    l = handle.includes('l') ? ax - w : handle.includes('r') ? ax : ax - w / 2;
    t = handle.includes('t') ? ay - h : handle.includes('b') ? ay : ay - h / 2;
    r = l + w; b = t + h;
  }
  return {l, t, r, b};
}

// The editor page

/**
 * Opens the editor, full screen, on [blob]. [done] gets (picture blob, file name, send now). With [sticker] it
 * opens on Background with the background already being removed, and its one button saves the sticker.
 */
async function openPhotoEditor(blob, {done, sticker = false}) {
  let original;
  try { original = await loadPicture(blob, 2048); } catch (error) { showError(error); return; }
  const sheet = dialog(sticker ? 'Make a sticker' : 'Edit photo');
  sheet.classList.add('editor-sheet');
  const head = sheet.querySelector('.sheet-head');
  const undoButton = element('button', '↶ Undo', 'quiet');
  const redoButton = element('button', '↷', 'quiet');
  redoButton.setAttribute('aria-label', 'Redo');
  const doneButton = element('button', 'Done', 'quiet');
  const sendButton = element('button', sticker ? 'Save sticker' : 'Send', 'primary');
  for (const button of [undoButton, redoButton, doneButton, sendButton]) button.type = 'button';
  doneButton.hidden = sticker;
  doneButton.title = 'Put it in the message box, to add words first';
  head.append(undoButton, redoButton, doneButton, sendButton);

  const stage = element('div', '', 'editor-stage');
  const canvas = Object.assign(document.createElement('canvas'), {className: 'editor-canvas', tabIndex: 0});
  canvas.setAttribute('role', 'img');
  canvas.setAttribute('aria-label', 'The picture you’re editing');
  stage.append(canvas);
  const panel = element('div', '', 'editor-panel');
  const tabs = element('div', '', 'editor-tools');
  tabs.setAttribute('role', 'tablist');
  tabs.setAttribute('aria-label', 'Tools');
  sheet.append(stage, panel, tabs);

  let state = newEditState(original);
  const undo = [], redo = [];
  let tool = sticker ? 'Background' : 'Crop';
  let brush = 'Pen', ink = '#ff3b30', thickness = 12;
  let selected = null;
  let crop = null, cropRatio = null;
  let view = {zoom: 1, panX: 0, panY: 0};
  let drawing = null;
  let sliding = false;
  let nextId = 1;
  let cutMode = 'Tap to remove', strength = .4, softness = .35, cutToken = 0, cutBusy = null, cutNote = '';
  const unit = () => Math.max(pictureWidth(state), pictureHeight(state)) / 1000;

  const trim = () => { while (undo.length > 30) undo.shift(); };
  const commit = (next) => { undo.push(state); trim(); redo.length = 0; state = next; sliding = false; changed(); };
  // Sliders change the picture live; the step for undo is taken once, when a slide starts.
  const slide = (next) => { if (!sliding) { undo.push(state); trim(); redo.length = 0; sliding = true; } state = next; requestDraw(); updateHistory(); };
  const endSlide = () => { sliding = false; };
  const replaceBase = (base) => { commit(newEditState(base)); crop = null; selected = null; view = {zoom: 1, panX: 0, panY: 0}; };
  const updateHistory = () => { undoButton.disabled = !undo.length; redoButton.disabled = !redo.length; };
  undoButton.addEventListener('click', () => { if (!undo.length) return; redo.push(state); state = undo.pop(); selected = null; crop = null; changed(); });
  redoButton.addEventListener('click', () => { if (!redo.length) return; undo.push(state); state = redo.pop(); changed(); });
  const changed = () => { updateHistory(); requestDraw(); drawPanel(); };

  // Drawing the stage

  const PAD = 12;
  let frame = 0;
  const requestDraw = () => { if (!frame) frame = requestAnimationFrame(() => { frame = 0; draw(); }); };
  const screen = () => {
    const box = stage.getBoundingClientRect();
    const width = pictureWidth(state), height = pictureHeight(state);
    const fit = Math.min((box.width - PAD * 2) / width, (box.height - PAD * 2) / height);
    const zoom = tool === 'Crop' ? view.zoom : 1;
    return {box, fit, scale: fit * zoom, cx: box.width / 2 + (tool === 'Crop' ? view.panX : 0), cy: box.height / 2 + (tool === 'Crop' ? view.panY : 0), width, height};
  };
  const toPicture = (event) => {
    const s = screen();
    const x = event.clientX - s.box.left, y = event.clientY - s.box.top;
    return {x: (x - s.cx) / s.scale + s.width / 2, y: (y - s.cy) / s.scale + s.height / 2};
  };
  const toScreen = (s, x, y) => [s.cx + (x - s.width / 2) * s.scale, s.cy + (y - s.height / 2) * s.scale];

  function draw() {
    const s = screen();
    const ratio = devicePixelRatio || 1;
    canvas.width = Math.max(1, Math.round(s.box.width * ratio));
    canvas.height = Math.max(1, Math.round(s.box.height * ratio));
    const context = canvas.getContext('2d');
    context.setTransform(1, 0, 0, 1, 0, 0);
    context.clearRect(0, 0, canvas.width, canvas.height);
    const [left, top] = toScreen(s, 0, 0);
    context.setTransform(ratio * s.scale, 0, 0, ratio * s.scale, ratio * left, ratio * top);
    const live = drawing ? withDrawing(state, drawing) : state;
    if (live.cutout || live.base.hasAlpha) {
      // A checkerboard behind see-through parts, as photo apps show it.
      const cell = 8 / s.scale;
      for (let y = 0; y < s.height; y += cell) for (let x = 0; x < s.width; x += cell) {
        context.fillStyle = (Math.floor(x / cell) + Math.floor(y / cell)) % 2 ? '#6b6b70' : '#9a9a9e';
        context.fillRect(x, y, Math.min(cell, s.width - x), Math.min(cell, s.height - y));
      }
    }
    renderEdit(context, live, true);
    context.setTransform(ratio, 0, 0, ratio, 0, 0);
    const chosen = state.layers.find((layer) => layer.id === selected);
    if (chosen && tool !== 'Crop') {
      const box = layerBounds(chosen);
      const [x, y] = toScreen(s, chosen.center.x, chosen.center.y);
      context.save();
      context.translate(x, y);
      context.rotate(chosen.rotation * Math.PI / 180);
      context.setLineDash([7, 5]);
      context.lineWidth = 2;
      context.strokeStyle = '#fff';
      context.shadowColor = 'rgba(0,0,0,.6)';
      context.shadowBlur = 3;
      context.strokeRect((box.x - chosen.center.x) * s.scale, (box.y - chosen.center.y) * s.scale, box.w * s.scale, box.h * s.scale);
      context.restore();
    }
    if (tool === 'Crop') {
      const box = crop || {l: 0, t: 0, r: s.width, b: s.height};
      const [x1, y1] = toScreen(s, box.l, box.t), [x2, y2] = toScreen(s, box.r, box.b);
      const [px1, py1] = toScreen(s, 0, 0), [px2, py2] = toScreen(s, s.width, s.height);
      context.fillStyle = 'rgba(0,0,0,.55)';
      context.beginPath();
      context.rect(px1, py1, px2 - px1, py2 - py1);
      context.rect(x1, y1, x2 - x1, y2 - y1);
      context.fill('evenodd');
      context.strokeStyle = '#fff';
      context.lineWidth = 2;
      context.strokeRect(x1, y1, x2 - x1, y2 - y1);
      context.globalAlpha = .5;
      context.lineWidth = 1;
      for (const third of [1, 2]) {
        context.beginPath();
        context.moveTo(x1 + (x2 - x1) * third / 3, y1); context.lineTo(x1 + (x2 - x1) * third / 3, y2);
        context.moveTo(x1, y1 + (y2 - y1) * third / 3); context.lineTo(x2, y1 + (y2 - y1) * third / 3);
        context.stroke();
      }
      context.globalAlpha = 1;
      context.fillStyle = '#fff';
      for (const [x, y] of [[x1, y1], [x2, y1], [x1, y2], [x2, y2]]) { context.beginPath(); context.arc(x, y, 8, 0, Math.PI * 2); context.fill(); }
    }
  }

  const withDrawing = (current, points) => {
    if (tool === 'Background') {
      const cut = current.cutout || {mask: null, source: null, touches: [], taps: [], removed: 0};
      return {...current, cutout: {...cut, touches: [...cut.touches, cutTouch(points)]}};
    }
    return {...current, strokes: [...current.strokes, {points, color: ink, width: thickness * unit(), brush}]};
  };
  const cutTouch = (points) => ({points, width: thickness * unit() * 2.4, brush: cutMode === 'Erase' ? 'Eraser' : 'Pen'});

  // Pointer gestures, by tool: draw and brush strokes; move, resize, and turn layers; crop and zoom.

  const pointers = new Map();
  let gesture = null;
  const handleAt = (s, event) => {
    const box = crop || {l: 0, t: 0, r: s.width, b: s.height};
    const [x1, y1] = toScreen(s, box.l, box.t), [x2, y2] = toScreen(s, box.r, box.b);
    const x = event.clientX - s.box.left, y = event.clientY - s.box.top;
    const reach = 22;
    let handle = '';
    if (Math.abs(x - x1) < reach && y > y1 - reach && y < y2 + reach) handle += 'l';
    if (Math.abs(x - x2) < reach && y > y1 - reach && y < y2 + reach) handle += 'r';
    if (Math.abs(y - y1) < reach && x > x1 - reach && x < x2 + reach) handle += 't';
    if (Math.abs(y - y2) < reach && x > x1 - reach && x < x2 + reach) handle += 'b';
    if (handle) return handle;
    const whole = !crop || (box.r - box.l >= s.width - .5 && box.b - box.t >= s.height - .5);
    return x > x1 && x < x2 && y > y1 && y < y2 && !whole ? 'move' : 'pan';
  };
  canvas.addEventListener('pointerdown', (event) => {
    canvas.setPointerCapture(event.pointerId);
    pointers.set(event.pointerId, {x: event.clientX, y: event.clientY});
    const point = toPicture(event);
    if (pointers.size === 2) {
      // Two fingers: zoom the crop view, or resize and turn the chosen layer.
      const [a, b] = [...pointers.values()];
      gesture = {kind: 'pinch', distance: Math.hypot(a.x - b.x, a.y - b.y), angle: Math.atan2(b.y - a.y, b.x - a.x), view: {...view},
        layer: state.layers.find((layer) => layer.id === selected), recorded: false};
      drawing = null;
      return;
    }
    if (tool === 'Draw' || (tool === 'Background' && cutMode !== 'Tap to remove')) { drawing = [point]; gesture = {kind: 'draw'}; return; }
    if (tool === 'Background') { gesture = {kind: 'tap', start: point}; return; }
    if (tool === 'Crop') {
      const s = screen();
      gesture = {kind: 'crop', handle: handleAt(s, event), start: {x: event.clientX, y: event.clientY}, box: crop || {l: 0, t: 0, r: s.width, b: s.height},
        view: {...view}, scale: s.scale};
      return;
    }
    if (tool === 'Text' || tool === 'Images') {
      const hit = [...state.layers].reverse().find((layer) => layer.id === selected && layerHit(layer, point))
        || [...state.layers].reverse().find((layer) => layerHit(layer, point));
      gesture = {kind: 'layer', id: hit?.id || null, start: point, moved: false, recorded: false};
      if (hit) { selected = hit.id; drawPanel(); requestDraw(); }
    }
  });
  canvas.addEventListener('pointermove', (event) => {
    if (!pointers.has(event.pointerId)) return;
    pointers.set(event.pointerId, {x: event.clientX, y: event.clientY});
    if (!gesture) return;
    if (gesture.kind === 'pinch' && pointers.size >= 2) {
      const [a, b] = [...pointers.values()];
      const zoom = Math.hypot(a.x - b.x, a.y - b.y) / Math.max(1, gesture.distance);
      const turn = (Math.atan2(b.y - a.y, b.x - a.x) - gesture.angle) * 180 / Math.PI;
      if (tool === 'Crop') view = {...gesture.view, zoom: Math.min(6, Math.max(1, gesture.view.zoom * zoom))};
      else if (gesture.layer) {
        if (!gesture.recorded) { undo.push(state); trim(); redo.length = 0; gesture.recorded = true; }
        const base = gesture.layer;
        state = {...state, layers: state.layers.map((layer) => layer.id === base.id
          ? {...layer, scale: Math.min(12, Math.max(.1, base.scale * zoom)), rotation: base.rotation + turn} : layer)};
      }
      requestDraw();
      return;
    }
    const point = toPicture(event);
    if (gesture.kind === 'draw') { drawing.push(point); requestDraw(); return; }
    if (gesture.kind === 'crop') {
      const dx = event.clientX - gesture.start.x, dy = event.clientY - gesture.start.y;
      if (gesture.handle === 'pan') {
        if (view.zoom > 1) view = {...view, panX: gesture.view.panX + dx, panY: gesture.view.panY + dy};
      } else crop = resizeCrop(gesture.box, gesture.handle, dx / gesture.scale, dy / gesture.scale, pictureWidth(state), pictureHeight(state), cropRatio);
      requestDraw();
      return;
    }
    if (gesture.kind === 'layer' && gesture.id) {
      const dx = point.x - gesture.start.x, dy = point.y - gesture.start.y;
      if (!gesture.moved && Math.hypot(dx, dy) * screen().scale < 4) return;
      if (!gesture.recorded) { undo.push(state); trim(); redo.length = 0; gesture.recorded = true; }
      gesture.moved = true;
      gesture.start = point;
      state = {...state, layers: state.layers.map((layer) => layer.id === gesture.id ? {...layer, center: {x: layer.center.x + dx, y: layer.center.y + dy}} : layer)};
      requestDraw();
    }
  });
  const release = (event) => {
    if (!pointers.has(event.pointerId)) return;
    pointers.delete(event.pointerId);
    if (!gesture || pointers.size) return;
    const finished = gesture;
    gesture = null;
    if (finished.kind === 'pinch') { changed(); return; }
    if (finished.kind === 'draw' && drawing) {
      const points = drawing;
      drawing = null;
      if (tool === 'Background') {
        const cut = state.cutout || {mask: null, source: null, touches: [], taps: [], removed: 0};
        commit({...state, cutout: {...cut, touches: [...cut.touches, cutTouch(points)]}});
      } else commit({...state, strokes: [...state.strokes, {points, color: ink, width: thickness * unit(), brush}]});
    } else if (finished.kind === 'tap' && event.type === 'pointerup') {
      // A tap on background still showing removes it too (picture coordinates to photo pixels).
      const x = Math.floor((finished.start.x - state.padSides) / state.stretchX), y = Math.floor((finished.start.y - state.padTop) / state.stretchY);
      if (x >= 0 && y >= 0 && x < state.base.width && y < state.base.height) runCut({taps: [...(state.cutout?.taps || []), [x, y]].slice(-40)});
    } else if (finished.kind === 'layer') {
      if (finished.moved) changed();
      else if (!finished.id && tool === 'Text' && event.type === 'pointerup') addText('Text', finished.start, {focus: true});
      else if (!finished.id) { selected = null; drawPanel(); requestDraw(); }
    } else if (finished.kind === 'crop') drawPanel();
  };
  canvas.addEventListener('pointerup', release);
  canvas.addEventListener('pointercancel', release);
  canvas.addEventListener('dblclick', (event) => {
    const point = toPicture(event);
    if (tool === 'Crop') { view = view.zoom > 1.01 ? {zoom: 1, panX: 0, panY: 0} : {...view, zoom: 2}; requestDraw(); drawPanel(); return; }
    const hit = [...state.layers].reverse().find((layer) => layerHit(layer, point));
    if (hit?.type === 'text') { selected = hit.id; drawPanel(); panel.querySelector('textarea')?.select(); }
  });
  canvas.addEventListener('wheel', (event) => {
    // The wheel zooms crop; over a layer it resizes it, and with Shift it turns it.
    if (tool === 'Crop') {
      event.preventDefault();
      view = {...view, zoom: Math.min(6, Math.max(1, view.zoom * (event.deltaY < 0 ? 1.1 : 1 / 1.1)))};
      if (view.zoom === 1) view = {zoom: 1, panX: 0, panY: 0};
      requestDraw();
      drawPanel();
      return;
    }
    const chosen = state.layers.find((layer) => layer.id === selected);
    if (!chosen || !layerHit(chosen, toPicture(event))) return;
    event.preventDefault();
    const change = event.shiftKey ? {rotation: chosen.rotation + (event.deltaY < 0 ? -5 : 5)}
      : {scale: Math.min(12, Math.max(.1, chosen.scale * (event.deltaY < 0 ? 1.08 : 1 / 1.08)))};
    slide({...state, layers: state.layers.map((layer) => layer.id === chosen.id ? {...layer, ...change} : layer)});
    clearTimeout(canvas.wheelTimer);
    canvas.wheelTimer = setTimeout(endSlide, 400);
  }, {passive: false});
  canvas.addEventListener('keydown', (event) => {
    const chosen = state.layers.find((layer) => layer.id === selected);
    if (!chosen) return;
    if (event.key === 'Delete' || event.key === 'Backspace') { event.preventDefault(); commit({...state, layers: state.layers.filter((layer) => layer !== chosen)}); selected = null; }
    const nudge = {ArrowLeft: [-1, 0], ArrowRight: [1, 0], ArrowUp: [0, -1], ArrowDown: [0, 1]}[event.key];
    if (nudge) {
      event.preventDefault();
      const step = 10 * unit() * (event.shiftKey ? 5 : 1);
      commit({...state, layers: state.layers.map((layer) => layer === chosen ? {...layer, center: {x: layer.center.x + nudge[0] * step, y: layer.center.y + nudge[1] * step}} : layer)});
    }
  });

  // Layers

  function addText(text, at, {font = 'Bold', look = 'Outline', color = '#ffffff', focus = false} = {}) {
    if (state.layers.length >= 60) return;
    const layer = {type: 'text', id: nextId++, text, font, look, color, size: 72 * Math.max(.4, unit()), center: at, scale: 1, rotation: 0};
    commit({...state, layers: [...state.layers, layer]});
    selected = layer.id;
    drawPanel();
    if (focus) panel.querySelector('textarea')?.select();
  }

  async function addPicture(source, share) {
    const picture = source instanceof HTMLCanvasElement ? source : await loadPicture(source, 1024);
    const fit = Math.min(pictureWidth(state), pictureHeight(state)) * share / Math.max(picture.width, picture.height);
    const layer = {type: 'picture', id: nextId++, picture, center: {x: pictureWidth(state) / 2, y: pictureHeight(state) / 2}, scale: fit, rotation: 0};
    if (state.layers.length < 60) commit({...state, layers: [...state.layers, layer]});
    selected = layer.id;
    drawPanel();
  }

  const updateLayer = (id, change, live = false) => {
    const next = {...state, layers: state.layers.map((layer) => (layer.id === id ? {...layer, ...change} : layer))};
    if (live) slide(next); else commit(next);
  };

  // Background removal, in this page. A newer request replaces one still running.

  async function runCut(extra = {}) {
    const token = ++cutToken;
    const base = state.base;
    const params = {tolerance: strength, softness, fromEdges: true, taps: state.cutout?.taps || [], ...extra};
    cutBusy = 0;
    cutNote = '';
    drawPanel();
    try {
      const analysis = scaledCanvas(base, 1024);
      const small = analysis.getContext('2d').getImageData(0, 0, analysis.width, analysis.height);
      const found = await findBackground(small.data, analysis.width, analysis.height, params, (value) => {
        cutBusy = value * .85;
        const bar = panel.querySelector('progress');
        if (bar) bar.value = cutBusy;
      }, () => token !== cutToken || !sheet.isConnected);
      const mask = canvasOf(found.width, found.height);
      const maskImage = mask.getContext('2d').createImageData(found.width, found.height);
      for (let i = 0; i < found.alpha.length; i++) maskImage.data[i * 4 + 3] = found.alpha[i];
      mask.getContext('2d').putImageData(maskImage, 0, 0);
      await pause();
      if (token !== cutToken) return;
      const source = canvasOf(base.width, base.height);
      const sourceContext = source.getContext('2d');
      sourceContext.drawImage(base, 0, 0);
      const full = sourceContext.getImageData(0, 0, base.width, base.height);
      cutDecontaminate(full.data, base.width, base.height, found);
      sourceContext.putImageData(full, 0, 0);
      cutBusy = null;
      if (state.base !== base) { drawPanel(); return; }
      // Erase and Keep strokes made meanwhile stay.
      commit({...state, cutout: {mask, source, touches: state.cutout?.touches || [], taps: params.taps, removed: found.removed}});
      cutNote = found.removed < .01 ? 'No clear background found. Tap the background, or use Erase.'
        : found.removed > .97 ? 'That took almost everything. Lower Strength, or use Keep to bring parts back.' : '';
      drawPanel();
    } catch (error) {
      if (token !== cutToken) return;
      cutBusy = null;
      cutNote = error instanceof CutStopped ? '' : 'This picture’s background couldn’t be removed here. Try cropping it first.';
      drawPanel();
    }
  }

  // The controls for the tool in use

  const chip = (label, on, run, extra = '') => {
    const button = element('button', label, 'editor-chip' + (on ? ' on' : '') + (extra ? ' ' + extra : ''));
    button.type = 'button';
    button.setAttribute('aria-pressed', String(!!on));
    button.addEventListener('click', run);
    return button;
  };
  const chipRow = (...items) => { const row = element('div', '', 'editor-chips'); row.append(...items.filter(Boolean)); return row; };
  const hint = (text) => element('p', text, 'editor-hint');
  const slider = (label, value, min, max, step, input, after = endSlide) => {
    const range = Object.assign(element('input', ''), {type: 'range', min, max, step, value});
    const wrap = element('label', '', 'editor-slider');
    wrap.append(element('span', label), range);
    range.addEventListener('input', () => input(Number(range.value)));
    range.addEventListener('change', after);
    return wrap;
  };
  const swatches = (current, pick) => {
    const row = element('div', '', 'editor-swatches');
    for (const color of EDIT_SWATCHES) {
      const button = element('button', '', 'editor-swatch' + (color === current ? ' on' : ''));
      button.type = 'button';
      button.style.background = color;
      button.setAttribute('aria-label', 'Color ' + color);
      button.setAttribute('aria-pressed', String(color === current));
      button.addEventListener('click', () => pick(color));
      row.append(button);
    }
    const own = Object.assign(element('input', ''), {type: 'color', value: current, className: 'editor-color'});
    own.setAttribute('aria-label', 'Any color');
    own.addEventListener('change', () => pick(own.value));
    row.append(own);
    return row;
  };

  function drawPanel() {
    panel.replaceChildren();
    const chosen = state.layers.find((layer) => layer.id === selected);
    if (tool === 'Crop') {
      panel.append(chipRow(...RATIOS.map(([label, ratio]) => chip(label, cropRatio === ratio, () => {
        cropRatio = ratio;
        crop = ratio ? centeredCrop(pictureWidth(state), pictureHeight(state), ratio) : null;
        drawPanel();
        requestDraw();
      }))));
      const zoom = slider('Zoom', view.zoom, 1, 6, .05, (value) => { view = value <= 1.001 ? {zoom: 1, panX: 0, panY: 0} : {...view, zoom: value}; requestDraw(); }, () => drawPanel());
      panel.append(zoom, hint(pictureWidth(state) + ' × ' + pictureHeight(state) + ' px' + (view.zoom > 1.01 ? ' · ' + view.zoom.toFixed(1) + '×' : '')
        + '. Drag the corners or edges to resize the box, or inside it to move it. Zoom with the wheel, a pinch, or a double-click; drag outside the box to look around.'));
      const apply = chip('Crop', false, () => {
        const box = crop;
        if (!box) return;
        const whole = flattenEdit(state);
        const sx = whole.width / pictureWidth(state), sy = whole.height / pictureHeight(state);
        const out = canvasOf((box.r - box.l) * sx, (box.b - box.t) * sy);
        out.getContext('2d').drawImage(whole, box.l * sx, box.t * sy, (box.r - box.l) * sx, (box.b - box.t) * sy, 0, 0, out.width, out.height);
        out.hasAlpha = whole.hasAlpha;
        replaceBase(out);
      }, 'primary');
      apply.disabled = !crop;
      panel.append(chipRow(chip('⟳ Rotate', false, () => replaceBase(transformed(flattenEdit(state), true))),
        chip('⇋ Flip', false, () => replaceBase(transformed(flattenEdit(state), false))), chip('Resize…', false, drawResize), apply));
    } else if (tool === 'Adjust') {
      for (const [key, label, min] of ADJUSTMENTS) {
        panel.append(slider(label, state.adjust[key], min, 1, .01, (value) => slide({...state, adjust: {...state.adjust, [key]: value}})));
      }
      const reset = chip('Reset adjustments', false, () => commit({...state, adjust: {...NO_ADJUST}}));
      reset.disabled = Object.values(state.adjust).every((value) => value === 0);
      panel.append(chipRow(reset));
    } else if (tool === 'Filters') {
      const row = element('div', '', 'editor-filters');
      const thumb = scaledCanvas(state.base, 96);
      for (const [name] of EDIT_FILTERS) {
        const button = element('button', '', 'editor-filter' + (state.filter === name ? ' on' : ''));
        button.type = 'button';
        button.setAttribute('aria-pressed', String(state.filter === name));
        const picture = canvasOf(thumb.width, thumb.height);
        const context = picture.getContext('2d');
        context.drawImage(thumb, 0, 0);
        const matrix = editMatrix(name, NO_ADJUST);
        if (matrix) { const image = context.getImageData(0, 0, picture.width, picture.height); applyMatrix(image.data, matrix); context.putImageData(image, 0, 0); }
        button.append(picture, element('span', name));
        button.addEventListener('click', () => commit({...state, filter: name}));
        row.append(button);
      }
      panel.append(row, hint('Adjustments apply on top of the filter.'));
    } else if (tool === 'Background') {
      const cut = state.cutout;
      if (cutBusy !== null) {
        const row = element('div', '', 'editor-busy');
        const bar = Object.assign(element('progress', ''), {max: 1, value: cutBusy});
        bar.setAttribute('aria-label', 'Removing the background');
        row.append(element('span', 'Removing the background…'), bar, chip('Stop', false, () => { cutToken++; cutBusy = null; drawPanel(); }));
        panel.append(row);
      } else if (!cut?.mask) {
        panel.append(chipRow(chip('Remove background', false, () => runCut(), 'primary')),
          hint('Works best when the background is plain and runs along the picture’s edges. It all happens in this browser.'));
      }
      panel.append(chipRow(...['Tap to remove', 'Erase', 'Keep'].map((mode) => chip(mode, cutMode === mode, () => { cutMode = mode; drawPanel(); })),
        cut ? chip('Bring back all', false, () => { cutToken++; cutBusy = null; cutNote = ''; commit({...state, cutout: null}); }) : null));
      panel.append(hint({'Tap to remove': 'Tap any background still showing, like a gap between arms, to remove it too.', Erase: 'Brush over anything else to remove.',
        Keep: 'Brush over parts that should stay, to bring them back.'}[cutMode]));
      if (cutMode === 'Tap to remove') {
        panel.append(slider('Strength', strength, 0, 1, .01, (value) => { strength = value; }, () => { if (state.cutout?.mask) runCut(); }),
          slider('Soft edges', softness, 0, 1, .01, (value) => { softness = value; }, () => { if (state.cutout?.mask) runCut(); }));
      } else panel.append(slider('Brush size', thickness, 3, 60, 1, (value) => { thickness = value; }, () => {}));
      if (cutNote) panel.append(element('p', cutNote, 'editor-note'));
    } else if (tool === 'Draw') {
      panel.append(chipRow(...EDIT_BRUSHES.map((name) => chip(name, brush === name, () => { brush = name; drawPanel(); }))));
      if (brush !== 'Eraser') panel.append(swatches(ink, (color) => { ink = color; drawPanel(); }));
      panel.append(slider('Size', thickness, 3, 60, 1, (value) => { thickness = value; }, () => {}), hint('Draw on the picture with your mouse, pen, or finger.'));
    } else if (tool === 'Text') {
      if (chosen?.type === 'text') {
        const words = Object.assign(element('textarea', ''), {rows: 2, maxLength: 200, value: chosen.text});
        words.setAttribute('aria-label', 'Words');
        words.addEventListener('input', () => { if (words.value.trim()) updateLayer(chosen.id, {text: words.value}, true); });
        words.addEventListener('change', endSlide);
        panel.append(words);
        const fonts = chipRow(...Object.keys(EDIT_FONTS).map((font) => {
          const button = chip(font, chosen.font === font, () => updateLayer(chosen.id, {font}));
          const [variant, weight, family] = EDIT_FONTS[font];
          button.style.fontFamily = family;
          button.style.fontWeight = weight;
          if (variant) button.style.fontVariant = variant;
          return button;
        }));
        panel.append(fonts, chipRow(...TEXT_LOOKS.map((look) => chip(look, chosen.look === look, () => updateLayer(chosen.id, {look}))),
          chip('Delete', false, () => { commit({...state, layers: state.layers.filter((layer) => layer.id !== chosen.id)}); selected = null; drawPanel(); }, 'danger-chip')),
        swatches(chosen.color, (color) => updateLayer(chosen.id, {color})), layerSliders(chosen), hint('Drag to move. Pinch, the sliders, or the wheel resize it; Shift with the wheel turns it.'));
      } else {
        const middle = {x: pictureWidth(state) / 2, y: pictureHeight(state) / 2};
        panel.append(hint('Click the picture where you want words, or start from a layout.'), chipRow(
          chip('Add text', false, () => addText('Text', middle, {font: 'Classic', focus: true})),
          chip('Top and bottom', false, () => {
            addText('Top text', {x: middle.x, y: pictureHeight(state) * .1});
            addText('Bottom text', {x: middle.x, y: pictureHeight(state) * .9});
          }),
          chip('Title bar above', false, () => {
            const space = Math.round(pictureHeight(state) * .22);
            const shift = (point) => ({x: point.x, y: point.y + space});
            commit({...state, padTop: state.padTop + space, padColor: '#ffffff', layers: state.layers.map((layer) => ({...layer, center: shift(layer.center)})),
              strokes: state.strokes.map((stroke) => ({...stroke, points: stroke.points.map(shift)}))});
            addText('Title', {x: pictureWidth(state) / 2, y: space / 2}, {font: 'Classic', look: 'Plain', color: '#000000'});
          }),
          chip('Caption bar below', false, () => {
            const space = Math.round(pictureHeight(state) * .22);
            commit({...state, padBottom: state.padBottom + space, padColor: '#ffffff'});
            addText('Caption', {x: pictureWidth(state) / 2, y: pictureHeight(state) - space / 2}, {font: 'Classic', look: 'Plain', color: '#000000'});
          }),
          chip('Label', false, () => addText('Label', middle, {font: 'Classic', look: 'Box'}))));
      }
    } else if (tool === 'Images') {
      const pick = Object.assign(element('input', ''), {type: 'file', accept: 'image/*', hidden: true});
      pick.addEventListener('change', () => { if (pick.files[0]) addPicture(pick.files[0], .4).catch(showError); });
      const row = chipRow(chip('Add a photo', false, () => pick.click()));
      panel.append(pick, row);
      if (typeof listStickers === 'function') {
        listStickers().then((items) => {
          for (const item of items.slice(0, 12)) {
            const button = element('button', '', 'editor-sticker');
            button.type = 'button';
            button.setAttribute('aria-label', 'Add this sticker');
            button.append(Object.assign(element('img', ''), {src: URL.createObjectURL(item.blob), alt: ''}));
            button.addEventListener('click', () => addPicture(item.blob, .3).catch(showError));
            row.append(button);
          }
        }).catch(() => {});
      }
      if (chosen?.type === 'picture') {
        panel.append(layerSliders(chosen), chipRow(chip('Delete', false, () => { commit({...state, layers: state.layers.filter((layer) => layer.id !== chosen.id)}); selected = null; drawPanel(); },
          'danger-chip')), hint('Drag to move. Pinch, the sliders, or the wheel resize it; Shift with the wheel turns it.'));
      } else panel.append(hint('Add another photo or one of your stickers, then move, resize, and turn it.'));
    } else if (tool === 'Size') {
      const most = Math.max(imageWidth(state), imageHeight(state));
      panel.append(slider('Stretch wide', state.stretchX, .4, 2.5, .01, (value) => slide({...state, stretchX: value})),
        slider('Stretch tall', state.stretchY, .4, 2.5, .01, (value) => slide({...state, stretchY: value})),
        slider('Space on top', state.padTop / most, 0, 1, .01, (value) => slide({...state, padTop: Math.round(value * most)})),
        slider('Space below', state.padBottom / most, 0, 1, .01, (value) => slide({...state, padBottom: Math.round(value * most)})),
        slider('Space on the sides', state.padSides / most, 0, 1, .01, (value) => slide({...state, padSides: Math.round(value * most)})),
        swatches(state.padColor, (color) => commit({...state, padColor: color})),
        chipRow(chip('Reset size', false, () => commit({...state, stretchX: 1, stretchY: 1, padTop: 0, padBottom: 0, padSides: 0}))));
    }
  }

  function layerSliders(layer) {
    const box = element('div', '', 'editor-layer');
    box.append(slider('Size', Math.log2(layer.scale), -3.3, 3.6, .01, (value) => updateLayer(layer.id, {scale: 2 ** value}, true)),
      slider('Turn', ((layer.rotation % 360) + 540) % 360 - 180, -180, 180, 1, (value) => updateLayer(layer.id, {rotation: value}, true)));
    return box;
  }

  /** Resize: a new size in pixels, keeping the shape unless you turn that off. Shown in the panel, not a pop-up. */
  function drawResize() {
    const width = pictureWidth(state), height = pictureHeight(state);
    panel.replaceChildren();
    const w = Object.assign(element('input', ''), {type: 'number', min: 1, max: 4096, value: width});
    const h = Object.assign(element('input', ''), {type: 'number', min: 1, max: 4096, value: height});
    const lock = Object.assign(element('input', ''), {type: 'checkbox', checked: true});
    w.addEventListener('input', () => { if (lock.checked && +w.value > 0) h.value = Math.max(1, Math.round(+w.value * height / width)); });
    h.addEventListener('input', () => { if (lock.checked && +h.value > 0) w.value = Math.max(1, Math.round(+h.value * width / height)); });
    const sizes = element('div', '', 'editor-size');
    const wl = element('label', 'Width'); wl.append(w);
    const hl = element('label', 'Height'); hl.append(h);
    const ll = element('label', '', 'check'); ll.append(lock, element('span', 'Keep the shape'));
    sizes.append(wl, element('span', '×'), hl, ll);
    const preset = (label, longest) => chip(label, false, () => {
      const scale = longest / Math.max(width, height);
      w.value = Math.max(1, Math.round(width * scale));
      h.value = Math.max(1, Math.round(height * scale));
    });
    const apply = chip('Resize', false, () => {
      const scale = Math.min(1, 4096 / Math.max(+w.value, +h.value), Math.sqrt(16000000 / (+w.value * +h.value)));
      const tw = Math.max(1, Math.round(+w.value * scale)), th = Math.max(1, Math.round(+h.value * scale));
      const out = canvasOf(tw, th);
      const context = out.getContext('2d');
      context.imageSmoothingQuality = 'high';
      const whole = flattenEdit(state);
      context.drawImage(whole, 0, 0, tw, th);
      out.hasAlpha = whole.hasAlpha;
      replaceBase(out);
    }, 'primary');
    panel.append(hint('Now ' + width + ' × ' + height + ' pixels. Pictures can be up to 4096 pixels on a side.'), sizes,
      chipRow(preset('Large · 2048', 2048), preset('Medium · 1280', 1280), preset('Small · 640', 640)),
      chipRow(chip('Cancel', false, () => drawPanel()), apply));
    w.focus();
  }

  for (const name of ['Crop', 'Adjust', 'Filters', 'Background', 'Draw', 'Text', 'Images', 'Size']) {
    const tab = element('button', name, 'editor-tab');
    tab.type = 'button';
    tab.setAttribute('role', 'tab');
    tab.addEventListener('click', () => {
      tool = name;
      if (name !== 'Crop') { crop = null; view = {zoom: 1, panX: 0, panY: 0}; }
      for (const other of tabs.children) other.setAttribute('aria-selected', String(other === tab));
      drawPanel();
      requestDraw();
    });
    tab.setAttribute('aria-selected', String(name === tool));
    tabs.append(tab);
  }

  // Done puts the picture in the message box; Send sends it; Save sticker keeps the cut-out.
  const finish = async (now) => {
    sendButton.disabled = doneButton.disabled = true;
    try {
      selected = null;
      let picture = flattenEdit(state);
      if (sticker) picture = trimmedSticker(picture);
      const alpha = sticker || (picture.hasAlpha && canvasHasAlpha(picture));
      const out = await new Promise((resolve) => picture.toBlob(resolve, alpha ? 'image/png' : 'image/jpeg', .92));
      if (!out) throw new Error('The edited picture couldn’t be made. Try a smaller one.');
      sheet.close();
      done(out, alpha ? 'Edited photo.png' : 'Edited photo.jpg', now);
    } catch (error) { showError(error); sendButton.disabled = doneButton.disabled = false; }
  };
  sendButton.addEventListener('click', () => finish(!sticker));
  doneButton.addEventListener('click', () => finish(false));
  sheet.addEventListener('close', () => { cutToken++; observer.disconnect(); });
  const observer = new ResizeObserver(requestDraw);
  observer.observe(stage);
  sheet.showModal();
  updateHistory();
  drawPanel();
  requestDraw();
  if (sticker) runCut();
}

/** Edit in the full-screen viewer, for photos in internet chats (cards.js openViewer). */
function viewerEditButton(src, closeViewer) {
  const blob = blobFiles.get(src);
  // Received files come as application/octet-stream; the editor reads the picture itself.
  if (!blob || !/^(image\/|application\/octet-stream$)/.test(blob.type || 'image/') || !active || !internet(threadProtocol(active))) return null;
  const button = element('button', 'Edit', 'viewer-button');
  button.type = 'button';
  button.addEventListener('click', (event) => {
    event.stopPropagation();
    closeViewer();
    openPhotoEditor(blob, {done: (edited, name, now) => (now ? sendEdited(edited, name).catch(showError) : stageAttachment(edited, name))});
  });
  return button;
}
