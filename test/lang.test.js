import test from "node:test";
import assert from "node:assert/strict";
import { hasHangul, countEnglishWords, cleanTranscript, isLikelyNoise, isEcho } from "../public/js/lang.js";

test("hasHangul detects Korean", () => {
  assert.equal(hasHangul("비가 오네"), true);
  assert.equal(hasHangul("I like star"), false);
});

test("countEnglishWords", () => {
  assert.equal(countEnglishWords("I don't like it 좋아"), 4);
});

test("cleanTranscript trims and caps", () => {
  assert.equal(cleanTranscript("  hello   world "), "hello world");
  assert.equal(cleanTranscript("a".repeat(500)).length, 300);
});

test("isLikelyNoise", () => {
  assert.equal(isLikelyNoise("", 0.9), true);
  assert.equal(isLikelyNoise("um uh", 0.9), true);
  assert.equal(isLikelyNoise("hello", 0.1), true);
  assert.equal(isLikelyNoise("hello", 0), false); // 0 = unknown confidence
  assert.equal(isLikelyNoise("I like star", 0.8), false);
});

test("isEcho catches the buddy's own voice", () => {
  const spoken = "Do you want to build a house or go mining? You can say: Build a house!";
  assert.equal(isEcho("build a house or go mining", spoken), true);
  assert.equal(isEcho("a house", spoken), false); // too short to judge
  assert.equal(isEcho("I want to go fishing", spoken), false);
});
