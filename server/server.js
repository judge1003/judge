// 미니PC 통합관리 시스템 자체 API 서버
// - 파일 스테이션: 목록/다운로드/업로드/폴더 생성/삭제
// - 포토 스테이션: 사진 타임라인/썸네일/원본
// - 자동 백업: 폰 사진 수신 (연/월 폴더로 정리, 중복 스킵)
//
// 실행: API_TOKEN=비밀토큰 node server.js  (또는 docker compose up -d)

const express = require("express");
const fs = require("fs");
const fsp = require("fs/promises");
const path = require("path");
const crypto = require("crypto");
const sharp = require("sharp");

const PORT = parseInt(process.env.PORT || "8585", 10);
const TOKEN = process.env.API_TOKEN || "";
const FILES_ROOT = path.resolve(process.env.FILES_ROOT || "/data/files");
const PHOTOS_ROOT = path.resolve(process.env.PHOTOS_ROOT || "/data/photos");
const THUMBS_ROOT = path.resolve(process.env.THUMBS_ROOT || "/data/thumbs");

// AI 사진 태깅 (선택): Gemini API 키가 있으면 사진에 한국어 검색 태그를 자동 부여
const GEMINI_KEY = process.env.GEMINI_API_KEY || "";
const GEMINI_MODEL = process.env.GEMINI_MODEL || "gemini-2.5-flash";
const AI_BATCH = parseInt(process.env.AI_BATCH || "30", 10); // 주기당 분석 장수 (무료 한도 보호)
const TAGS_FILE = path.join(THUMBS_ROOT, "photo-tags.json");

if (!TOKEN) {
  console.error("API_TOKEN 환경변수를 설정해야 합니다 (앱과 서버가 공유하는 비밀값)");
  process.exit(1);
}

for (const dir of [FILES_ROOT, PHOTOS_ROOT, THUMBS_ROOT]) {
  fs.mkdirSync(dir, { recursive: true });
}

const IMAGE_EXTS = new Set([".jpg", ".jpeg", ".png", ".gif", ".webp", ".heic", ".bmp"]);

const app = express();

// ---- 인증: 헤더 또는 쿼리의 토큰 검사 ----
app.use((req, res, next) => {
  const t = req.header("x-auth-token") || req.query.token;
  if (t !== TOKEN) return res.status(401).json({ error: "unauthorized" });
  next();
});

// ---- 경로 탈출 방지 ----
function safePath(root, p) {
  const rel = (p || "").toString().replace(/\\/g, "/").replace(/^\/+/, "");
  const full = path.resolve(root, rel);
  if (full !== root && !full.startsWith(root + path.sep)) {
    const err = new Error("invalid path");
    err.status = 400;
    throw err;
  }
  return full;
}

app.get("/api/ping", (req, res) => res.json({ ok: true }));

// ============ 파일 스테이션 ============

app.get("/api/files", async (req, res, next) => {
  try {
    const dir = safePath(FILES_ROOT, req.query.path);
    const names = await fsp.readdir(dir);
    const entries = [];
    for (const name of names) {
      try {
        const st = await fsp.stat(path.join(dir, name));
        entries.push({
          name,
          isDir: st.isDirectory(),
          size: st.size,
          mtime: st.mtimeMs,
        });
      } catch {} // 접근 불가 항목은 건너뜀
    }
    entries.sort((a, b) => (a.isDir !== b.isDir ? (a.isDir ? -1 : 1) : a.name.localeCompare(b.name)));
    res.json({ entries });
  } catch (e) {
    next(e);
  }
});

app.get("/api/files/download", (req, res, next) => {
  try {
    const file = safePath(FILES_ROOT, req.query.path);
    res.download(file, path.basename(file));
  } catch (e) {
    next(e);
  }
});

// 요청 본문을 그대로 파일로 저장한다 (스트리밍이라 대용량도 안전)
app.post("/api/files/upload", (req, res, next) => {
  try {
    const dir = safePath(FILES_ROOT, req.query.path);
    const name = path.basename((req.query.name || "").toString());
    if (!name) return res.status(400).json({ error: "name required" });
    fs.mkdirSync(dir, { recursive: true });
    const dest = path.join(dir, name);
    const out = fs.createWriteStream(dest);
    req.pipe(out);
    out.on("finish", () => res.json({ ok: true }));
    out.on("error", next);
  } catch (e) {
    next(e);
  }
});

