// Pure text helpers shared by the browser app and the Node tests.

const HANGUL = /[ᄀ-ᇿ㄰-㆏가-힯]/;
const LATIN_WORD = /[A-Za-z]+(?:'[A-Za-z]+)?/g;

export function hasHangul(text) {
  return HANGUL.test(text || "");
}

export function countEnglishWords(text) {
  return ((text || "").match(LATIN_WORD) || []).length;
}

/** Collapse whitespace and cap length so a runaway transcript can't flood the API. */
export function cleanTranscript(text, max = 300) {
  return (text || "").replace(/\s+/g, " ").trim().slice(0, max);
}

const FILLERS = new Set(["uh", "um", "hmm", "mm", "ah", "oh", "er", "eh", "huh"]);

/**
 * Decide whether a final speech result should be ignored.
 * Background TV / family chatter and the buddy's own voice echo are the main
 * false triggers on a tablet with no push-to-talk button.
 */
export function isLikelyNoise(text, confidence) {
  const t = cleanTranscript(text).toLowerCase();
  if (!t) return true;
  // Some engines report 0 when confidence is unknown; only trust real low scores.
  if (typeof confidence === "number" && confidence > 0 && confidence < 0.3) return true;
  const words = t.split(" ").filter(Boolean);
  if (words.every((w) => FILLERS.has(w.replace(/[^a-z]/g, "")))) return true;
  return false;
}

/** True when the child's sentence echoes what the buddy just said (speaker -> mic loop). */
export function isEcho(heard, lastSpoken) {
  const norm = (s) => (s || "").toLowerCase().replace(/[^a-z0-9 ]/g, "").replace(/\s+/g, " ").trim();
  const h = norm(heard);
  const s = norm(lastSpoken);
  if (!h || !s || h.split(" ").length < 3) return false;
  return s.includes(h);
}
