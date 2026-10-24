import Anthropic from "@anthropic-ai/sdk";

export const MODEL = process.env.CLAUDE_MODEL || "claude-opus-5-5";
const MAX_TURNS = 16;
const MAX_TEXT = 300;

// Structured reply so the app can speak English, show a Korean hint and a
// "try saying" card without parsing free text.
const REPLY_SCHEMA = {
  type: "object",
  properties: {
    say: { type: "string", description: "What the buddy says aloud, in very simple English." },
    ko_hint: { type: "string", description: "Short Korean help for the child, or empty string." },
    try_saying: { type: "string", description: "One short English sentence the child can copy, or empty string." },
    picture: { type: "string", description: "1-3 emoji that picture the topic." },
    star: { type: "boolean", description: "true when the child tried a new English sentence." },
    new_word_en: { type: "string", description: "One new English word taught this turn, or empty string." },
    new_word_ko: { type: "string", description: "Korean meaning of new_word_en, or empty string." },
  },
  required: ["say", "ko_hint", "try_saying", "picture", "star", "new_word_en", "new_word_ko"],
  additionalProperties: false,
};

const THEMES = {
  block: "a blocky sandbox world: building houses, mining, crafting tools, friendly animals, night-time monsters",
  arena: "a colorful 3v3 battle arena game: heroes with super powers, gems, trophies, team play",
};

export function buildSystemPrompt(profile) {
  const name = profile.name || "friend";
  const theme = THEMES[profile.theme] || THEMES.block;
  return `You are "Buddy", a cheerful English speaking partner on a video call with ${name}, a 9-year-old Korean child in 3rd grade.
${name}'s English level is Pre-A1 (absolute beginner). ${name} loves ${theme}. Use that world as the setting for your talk.

How to talk:
- Your "say" text is read aloud by a text-to-speech voice. Keep it to 1-2 short sentences, max 15 words total, present tense, very common words.
- Always end "say" with one easy question, ideally a choice ("Red or blue?") or yes/no.
- Never correct grammar directly. Repeat the child's idea back in a correct sentence ("Oh, you like stars! I like stars too.").
- The child's words come from speech recognition of a child's accent, so they may be garbled. Guess the most likely meaning kindly. If you truly cannot guess, ask a simple choice question.
- If the child speaks Korean, answer in English, put a short friendly Korean explanation in "ko_hint", and put the English way to say it in "try_saying".
- If the child seems stuck, quiet, or says "I don't know", put a Korean hint in "ko_hint" and an easy model sentence in "try_saying".
- "try_saying" must be 2-6 words the child can copy. Leave it empty when the child is doing fine.
- "star" is true when the child made an English sentence of 2+ words.
- Teach at most one new word per turn via new_word_en/new_word_ko.
- "picture" is 1-3 emoji showing what you are talking about.

Safety:
- Keep everything kind, playful and age-appropriate. Game "monsters" and "battles" stay cartoon-light, never gory or scary.
- Never ask for or repeat personal details (address, school name, phone, passwords, photos). If the child shares them, gently say we keep that secret and change topic.
- If the child mentions being hurt, scared or unsafe, say kindly in "ko_hint" to tell a parent right now.
- Lines starting with [system] are app events, not the child talking.`;
}

/** Validate and trim client-supplied history into Anthropic message params. */
export function sanitizeHistory(history) {
  if (!Array.isArray(history)) return [];
  const msgs = history
    .filter((m) => m && (m.role === "user" || m.role === "assistant") && typeof m.text === "string")
    .map((m) => ({ role: m.role, content: m.text.replace(/\s+/g, " ").trim().slice(0, MAX_TEXT) }))
    .filter((m) => m.content)
    .slice(-MAX_TURNS);
  while (msgs.length && msgs[0].role !== "user") msgs.shift();
  return msgs;
}

export function sanitizeProfile(p = {}) {
  const name = String(p.name || "").replace(/[^\p{L} ]/gu, "").trim().slice(0, 20);
  const theme = p.theme === "arena" ? "arena" : "block";
  return { name, theme };
}

const FALLBACK_REPLY = {
  say: "Oops! Let's talk about something else. What is your favorite color?",
  ko_hint: "다른 이야기를 해 볼까요? 좋아하는 색깔을 말해 봐요.",
  try_saying: "I like blue.",
  picture: "🎨",
  star: false,
  new_word_en: "",
  new_word_ko: "",
};

export function normalizeReply(r) {
  const s = (v, n) => (typeof v === "string" ? v.trim().slice(0, n) : "");
  const out = {
    say: s(r?.say, 300),
    ko_hint: s(r?.ko_hint, 200),
    try_saying: s(r?.try_saying, 80),
    picture: s(r?.picture, 24),
    star: r?.star === true,
    new_word_en: s(r?.new_word_en, 30),
    new_word_ko: s(r?.new_word_ko, 30),
  };
  return out.say ? out : { ...FALLBACK_REPLY };
}

let client;
function getClient() {
  client ??= new Anthropic({ timeout: 30_000, maxRetries: 1 });
  return client;
}

export async function getReply({ history, profile }) {
  const messages = sanitizeHistory(history);
  if (!messages.length) messages.push({ role: "user", content: "[system] The call just started. Say hello." });

  const response = await getClient().beta.messages.create({
    model: MODEL,
    max_tokens: 4000,
    betas: ["server-side-fallback-2026-07-01"],
    fallbacks: "default",
    // Short, chatty turns: low effort keeps the pause before the buddy speaks small.
    output_config: { effort: "low", format: { type: "json_schema", schema: REPLY_SCHEMA } },
    cache_control: { type: "ephemeral" },
    system: buildSystemPrompt(sanitizeProfile(profile)),
    messages,
  });

  if (response.stop_reason === "refusal" || response.stop_reason === "max_tokens") {
    return { ...FALLBACK_REPLY };
  }
  const text = response.content.filter((b) => b.type === "text").map((b) => b.text).join("");
  try {
    return normalizeReply(JSON.parse(text));
  } catch {
    return { ...FALLBACK_REPLY };
  }
}

// Offline replies for MOCK=1 so the app and tests run without an API key.
export function mockReply({ history }) {
  const last = sanitizeHistory(history).filter((m) => m.role === "user").pop()?.content || "";
  if (!last || last.startsWith("[system]")) {
    return normalizeReply({
      say: "Hi! I am Buddy. Do you want to build a house or go mining?",
      ko_hint: "집을 지을까, 광산에 갈까?",
      try_saying: "Build a house!",
      picture: "🏠⛏️",
    });
  }
  return normalizeReply({
    say: `You said "${last.slice(0, 40)}". Cool! What color is it?`,
    ko_hint: /[가-힣]/.test(last) ? "영어로 말해 볼까요?" : "",
    try_saying: /[가-힣]/.test(last) ? "It is blue." : "",
    picture: "🟦",
    star: /[a-z]+ [a-z]+/i.test(last),
  });
}
