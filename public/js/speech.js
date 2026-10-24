// Hands-free voice loop: listen -> (child finishes a sentence) -> think -> speak -> listen.
// The mic is fully stopped while the buddy talks so the speaker can't feed back into it.

const Recognition = window.SpeechRecognition || window.webkitSpeechRecognition;

export function speechSupported() {
  return Boolean(Recognition && window.speechSynthesis);
}

function pickVoice(lang) {
  const voices = speechSynthesis.getVoices();
  const base = lang.slice(0, 2);
  return (
    voices.find((v) => v.lang === lang && /google/i.test(v.name)) ||
    voices.find((v) => v.lang === lang) ||
    voices.find((v) => v.lang.replace("_", "-").startsWith(base)) ||
    null
  );
}

export class VoiceLoop {
  /**
   * @param {object} h handlers: onState(state), onInterim(text), onFinal(text, confidence), onError(code)
   */
  constructor(h) {
    this.h = h;
    this.state = "idle";
    this.lang = "en-US";
    this.rate = 0.85;
    this.rec = null;
    this.restartTimer = 0;
    this.wantListening = false;
    speechSynthesis.getVoices();
    speechSynthesis.addEventListener?.("voiceschanged", () => speechSynthesis.getVoices());
  }

  setState(s) {
    this.state = s;
    this.h.onState?.(s);
  }

  listen() {
    this.wantListening = true;
    clearTimeout(this.restartTimer);
    if (this.rec) return;
    const rec = new Recognition();
    rec.lang = this.lang;
    // Android Chrome is more reliable with single-shot sessions restarted on end
    // than with continuous=true, which tends to duplicate results there.
    rec.continuous = false;
    rec.interimResults = true;
    rec.maxAlternatives = 1;
    rec.onresult = (e) => {
      let interim = "";
      for (let i = e.resultIndex; i < e.results.length; i++) {
        const r = e.results[i];
        if (r.isFinal) {
          this.wantListening = false;
          rec.abort();
          this.h.onFinal?.(r[0].transcript, r[0].confidence);
          return;
        }
        interim += r[0].transcript;
      }
      this.h.onInterim?.(interim);
    };
    rec.onerror = (e) => {
      if (e.error === "not-allowed" || e.error === "service-not-allowed" || e.error === "audio-capture") {
        this.wantListening = false;
        this.h.onError?.(e.error);
      }
      // "no-speech", "aborted", "network": fall through to onend and restart.
    };
    rec.onend = () => {
      this.rec = null;
      this.h.onInterim?.("");
      if (this.wantListening) this.restartTimer = setTimeout(() => this.listen(), 250);
    };
    this.rec = rec;
    try {
      rec.start();
      this.setState("listening");
    } catch {
      this.rec = null;
      this.restartTimer = setTimeout(() => this.listen(), 500);
    }
  }

  stopListening() {
    this.wantListening = false;
    clearTimeout(this.restartTimer);
    if (this.rec) {
      this.rec.onend = null;
      this.rec.abort();
      this.rec = null;
    }
  }

  /** Speak a list of {text, lang} parts in order. Resolves when done (or on safety timeout). */
  async speak(parts) {
    this.stopListening();
    this.setState("speaking");
    for (const p of parts) {
      if (!p.text) continue;
      await this.#say(p.text, p.lang || "en-US", p.lang?.startsWith("ko") ? 1.0 : this.rate);
    }
  }

  #say(text, lang, rate) {
    return new Promise((resolve) => {
      const u = new SpeechSynthesisUtterance(text);
      u.lang = lang;
      u.rate = rate;
      const v = pickVoice(lang);
      if (v) u.voice = v;
      // onend is occasionally never fired on Android, so cap the wait.
      const guard = setTimeout(done, 2000 + (text.length * 110) / rate);
      function done() {
        clearTimeout(guard);
        resolve();
      }
      u.onend = done;
      u.onerror = done;
      speechSynthesis.speak(u);
    });
  }

  shutdown() {
    this.stopListening();
    speechSynthesis.cancel();
    this.setState("idle");
  }
}
