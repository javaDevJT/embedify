(function (root) {
  "use strict";

  const HEX = /^#[0-9a-f]{6}$/i;
  const FONTS = ["serif", "sans", "mono"];
  const SIZES = ["compact", "standard", "large"];
  const RADII = ["square", "soft", "round"];
  const DENSITIES = ["compact", "comfortable", "airy"];
  const VIEWS = ["month", "agenda"];

  function safeHttpsUrl(value) {
    try {
      const url = new URL(String(value).trim());
      if (url.protocol !== "https:" || url.username || url.password || (url.port && url.port !== "443")) return null;
      return url.href;
    } catch (_) {
      return null;
    }
  }

  function validTimezone(value) {
    const candidate = String(value || "").trim();
    try {
      new Intl.DateTimeFormat("en-US", { timeZone: candidate });
      return candidate;
    } catch (_) {
      return Intl.DateTimeFormat().resolvedOptions().timeZone || "America/Detroit";
    }
  }

  function safeTitle(value) {
    return String(value || "").replace(/[\u0000-\u001f\u007f]/g, " ").replace(/\s+/g, " ").trim().slice(0, 72);
  }

  function currentMonth() {
    const now = new Date();
    return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, "0")}`;
  }

  function validMonth(value) {
    const candidate = String(value || "");
    if (!/^\d{4}-(0[1-9]|1[0-2])$/.test(candidate) || Number(candidate.slice(0, 4)) < 1900) return currentMonth();
    return candidate;
  }

  function choice(value, allowed, fallback) {
    return allowed.includes(value) ? value : fallback;
  }

  function parseOptions(search) {
    const query = search instanceof URLSearchParams
      ? search
      : new URLSearchParams(String(search || "").replace(/^\?/, ""));
    const localZone = Intl.DateTimeFormat().resolvedOptions().timeZone || "America/Detroit";
    const feeds = query.getAll("feed").map(safeHttpsUrl).filter(Boolean).slice(0, 5);
    const background = query.get("background");
    const surface = query.get("surface");
    const text = query.get("text");
    const accent = query.get("accent");
    return {
      feeds,
      title: safeTitle(query.get("title")) || "Shared calendar",
      timezone: validTimezone(query.get("tz") || localZone),
      weekStart: choice(query.get("weekStart"), ["sun", "mon"], "sun"),
      background: HEX.test(background || "") ? background : "#faf8ff",
      surface: HEX.test(surface || "") ? surface : "#ffffff",
      text: HEX.test(text || "") ? text : "#131b2e",
      accent: HEX.test(accent || "") ? accent : "#0053db",
      font: choice(query.get("font"), FONTS, "sans"),
      size: choice(query.get("size"), SIZES, "standard"),
      radius: choice(query.get("radius"), RADII, "soft"),
      density: choice(query.get("density"), DENSITIES, "comfortable"),
      view: choice(query.get("view"), VIEWS, "month"),
      month: validMonth(query.get("month"))
    };
  }

  const api = { safeHttpsUrl, validTimezone, safeTitle, parseOptions };
  root.EmbedifyConfig = api;
  if (typeof module !== "undefined" && module.exports) module.exports = api;
})(typeof window !== "undefined" ? window : globalThis);
