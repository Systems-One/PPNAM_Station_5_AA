// Small ports of the .NET primitives HandheldMqttService relies on.
import crypto from 'node:crypto';

export class WorkflowError extends Error {
  constructor(code, message) {
    super(message);
    this.code = code;
  }
}

/** Desktop Stamp(): yyyy-MM-ddTHH:mm:ss.ffffffZ (exactly six fractional digits). */
export function stamp(ms) {
  return new Date(ms).toISOString().replace(/\.(\d{3})Z$/, '.$1000Z');
}

/**
 * System.Text.Json's DateTimeOffset form for SQL-sourced timestamps (load/pack records):
 * round-trip ISO with trailing fractional zeros trimmed and an explicit offset (here +00:00).
 */
export function offsetStamp(ms) {
  if (ms == null) return null;
  return new Date(ms).toISOString().replace(/\.?0*Z$/, '+00:00');
}

const TIMESTAMP = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})\.(\d{6})Z$/;
/** DateTimeOffset.TryParseExact(value, "yyyy-MM-dd'T'HH:mm:ss.ffffff'Z'") -> epoch ms (fractional), or null. */
export function parseStamp(value) {
  const m = TIMESTAMP.exec(value);
  if (!m) return null;
  const [y, mo, d, h, mi, s] = m.slice(1, 7).map(Number);
  const ms = Date.UTC(y, mo - 1, d, h, mi, s);
  const check = new Date(ms);
  if (y < 1 || check.getUTCFullYear() !== y || check.getUTCMonth() !== mo - 1 || check.getUTCDate() !== d ||
      check.getUTCHours() !== h || check.getUTCMinutes() !== mi || check.getUTCSeconds() !== s) return null;
  return ms + Number(m[7]) / 1000;
}

/** char.IsControl: Unicode Cc (U+0000-U+001F, U+007F-U+009F). */
export const hasControl = (s) => /[\u0000-\u001F\u007F-\u009F]/.test(s);
export const isBlank = (s) => s == null || s.trim().length === 0;

/** int.TryParse(value, NumberStyles.None, Invariant): ASCII digits only, within Int32. */
export function parseInt32(value) {
  if (!/^[0-9]+$/.test(value)) return null;
  const n = Number(value);
  return n <= 2147483647 ? n : null;
}

/** Guid.TryParse (D, N, B, P forms; surrounding whitespace allowed) -> lowercase D form, or null. */
export function parseGuid(value) {
  const v = String(value).trim();
  let hex = null;
  let m;
  if ((m = /^([0-9a-fA-F]{8})-([0-9a-fA-F]{4})-([0-9a-fA-F]{4})-([0-9a-fA-F]{4})-([0-9a-fA-F]{12})$/.exec(v))) hex = m.slice(1).join('');
  else if ((m = /^\{([0-9a-fA-F-]{36})\}$/.exec(v)) || (m = /^\(([0-9a-fA-F-]{36})\)$/.exec(v))) return parseGuid(m[1]);
  else if (/^[0-9a-fA-F]{32}$/.test(v)) hex = v;
  if (!hex) return null;
  hex = hex.toLowerCase();
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

export const EMPTY_GUID = '00000000-0000-0000-0000-000000000000';
export const newGuid = () => crypto.randomUUID();
export const newGuidN = () => crypto.randomUUID().replace(/-/g, '');
export const sha256Hex = (text) => crypto.createHash('sha256').update(text, 'utf8').digest('hex').toUpperCase();

/**
 * What JsonDocument.Parse(payload, MaxDepth = 16) + EnumerateObject() would see: the parsed value,
 * the root object's property names including duplicates, or null if the text is not valid JSON
 * or nests deeper than 16.
 */
export function inspectJson(text) {
  let value;
  try { value = JSON.parse(text); } catch { return null; }
  let depth = 0;
  let maxDepth = 0;
  let expectKey = false;
  const keys = [];
  for (let i = 0; i < text.length; i++) {
    const ch = text[i];
    if (ch === '"') {
      let j = i + 1;
      while (text[j] !== '"') j += text[j] === '\\' ? 2 : 1;
      if (depth === 1 && expectKey) { keys.push(JSON.parse(text.slice(i, j + 1))); expectKey = false; }
      i = j;
    } else if (ch === '{' || ch === '[') {
      depth++;
      maxDepth = Math.max(maxDepth, depth);
      if (depth === 1 && ch === '{') expectKey = true;
    } else if (ch === '}' || ch === ']') {
      depth--;
    } else if (ch === ',' && depth === 1) {
      expectKey = true;
    }
  }
  if (maxDepth > 16) return null;
  return { value, keys };
}
