// Pictures stay on the bridge until a phone asks. Every update sent to a phone carries a reference where the log has
// image bytes ({type:'image', mimeType, ref, size, width?, height?}); bridge/media serves the bytes in slices, so no
// frame is so large that the pings queued behind it time the connection out.
import crypto from 'node:crypto';

/**
 * Most characters of base64 one bridge/media result carries. A phone downloads two images at once, and a ping waits
 * behind both chunks: this size keeps that under the 15 s ping timeout down to about 15 KB/s.
 */
export const MEDIA_CHUNK = 128 * 1024;

/** JPEG: the size from the first frame header, swapped when EXIF orientation turns the picture on its side. */
function jpegSize(b) {
  let orientation = 1;
  for (let i = 2; i + 4 <= b.length;) {
    if (b[i] !== 0xff) return null;
    const marker = b[i + 1];
    if (marker === 0xff) { i++; continue; }                                            // fill byte
    if (marker === 0x01 || (marker >= 0xd0 && marker <= 0xd8)) { i += 2; continue; }   // no length
    if (marker === 0xd9 || marker === 0xda) return null;                               // image data before any frame
    const len = b.readUInt16BE(i + 2);
    if (len < 2) return null;
    // C0-CF are frame headers, except DHT (C4), JPG (C8) and DAC (CC)
    if (marker >= 0xc0 && marker <= 0xcf && marker !== 0xc4 && marker !== 0xc8 && marker !== 0xcc) {
      if (i + 9 > b.length) return null;
      const height = b.readUInt16BE(i + 5), width = b.readUInt16BE(i + 7);
      return orientation >= 5 ? { width: height, height: width } : { width, height };
    }
    if (marker === 0xe1) orientation = exifOrientation(b.subarray(i + 4, i + 2 + len)) ?? orientation;
    i += 2 + len;   // a segment is skipped whole, so an EXIF thumbnail's own frame header is never read
  }
  return null;
}

/** Orientation (1..8) from an APP1 "Exif" payload's first directory, or null. */
function exifOrientation(seg) {
  if (seg.length < 14 || seg.toString('latin1', 0, 6) !== 'Exif\0\0') return null;
  const t = seg.subarray(6);
  const order = t.toString('latin1', 0, 2);
  if (order !== 'II' && order !== 'MM') return null;
  const u16 = o => (order === 'II' ? t.readUInt16LE(o) : t.readUInt16BE(o));
  const u32 = o => (order === 'II' ? t.readUInt32LE(o) : t.readUInt32BE(o));
  const dir = u32(4);
  if (dir + 2 > t.length) return null;
  const n = u16(dir);
  for (let i = 0, o = dir + 2; i < n && o + 12 <= t.length; i++, o += 12) {
    if (u16(o) !== 0x0112) continue;
    const v = u16(o + 8);
    return v >= 1 && v <= 8 ? v : null;
  }
  return null;
}

function webpSize(b) {
  const chunk = b.toString('latin1', 12, 16);
  if (chunk === 'VP8 ' && b.length >= 30 && b[23] === 0x9d && b[24] === 0x01 && b[25] === 0x2a) {
    return { width: b.readUInt16LE(26) & 0x3fff, height: b.readUInt16LE(28) & 0x3fff };
  }
  if (chunk === 'VP8L' && b.length >= 25 && b[20] === 0x2f) {
    const bits = b.readUInt32LE(21);
    return { width: (bits & 0x3fff) + 1, height: ((bits >>> 14) & 0x3fff) + 1 };
  }
  if (chunk === 'VP8X' && b.length >= 30) return { width: b.readUIntLE(24, 3) + 1, height: b.readUIntLE(27, 3) + 1 };
  return null;
}

/** {width, height} in pixels as displayed, from the header of a PNG, JPEG, GIF or WebP; null when unknown. */
export function imageSize(b) {
  let r = null;
  if (b.length >= 24 && b.readUInt32BE(0) === 0x89504e47 && b.toString('latin1', 12, 16) === 'IHDR') r = { width: b.readUInt32BE(16), height: b.readUInt32BE(20) };
  else if (b.length >= 10 && /^GIF8[79]a$/.test(b.toString('latin1', 0, 6))) r = { width: b.readUInt16LE(6), height: b.readUInt16LE(8) };
  else if (b.length >= 16 && b.toString('latin1', 0, 4) === 'RIFF' && b.toString('latin1', 8, 12) === 'WEBP') r = webpSize(b);
  else if (b.length >= 4 && b[0] === 0xff && b[1] === 0xd8) r = jpegSize(b);
  return r?.width > 0 && r.height > 0 ? r : null;
}

/** The reference block for an image's base64; `found` collects what the reference stands for. */
function reference(data, mimeType, keep, found) {
  const ref = 'm_' + crypto.createHash('sha256').update(data).digest('base64url').slice(0, 32);
  const bytes = Buffer.from(data, 'base64');
  let dims = null;
  try { dims = imageSize(bytes); } catch {}   // a broken header only costs the placeholder its size
  const { data: _, width: _w, height: _h, ...rest } = keep;
  const mime = typeof mimeType === 'string' && mimeType ? mimeType : 'application/octet-stream';
  found.push({ ref, mimeType: mime, data });
  return { ...rest, type: 'image', mimeType: mime, ref, size: bytes.length, ...(dims ?? {}) };
}

const filled = s => typeof s === 'string' && s.length > 0;

/** A copy of `v` with every image's bytes replaced by a reference; `v` itself (never changed) when it holds none. */
function strip(v, found) {
  if (!v || typeof v !== 'object') return v;
  if (!Array.isArray(v)) {
    if (v.type === 'image') {
      // ACP (and MCP); one with empty data (a picture given by URL) has no bytes to take out
      if (filled(v.data)) return reference(v.data, v.mimeType, v, found);
      // Anthropic API, as found in rawOutput
      if (v.source?.type === 'base64' && filled(v.source.data)) return reference(v.source.data, v.source.media_type, {}, found);
      // Claude Code's Read result
      if (filled(v.file?.base64)) return reference(v.file.base64, v.file.type, {}, found);
    }
    // an embedded resource that is a picture
    if (v.type === 'resource' && filled(v.resource?.blob) && String(v.resource.mimeType).startsWith('image/')) {
      return reference(v.resource.blob, v.resource.mimeType, v.resource.uri ? { uri: v.resource.uri } : {}, found);
    }
  }
  let out = null;
  for (const key of Object.keys(v)) {
    const next = strip(v[key], found);
    if (next === v[key]) continue;
    out ??= Array.isArray(v) ? v.slice() : { ...v };
    out[key] = next;
  }
  return out ?? v;
}

/**
 * claude-agent-acp's PostToolUse report repeats the whole tool result (a Read's picture or file text, a command's
 * output) in _meta.claudeCode.toolResponse. Phones read none of it; only its small status markers stay.
 */
function withoutToolResponse(update) {
  const cc = update?._meta?.claudeCode;
  if (!cc || cc.toolResponse === undefined) return update;
  const { toolResponse: r, ...rest } = cc;
  const markers = {};
  if (typeof r?.status === 'string') markers.status = r.status;
  if (typeof r?.isAsync === 'boolean') markers.isAsync = r.isAsync;
  if (Object.keys(markers).length) rest.toolResponse = markers;
  return { ...update, _meta: { ...update._meta, claudeCode: rest } };
}

/**
 * What a phone gets in place of a logged update: {update, images: [{ref, mimeType, data}]}. The logged update is
 * returned as is when there is nothing to take out.
 */
export function phoneUpdate(update) {
  const images = [];
  return { update: strip(withoutToolResponse(update), images), images };
}
