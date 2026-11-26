'use strict';
// A small QR code encoder for FluidMessages Web (ISO/IEC 18004): byte mode, error correction level M, versions 1–10
// (up to 213 bytes), with the mask chosen by the standard's penalty rules. Used for the two-step sign-in setup link
// (otpauth://), which authenticator apps scan. Drawn as an SVG made of elements, never from an HTML string.

const QR_ECC_PER_BLOCK = [0, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26];
const QR_BLOCKS = [0, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5];
const QR_MAX_VERSION = 10;

function qrRawModules(version) {
  let result = (16 * version + 128) * version + 64;
  if (version >= 2) {
    const align = Math.floor(version / 7) + 2;
    result -= (25 * align - 10) * align - 55;
    if (version >= 7) result -= 36;
  }
  return result;
}

const qrDataCodewords = (version) => Math.floor(qrRawModules(version) / 8) - QR_ECC_PER_BLOCK[version] * QR_BLOCKS[version];

function qrMultiply(x, y) {
  let z = 0;
  for (let i = 7; i >= 0; i--) {
    z = (z << 1) ^ ((z >>> 7) * 0x11D);
    z ^= ((y >>> i) & 1) * x;
  }
  return z;
}

function qrDivisor(degree) {
  const result = new Array(degree).fill(0);
  result[degree - 1] = 1;
  let root = 1;
  for (let i = 0; i < degree; i++) {
    for (let j = 0; j < result.length; j++) {
      result[j] = qrMultiply(result[j], root);
      if (j + 1 < result.length) result[j] ^= result[j + 1];
    }
    root = qrMultiply(root, 0x02);
  }
  return result;
}

/** Reed-Solomon error correction bytes for [data]. */
function qrRemainder(data, divisor) {
  const result = divisor.map(() => 0);
  for (const byte of data) {
    const factor = byte ^ result.shift();
    result.push(0);
    divisor.forEach((value, index) => { result[index] ^= qrMultiply(value, factor); });
  }
  return result;
}

