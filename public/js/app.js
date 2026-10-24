import { VoiceLoop, speechSupported } from "./speech.js";
import { cleanTranscript, hasHangul, isLikelyNoise, isEcho, countEnglishWords } from "./lang.js";

const $ = (id) => document.getElementById(id);
const QUIET_MS = 25_000; // nudge the child after this much silence
const MAX_NUDGES = 2; // then go to sleep and free the mic

// ---------- per-device storage (best effort) ----------
const store = {
  get(key, fallback) {
    try {
      const v = localStorage.getItem(key);
      return v ? JSON.parse(v) : fallback;
    } catch {
      return fallback;
    }
  },
  set(key, value) {
    try {
      localStorage.setItem(key, JSON.stringify(value));
    } catch {
      /* private mode etc. */
    }
  },
};

const settings = Object.assign(
  { name: "", theme: "block", rate: 0.85, koVoice: true, camera: true, code: "" },
  store.get("buddy.settings", {}),
);

const today = new Date().toISOString().slice(0, 10);
let report = store.get("buddy.report", null);
if (!report || report.date !== today) report = { date: today, sentences: [], stars: 0, words: [] };

const history = []; // {role: "user"|"assistant", text}
let lastSpoken = "";
let quietTimer = 0;
let nudges = 0;
let busy = false;
let wakeLock = null;

// ---------- UI helpers ----------
const STATUS_TEXT = { idle: "대기", listening: "듣는 중…", thinking: "생각 중…", speaking: "말하는 중", sleeping: "쉬는 중" };

function applySettings() {
  document.body.dataset.theme = settings.theme;
  $("title").textContent = settings.name ? `${settings.name}'s English Buddy` : "English Buddy";
  loop.rate = Number(settings.rate);
}

function setStatus(state) {
  const el = $("status");
  el.dataset.state = state;
  el.textContent = STATUS_TEXT[state] || state;
  const buddy = $("buddy");
  buddy.classList.toggle("talking", state === "speaking");
  buddy.classList.toggle("thinking", state === "thinking");
  buddy.classList.toggle("listening", state === "listening");
  clearTimeout(quietTimer);
  if (state === "listening") quietTimer = setTimeout(onQuiet, QUIET_MS);
}

function addLog(cls, text) {
  const li = document.createElement("li");
  li.className = cls;
  li.textContent = text;
  $("log").append(li);
  li.scrollIntoView({ block: "end", behavior: "smooth" });
}

function bumpMeter(text) {
  const n = Math.min(5, Math.ceil((text || "").length / 6));
  [...$("meter").children].forEach((bar, i) => (bar.style.height = i < n ? `${40 + Math.random() * 60}%` : "20%"));
}

function saveReport() {
  store.set("buddy.report", report);
  $("starCount").textContent = report.stars;
}

// ---------- conversation ----------
const loop = new VoiceLoop({
  onState: setStatus,
  onInterim: (t) => {
    $("live").textContent = t;
    bumpMeter(t);
  },
  onFinal: (t, conf) => onChildSaid(t, conf),
  onError: (code) => {
    setStatus("idle");
    showStart(`마이크를 쓸 수 없어요 (${code}). 브라우저 권한과 HTTPS 접속을 확인해 주세요.`);
  },
});

async function onChildSaid(raw, confidence) {
  const text = cleanTranscript(raw);
  $("live").textContent = "";
  bumpMeter("");
  if (busy) return;
  if (isLikelyNoise(text, confidence) || isEcho(text, lastSpoken)) return loop.listen();
  nudges = 0;
  const korean = hasHangul(text);
  addLog(korean ? "kid ko" : "kid", text);
  report.sentences.push({ t: Date.now(), text, ko: korean });
  saveReport();
  history.push({ role: "user", text });
  await respond();
}

async function onQuiet() {
  if (busy) return;
  if (nudges >= MAX_NUDGES) return sleep();
  nudges += 1;
  history.push({ role: "user", text: "[system] The child has been quiet for a while. Gently invite them with a very easy question." });
  await respond();
}

async function respond() {
  busy = true;
  loop.stopListening();
  setStatus("thinking");
  try {
    const res = await fetch("/api/chat", {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-Access-Code": settings.code || "" },
      body: JSON.stringify({ history, profile: { name: settings.name, theme: settings.theme } }),
    });
    if (res.status === 401) {
      busy = false;
      loop.shutdown();
      showStart("접속 코드가 필요해요. ⚙️ 설정에서 입력해 주세요.");
      return;
    }
    if (!res.ok) throw new Error(String(res.status));
    await present(await res.json());
  } catch (e) {
    console.warn("chat failed", e);
    await loop.speak([{ text: "Oops, my radio is fuzzy. Can you say it again?", lang: "en-US" }]);
  }
  busy = false;
  if (document.visibilityState === "visible") loop.listen();
}

