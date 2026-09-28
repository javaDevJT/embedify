import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { readFileSync } from "node:fs";

const require = createRequire(import.meta.url);
const { parseOptions, safeHttpsUrl, safeFeedUrl, safeTitle, validTimezone } = require("../src/main/resources/static/config.js");
const builderHtml = readFileSync(new URL("../src/main/resources/static/index.html", import.meta.url), "utf8");
const embedHtml = readFileSync(new URL("../src/main/resources/static/embed.html", import.meta.url), "utf8");
const faviconSvg = readFileSync(new URL("../src/main/resources/static/favicon.svg", import.meta.url), "utf8");
const appJs = readFileSync(new URL("../src/main/resources/static/app.js", import.meta.url), "utf8");

assert.equal(safeHttpsUrl("https://example.org/calendar.ics?key=visible"), "https://example.org/calendar.ics?key=visible");
assert.equal(safeHttpsUrl("http://example.org/calendar.ics"), null);
assert.equal(safeHttpsUrl("https://user:pass@example.org/calendar.ics"), null);
assert.equal(safeHttpsUrl("https://example.org:8443/calendar.ics"), null);

assert.equal(safeFeedUrl(" WEBCAL://example.org/calendar.ics?key=visible "), "https://example.org/calendar.ics?key=visible");
assert.equal(safeFeedUrl("https://example.org/calendar.ics"), "https://example.org/calendar.ics");
for (const value of ["http://example.org/calendar.ics", "webcal://user:pass@example.org/calendar.ics", "webcal://example.org:8443/calendar.ics", "webcal:not-a-url"]) {
  assert.equal(safeFeedUrl(value), null);
}
assert.equal(safeHttpsUrl("webcal://example.org/calendar.ics"), null);
assert.deepEqual(parseOptions(new URLSearchParams({ feed: "webcal://example.org/calendar.ics" })).feeds, ["https://example.org/calendar.ics"]);

const options = parseOptions("?feed=https%3A%2F%2Fexample.org%2Fone.ics&feed=http%3A%2F%2Finvalid.test%2Ffeed&feed=https%3A%2F%2Fexample.net%2Ftwo.ics&title=Shared%20dates&tz=UTC&weekStart=mon&background=%23fafafa&surface=%23ffffff&text=%23000000&accent=%23cc4422&font=mono&size=large&radius=round&density=airy&view=agenda&month=2026-10");
assert.deepEqual(options.feeds, ["https://example.org/one.ics", "https://example.net/two.ics"]);
assert.equal(options.title, "Shared dates");
assert.equal(options.timezone, "UTC");
assert.equal(options.weekStart, "mon");
assert.equal(options.background, "#fafafa");
assert.equal(options.surface, "#ffffff");
assert.equal(options.text, "#000000");
assert.equal(options.accent, "#cc4422");
assert.equal(options.font, "mono");
assert.equal(options.size, "large");
assert.equal(options.radius, "round");
assert.equal(options.density, "airy");
assert.equal(options.view, "agenda");
assert.equal(options.month, "2026-10");

const defaults = parseOptions("?background=rgb(0,0,0)&font=external&view=script&month=2026-13&weekStart=friday");
assert.deepEqual(defaults.feeds, []);
assert.equal(defaults.background, "#faf8ff");
assert.equal(defaults.surface, "#ffffff");
assert.equal(defaults.text, "#131b2e");
assert.equal(defaults.accent, "#0053db");
assert.equal(defaults.font, "sans");
assert.equal(defaults.size, "standard");
assert.equal(defaults.radius, "soft");
assert.equal(defaults.density, "comfortable");
assert.equal(defaults.view, "month");
assert.match(defaults.month, /^\d{4}-\d{2}$/);
assert.equal(defaults.weekStart, "sun");
assert.equal(safeTitle("  Calendar\n\u0000 title  "), "Calendar title");
assert.equal(safeTitle("x".repeat(100)).length, 72);
assert.doesNotThrow(() => new Intl.DateTimeFormat("en-US", { timeZone: validTimezone("Not/AZone") }));

const fiveFeeds = parseOptions("?" + Array.from({ length: 6 }, (_, index) => `feed=https%3A%2F%2Fexample${index}.org%2Ffeed.ics`).join("&"));
assert.equal(fiveFeeds.feeds.length, 5);

const builderIds = new Set(Array.from(builderHtml.matchAll(/\bid="([^"]+)"/g), match => match[1]));
const embedIds = new Set(Array.from(embedHtml.matchAll(/\bid="([^"]+)"/g), match => match[1]));
for (const id of ["feed-list", "feed-count", "feed-warning", "feed-validation", "calendar-title", "timezone", "week-start", "calendar-view", "calendar-size", "color-background", "color-surface", "color-text", "color-accent", "font-category", "corner-style", "density", "style-css", "style-url", "suggest-style", "apply-style", "preview-notice", "calendar-root", "preview-width", "embed-url", "iframe-code", "copy-url", "copy-iframe", "copy-status", "export-ready", "year"]) {
  assert.ok(builderIds.has(id), `builder is missing #${id}`);
}
assert.ok(embedIds.has("preview-notice"));
assert.ok(embedIds.has("calendar-root"));
assert.match(builderHtml, /<link rel="icon" type="image\/svg\+xml" href="\/favicon\.svg">/);
assert.match(embedHtml, /<link rel="icon" type="image\/svg\+xml" href="\/favicon\.svg">/);
assert.match(faviconSvg, /<svg xmlns="http:\/\/www\.w3\.org\/2000\/svg"/);
assert.match(appJs, /function buildEmbedUrl\(options, month, view, includeMonth = false\)/);
assert.match(appJs, /if \(includeMonth\) url\.searchParams\.set\("month", month\)/);
assert.match(appJs, /buildEmbedUrl\(options, state\.month, state\.view\)/);
assert.match(appJs, /buildEmbedUrl\(state\.options, state\.month, state\.view, true\)/);
assert.doesNotMatch(`${builderHtml}\n${embedHtml}`, /cdn\.tailwindcss\.com|fonts\.googleapis\.com|fonts\.gstatic\.com|material-symbols/i);
assert.doesNotMatch(builderHtml, /\bSign in\b|\bGitHub\b|\bWeek view\b|auto-height|real-time sync|cookie policy|privacy policy|terms of service/i);

console.log("Embedify URL parsing checks passed.");