app.post("/api/files/mkdir", async (req, res, next) => {
  try {
    const dir = safePath(FILES_ROOT, req.query.path);
    await fsp.mkdir(dir, { recursive: true });
    res.json({ ok: true });
  } catch (e) {
    next(e);
  }
});

app.delete("/api/files", async (req, res, next) => {
  try {
    const target = safePath(FILES_ROOT, req.query.path);
    if (target === FILES_ROOT) return res.status(400).json({ error: "cannot delete root" });
    await fsp.rm(target, { recursive: true });
    res.json({ ok: true });
  } catch (e) {
    next(e);
  }
});

// ============ 포토 스테이션 ============

async function walkPhotos(dir, acc) {
  let names;
  try {
    names = await fsp.readdir(dir);
  } catch {
    return acc;
  }
  for (const name of names) {
    const full = path.join(dir, name);
    let st;
    try {
      st = await fsp.stat(full);
    } catch {
      continue;
    }
    if (st.isDirectory()) {
      await walkPhotos(full, acc);
    } else if (IMAGE_EXTS.has(path.extname(name).toLowerCase())) {
      acc.push({ path: path.relative(PHOTOS_ROOT, full).replace(/\\/g, "/"), mtime: st.mtimeMs, size: st.size });
    }
  }
  return acc;
}

app.get("/api/photos", async (req, res, next) => {
  try {
    const offset = parseInt(req.query.offset || "0", 10);
    const limit = Math.min(parseInt(req.query.limit || "200", 10), 500);
    const all = await walkPhotos(PHOTOS_ROOT, []);
    all.sort((a, b) => b.mtime - a.mtime);
    res.json({ total: all.length, photos: all.slice(offset, offset + limit) });
  } catch (e) {
    next(e);
  }
});

app.get("/api/photos/thumb", async (req, res, next) => {
  try {
    const file = safePath(PHOTOS_ROOT, req.query.path);
    const st = await fsp.stat(file);
    const key = crypto.createHash("md5").update(req.query.path + ":" + st.mtimeMs).digest("hex");
    const thumb = path.join(THUMBS_ROOT, key + ".jpg");
    if (!fs.existsSync(thumb)) {
      await sharp(file).rotate().resize(400, 400, { fit: "cover" }).jpeg({ quality: 75 }).toFile(thumb);
    }
    res.sendFile(thumb);
  } catch (e) {
    next(e);
  }
});

app.get("/api/photos/full", (req, res, next) => {
  try {
    const file = safePath(PHOTOS_ROOT, req.query.path);
    res.sendFile(file);
  } catch (e) {
    next(e);
  }
});

// ============ AI 사진 검색 (Gemini) ============

let photoTags = {};
try {
  photoTags = JSON.parse(fs.readFileSync(TAGS_FILE, "utf8"));
} catch {}

function saveTags() {
  fs.writeFile(TAGS_FILE, JSON.stringify(photoTags), () => {});
}

async function tagPhotoWithGemini(relPath) {
  const file = path.join(PHOTOS_ROOT, relPath);
  // 전송량을 줄이기 위해 768px로 축소해서 보낸다
  const jpeg = await sharp(file).rotate().resize(768, 768, { fit: "inside" }).jpeg({ quality: 80 }).toBuffer();
  const body = {
    contents: [{
      parts: [
        { text: "이 사진을 보고 검색용 한국어 키워드를 뽑아라. 사물/장면/장소/분위기 위주로 최대 12개. 반드시 JSON 문자열 배열만 출력해라. 예: [\"바다\",\"노을\",\"강아지\"]" },
        { inline_data: { mime_type: "image/jpeg", data: jpeg.toString("base64") } },
      ],
    }],
  };
  const resp = await fetch(
    `https://generativelanguage.googleapis.com/v1beta/models/${GEMINI_MODEL}:generateContent?key=${GEMINI_KEY}`,
    { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) }
  );
  if (!resp.ok) throw new Error(`Gemini HTTP ${resp.status}`);
  const json = await resp.json();
  const text = json.candidates?.[0]?.content?.parts?.[0]?.text || "[]";
  const start = text.indexOf("[");
  const end = text.lastIndexOf("]");
  const tags = JSON.parse(text.slice(start, end + 1));
  return Array.isArray(tags) ? tags.map(String) : [];
}