/** The 15 format bits for level M and [mask]. */
function qrFormatBits(mask) {
  const data = (0 << 3) | mask;
  let rem = data;
  for (let i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
  return ((data << 10) | rem) ^ 0x5412;
}

function qrVersionBits(version) {
  let rem = version;
  for (let i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
  return (version << 12) | rem;
}

function qrAlignment(version) {
  if (version === 1) return [];
  const count = Math.floor(version / 7) + 2;
  const step = Math.ceil((version * 4 + 4) / (count * 2 - 2)) * 2;
  const result = [6];
  for (let position = version * 4 + 10; result.length < count; position -= step) result.splice(1, 0, position);
  return result;
}

/** The data and error correction bytes in the order they're placed. */
function qrCodewords(bytes, version) {
  const capacity = qrDataCodewords(version) * 8;
  const bits = [];
  const put = (value, length) => { for (let i = length - 1; i >= 0; i--) bits.push((value >>> i) & 1); };
  put(4, 4);
  put(bytes.length, version < 10 ? 8 : 16);
  for (const byte of bytes) put(byte, 8);
  put(0, Math.min(4, capacity - bits.length));
  put(0, (8 - bits.length % 8) % 8);
  for (let pad = 0xEC; bits.length < capacity; pad ^= 0xEC ^ 0x11) put(pad, 8);
  const data = [];
  for (let i = 0; i < bits.length; i += 8) data.push(bits.slice(i, i + 8).reduce((value, bit) => (value << 1) | bit, 0));

  const blocks = QR_BLOCKS[version];
  const ecc = QR_ECC_PER_BLOCK[version];
  const raw = Math.floor(qrRawModules(version) / 8);
  const short = blocks - raw % blocks;
  const shortLength = Math.floor(raw / blocks);
  const divisor = qrDivisor(ecc);
  const parts = [];
  for (let i = 0, k = 0; i < blocks; i++) {
    const chunk = data.slice(k, k + shortLength - ecc + (i < short ? 0 : 1));
    k += chunk.length;
    const check = qrRemainder(chunk, divisor);
    if (i < short) chunk.push(0);
    parts.push(chunk.concat(check));
  }
  const result = [];
  for (let i = 0; i < parts[0].length; i++) {
    parts.forEach((block, j) => { if (i !== shortLength - ecc || j >= short) result.push(block[i]); });
  }
  return result;
}

const QR_MASKS = [
  (x, y) => (x + y) % 2 === 0, (x, y) => y % 2 === 0, (x) => x % 3 === 0, (x, y) => (x + y) % 3 === 0,
  (x, y) => (Math.floor(x / 3) + Math.floor(y / 2)) % 2 === 0, (x, y) => (x * y) % 2 + (x * y) % 3 === 0,
  (x, y) => ((x * y) % 2 + (x * y) % 3) % 2 === 0, (x, y) => ((x + y) % 2 + (x * y) % 3) % 2 === 0,
];

/** The standard's penalty score: long runs, 2×2 blocks, finder-like patterns, and too much dark or light. */
function qrPenalty(grid) {
  const size = grid.length;
  let score = 0;
  const lines = [];
  for (let i = 0; i < size; i++) lines.push(grid[i], grid.map((row) => row[i]));
  const finder = [1, 0, 1, 1, 1, 0, 1];
  for (const line of lines) {
    let run = 1;
    for (let i = 1; i <= size; i++) {
      if (i < size && line[i] === line[i - 1]) { run++; continue; }
      if (run >= 5) score += 3 + run - 5;
      run = 1;
    }
    for (let i = 0; i + 7 <= size; i++) {
      if (!finder.every((bit, k) => line[i + k] === !!bit)) continue;
      const light = (from, to) => { for (let k = from; k < to; k++) if (k >= 0 && k < size && line[k]) return false; return true; };
      if (light(i - 4, i) || light(i + 7, i + 11)) score += 40;
    }
  }
  for (let y = 0; y + 1 < size; y++) {
    for (let x = 0; x + 1 < size; x++) {
      const color = grid[y][x];
      if (grid[y][x + 1] === color && grid[y + 1][x] === color && grid[y + 1][x + 1] === color) score += 3;
    }
  }
  const dark = grid.reduce((sum, row) => sum + row.filter(Boolean).length, 0);
  const total = size * size;
  score += (Math.ceil(Math.abs(dark * 20 - total * 10) / total) - 1) * 10;
  return score;
}

/** The QR code for [text] as rows of booleans (true is dark), without the quiet zone. */
function qrMatrix(text) {
  const bytes = Array.from(new TextEncoder().encode(String(text)));
  let version = 1;
  while (version <= QR_MAX_VERSION && bytes.length + (version < 10 ? 2 : 3) > qrDataCodewords(version)) version++;
  if (version > QR_MAX_VERSION) throw new Error('Too long for a QR code');
  const size = version * 4 + 17;
  const grid = Array.from({length: size}, () => new Array(size).fill(false));
  const fixed = Array.from({length: size}, () => new Array(size).fill(false));
  const set = (x, y, dark) => { grid[y][x] = dark; fixed[y][x] = true; };

  for (let i = 0; i < size; i++) { set(6, i, i % 2 === 0); set(i, 6, i % 2 === 0); }
  for (const [cx, cy] of [[3, 3], [size - 4, 3], [3, size - 4]]) {
    for (let dy = -4; dy <= 4; dy++) {
      for (let dx = -4; dx <= 4; dx++) {
        const x = cx + dx; const y = cy + dy;
        const distance = Math.max(Math.abs(dx), Math.abs(dy));
        if (x >= 0 && x < size && y >= 0 && y < size) set(x, y, distance !== 2 && distance !== 4);
      }
    }
  }
  const positions = qrAlignment(version);
  const last = positions.length - 1;
  positions.forEach((ax, i) => positions.forEach((ay, j) => {
    if ((i === 0 && j === 0) || (i === 0 && j === last) || (i === last && j === 0)) return;
    for (let dy = -2; dy <= 2; dy++) for (let dx = -2; dx <= 2; dx++) set(ax + dx, ay + dy, Math.max(Math.abs(dx), Math.abs(dy)) !== 1);
  }));
  const drawFormat = (mask) => {
    const bits = qrFormatBits(mask);
    const bit = (i) => ((bits >>> i) & 1) === 1;
    for (let i = 0; i <= 5; i++) set(8, i, bit(i));
    set(8, 7, bit(6)); set(8, 8, bit(7)); set(7, 8, bit(8));
    for (let i = 9; i < 15; i++) set(14 - i, 8, bit(i));
    for (let i = 0; i < 8; i++) set(size - 1 - i, 8, bit(i));
    for (let i = 8; i < 15; i++) set(8, size - 15 + i, bit(i));
    set(8, size - 8, true);
  };
  drawFormat(0);
  if (version >= 7) {
    const bits = qrVersionBits(version);
    for (let i = 0; i < 18; i++) {
      const dark = ((bits >>> i) & 1) === 1;
      const a = size - 11 + i % 3; const b = Math.floor(i / 3);
      set(a, b, dark); set(b, a, dark);
    }
  }

  const words = qrCodewords(bytes, version);
  let index = 0;
  for (let right = size - 1; right >= 1; right -= 2) {
    if (right === 6) right = 5;
    for (let vertical = 0; vertical < size; vertical++) {
      for (let j = 0; j < 2; j++) {
        const x = right - j;
        const y = ((right + 1) & 2) === 0 ? size - 1 - vertical : vertical;
        if (!fixed[y][x] && index < words.length * 8) {
          grid[y][x] = ((words[index >>> 3] >>> (7 - (index & 7))) & 1) === 1;
          index++;
        }
      }
    }
  }

  const masked = (mask) => {
    const copy = grid.map((row) => row.slice());
    for (let y = 0; y < size; y++) for (let x = 0; x < size; x++) if (!fixed[y][x] && QR_MASKS[mask](x, y)) copy[y][x] = !copy[y][x];
    return copy;
  };
  let best = 0; let bestScore = Infinity;
  for (let mask = 0; mask < 8; mask++) {
    drawFormat(mask);
    const score = qrPenalty(masked(mask));
    if (score < bestScore) { best = mask; bestScore = score; }
  }
  drawFormat(best);
  return masked(best);
}

/** The QR code for [text] as an SVG element, dark on white with the standard quiet zone, labeled [label]. */
function qrSvg(text, label) {
  const matrix = qrMatrix(text);
  const size = matrix.length + 8;
  const ns = 'http://www.w3.org/2000/svg';
  const svg = document.createElementNS(ns, 'svg');
  svg.setAttribute('viewBox', '0 0 ' + size + ' ' + size);
  svg.setAttribute('shape-rendering', 'crispEdges');
  svg.setAttribute('role', 'img');
  svg.setAttribute('aria-label', label || 'QR code');
  svg.classList.add('qr-code');
  const back = document.createElementNS(ns, 'rect');
  back.setAttribute('width', String(size));
  back.setAttribute('height', String(size));
  back.setAttribute('fill', '#ffffff');
  let path = '';
  matrix.forEach((row, y) => row.forEach((dark, x) => { if (dark) path += 'M' + (x + 4) + ' ' + (y + 4) + 'h1v1h-1z'; }));
  const marks = document.createElementNS(ns, 'path');
  marks.setAttribute('d', path);
  marks.setAttribute('fill', '#000000');
  svg.append(back, marks);
  return svg;
}