async function present(r) {
  history.push({ role: "assistant", text: r.say });
  lastSpoken = [r.say, r.try_saying].join(" ");
  $("bubble").textContent = r.say;
  addLog("buddy-msg", r.say);

  const pic = $("picture");
  pic.textContent = r.picture || "";
  pic.classList.remove("pop");
  void pic.offsetWidth;
  pic.classList.add("pop");

  $("hint").hidden = !r.ko_hint;
  $("hint").textContent = r.ko_hint ? `💡 ${r.ko_hint}` : "";
  $("tryCard").hidden = !r.try_saying;
  $("tryText").textContent = r.try_saying || "";

  if (r.star) report.stars += 1;
  if (r.new_word_en) {
    addLog("word", `🆕 ${r.new_word_en} = ${r.new_word_ko}`);
    if (!report.words.some((w) => w.en === r.new_word_en)) report.words.push({ en: r.new_word_en, ko: r.new_word_ko });
  }
  saveReport();

  const parts = [{ text: r.say, lang: "en-US" }];
  if (r.ko_hint && settings.koVoice) parts.push({ text: r.ko_hint, lang: "ko-KR" });
  if (r.try_saying) parts.push({ text: `You can say: ${r.try_saying}`, lang: "en-US" });
  await loop.speak(parts);
}

// ---------- session lifecycle ----------
function showStart(msg) {
  $("startMsg").textContent = msg || "";
  $("startOverlay").hidden = false;
}

function sleep() {
  loop.shutdown();
  setStatus("sleeping");
  $("bubble").textContent = "Zzz… Buddy is resting. 다시 하려면 화면을 눌러요!";
  showStart("잠깐 쉬는 중이에요. 다시 하려면 시작을 눌러요.");
}

async function startCamera() {
  const v = $("selfie");
  if (!settings.camera || !navigator.mediaDevices?.getUserMedia) {
    v.hidden = true;
    v.srcObject?.getTracks().forEach((t) => t.stop());
    v.srcObject = null;
    return;
  }
  if (v.srcObject) return;
  try {
    // Video only: the picture stays on this tablet and is never uploaded.
    v.srcObject = await navigator.mediaDevices.getUserMedia({ video: { facingMode: "user" }, audio: false });
    v.hidden = false;
  } catch {
    v.hidden = true;
  }
}

async function start() {
  if (!speechSupported()) {
    showStart("이 브라우저는 음성 인식을 지원하지 않아요. 안드로이드 Chrome에서 열어 주세요.");
    return;
  }
  $("startOverlay").hidden = true;
  nudges = 0;
  try {
    wakeLock = await navigator.wakeLock?.request("screen");
  } catch {
    /* not critical */
  }
  await startCamera();
  history.push({ role: "user", text: history.length ? "[system] The child is back. Welcome them back." : "[system] The call just started. Say hello and ask the child's name if you don't know it." });
  await respond();
}

document.addEventListener("visibilitychange", () => {
  if (document.visibilityState === "hidden") {
    loop.shutdown();
    if ($("startOverlay").hidden) showStart("다시 시작하려면 눌러요.");
  }
});

// ---------- settings & report dialogs ----------
function openSettings() {
  $("setName").value = settings.name;
  $("setTheme").value = settings.theme;
  $("setRate").value = settings.rate;
  $("setKoVoice").checked = settings.koVoice;
  $("setCamera").checked = settings.camera;
  $("setCode").value = settings.code;
  $("settings").showModal();
}

$("settings").addEventListener("close", () => {
  if ($("settings").returnValue !== "save") return;
  settings.name = $("setName").value.replace(/[^A-Za-z ]/g, "").trim();
  settings.theme = $("setTheme").value;
  settings.rate = Number($("setRate").value);
  settings.koVoice = $("setKoVoice").checked;
  settings.camera = $("setCamera").checked;
  settings.code = $("setCode").value;
  store.set("buddy.settings", settings);
  applySettings();
  if ($("startOverlay").hidden) startCamera();
});

function openReport() {
  const en = report.sentences.filter((s) => !s.ko);
  const words = en.reduce((n, s) => n + countEnglishWords(s.text), 0);
  const body = $("reportBody");
  body.replaceChildren();
  const table = document.createElement("table");
  for (const [k, v] of [
    ["날짜", report.date],
    ["영어로 말한 문장", `${en.length}개 (단어 ${words}개)`],
    ["한국어로 말한 문장", `${report.sentences.length - en.length}개`],
    ["모은 별", `⭐ ${report.stars}`],
    ["새로 배운 단어", report.words.map((w) => `${w.en}(${w.ko})`).join(", ") || "-"],
  ]) {
    const tr = table.insertRow();
    tr.insertCell().textContent = k;
    tr.insertCell().textContent = v;
  }
  const list = document.createElement("ol");
  list.className = "sentences";
  for (const s of report.sentences) {
    const li = document.createElement("li");
    li.textContent = `${new Date(s.t).toLocaleTimeString("ko-KR", { hour: "2-digit", minute: "2-digit" })}  ${s.text}`;
    list.append(li);
  }
  body.append(table, list);
  $("report").showModal();
}

$("btnStart").addEventListener("click", start);
$("btnSettings").addEventListener("click", openSettings);
$("btnReport").addEventListener("click", openReport);
$("btnCloseReport").addEventListener("click", () => $("report").close());
$("btnClear").addEventListener("click", () => {
  report = { date: today, sentences: [], stars: 0, words: [] };
  saveReport();
  openReport();
});

applySettings();
saveReport();
if (!speechSupported()) $("startMsg").textContent = "⚠️ 음성 인식 미지원 브라우저예요. 안드로이드 Chrome을 써 주세요.";
if ("serviceWorker" in navigator) navigator.serviceWorker.register("sw.js").catch(() => {});