let indexing = false;
async function indexPhotos() {
  if (!GEMINI_KEY || indexing) return;
  indexing = true;
  try {
    const all = await walkPhotos(PHOTOS_ROOT, []);
    const pending = all.filter((p) => !photoTags[p.path]);
    for (const p of pending.slice(0, AI_BATCH)) {
      try {
        const tags = await tagPhotoWithGemini(p.path);
        photoTags[p.path] = { tags, at: Date.now() };
        console.log(`AI 태깅: ${p.path} → ${tags.join(", ")}`);
      } catch (e) {
        console.warn(`AI 태깅 실패 (${p.path}): ${e.message}`);
        if (String(e.message).includes("429")) break; // 한도 초과면 다음 주기로 미룸
      }
    }
    saveTags();
  } finally {
    indexing = false;
  }
}

if (GEMINI_KEY) {
  setTimeout(indexPhotos, 10_000);
  setInterval(indexPhotos, 10 * 60 * 1000);
}

app.get("/api/search", async (req, res, next) => {
  try {
    const q = (req.query.q || "").toString().trim().toLowerCase();
    if (!q) return res.json({ total: 0, photos: [] });
    const all = await walkPhotos(PHOTOS_ROOT, []);
    const matched = all.filter((p) => {
      if (p.path.toLowerCase().includes(q)) return true;
      const entry = photoTags[p.path];
      return entry && entry.tags.some((t) => t.toLowerCase().includes(q));
    });
    matched.sort((a, b) => b.mtime - a.mtime);
    res.json({ total: matched.length, photos: matched.slice(0, 500), aiEnabled: !!GEMINI_KEY });
  } catch (e) {
    next(e);
  }
});

app.get("/api/ai/status", async (req, res) => {
  const all = await walkPhotos(PHOTOS_ROOT, []);
  res.json({ enabled: !!GEMINI_KEY, indexed: Object.keys(photoTags).length, total: all.length });
});

// ============ 자동 백업 (폰 → 서버) ============
// 촬영 시각 기준 연/월 폴더에 저장. 같은 이름+크기 파일이 이미 있으면 스킵.

app.post("/api/backup", (req, res, next) => {
  try {
    const name = path.basename((req.query.name || "").toString());
    if (!name) return res.status(400).json({ error: "name required" });
    const takenMs = parseInt(req.query.mtime || Date.now().toString(), 10);
    const d = new Date(takenMs);
    const dir = path.join(
      PHOTOS_ROOT,
      String(d.getFullYear()),
      String(d.getMonth() + 1).padStart(2, "0")
    );
    fs.mkdirSync(dir, { recursive: true });
    const dest = path.join(dir, name);

    const declaredSize = parseInt(req.header("content-length") || "0", 10);
    if (fs.existsSync(dest) && declaredSize > 0 && fs.statSync(dest).size === declaredSize) {
      req.resume(); // 본문 소비 후 종료
      return res.json({ ok: true, skipped: true });
    }

    const out = fs.createWriteStream(dest);
    req.pipe(out);
    out.on("finish", () => {
      fs.utimes(dest, d, d, () => {});
      res.json({ ok: true, skipped: false });
    });
    out.on("error", next);
  } catch (e) {
    next(e);
  }
});

// ---- 공통 에러 처리 ----
app.use((err, req, res, next) => {
  const status = err.status || (err.code === "ENOENT" ? 404 : 500);
  res.status(status).json({ error: err.message });
});

app.listen(PORT, () => {
  console.log(`homeserver-api listening on :${PORT}`);
  console.log(`files:  ${FILES_ROOT}`);
  console.log(`photos: ${PHOTOS_ROOT}`);
});
