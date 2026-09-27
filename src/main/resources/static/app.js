(function () {
  "use strict";

  const config = window.EmbedifyConfig;
  const page = document.body.dataset.page;
  const isBuilder = page === "builder";
  const parsed = config.parseOptions(window.location.search);
  const initialFeeds = new URLSearchParams(window.location.search).getAll("feed");
  const state = {
    options: parsed,
    month: parsed.month,
    view: parsed.view,
    feeds: parsed.feeds,
    events: [],
    live: parsed.feeds.length > 0,
    loading: false,
    loaded: false,
    error: false,
    truncated: false,
    refreshSeconds: 60,
    lastRequestAt: 0,
    controller: null,
    requestId: 0,
    pollTimer: 0,
    inputTimer: 0,
    pendingStyle: null,
    invalidEmbedFeeds: initialFeeds.length > parsed.feeds.length
  };

  const calendarRoot = document.getElementById("calendar-root");
  const notice = document.getElementById("preview-notice");
  let eventLinkSequence = 0;
  if (!calendarRoot || !config) return;

  function node(tag, className, text) {
    const item = document.createElement(tag);
    if (className) item.className = className;
    if (text !== undefined) item.textContent = text;
    return item;
  }

  function setMessage(element, message, kind) {
    if (!element) return;
    element.textContent = message || "";
    if (kind) element.dataset.kind = kind;
    else delete element.dataset.kind;
  }

  function pad(value) { return String(value).padStart(2, "0"); }

  function dateKey(year, month, day) {
    const date = new Date(Date.UTC(year, month - 1, day));
    return `${date.getUTCFullYear()}-${pad(date.getUTCMonth() + 1)}-${pad(date.getUTCDate())}`;
  }

  function addDays(key, amount) {
    const [year, month, day] = key.split("-").map(Number);
    const date = new Date(Date.UTC(year, month - 1, day + amount));
    return dateKey(date.getUTCFullYear(), date.getUTCMonth() + 1, date.getUTCDate());
  }

  function monthParts(key) {
    const [year, month] = key.split("-").map(Number);
    return { year, month };
  }

  function shiftMonth(key, offset) {
    const { year, month } = monthParts(key);
    const date = new Date(Date.UTC(year, month - 1 + offset, 15));
    return `${date.getUTCFullYear()}-${pad(date.getUTCMonth() + 1)}`;
  }

  function monthTitle(key, timezone) {
    const { year, month } = monthParts(key);
    const date = new Date(Date.UTC(year, month - 1, 15, 12));
    return new Intl.DateTimeFormat("en-US", { month: "long", year: "numeric", timeZone: timezone }).format(date);
  }

  function partsInZone(value, timezone) {
    const date = value instanceof Date ? value : new Date(value);
    if (Number.isNaN(date.getTime())) return null;
    const parts = new Intl.DateTimeFormat("en-US", {
      timeZone: timezone,
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit",
      hourCycle: "h23"
    }).formatToParts(date);
    const values = Object.fromEntries(parts.filter(part => part.type !== "literal").map(part => [part.type, part.value]));
    return values;
  }

  function dateInZone(value, timezone) {
    const parts = partsInZone(value, timezone);
    return parts ? `${parts.year}-${parts.month}-${parts.day}` : null;
  }

  function isoDate(value) {
    const match = String(value || "").match(/^(\d{4}-\d{2}-\d{2})(?:$|T)/);
    return match ? match[1] : null;
  }

  function dayLabel(key, timezone) {
    const [year, month, day] = key.split("-").map(Number);
    const value = new Date(Date.UTC(year, month - 1, day, 12));
    return new Intl.DateTimeFormat("en-US", { weekday: "long", month: "long", day: "numeric", timeZone: "UTC" }).format(value);
  }

  function weekdayLabel(dayIndex, timezone) {
    const value = new Date(Date.UTC(2026, 0, 4 + dayIndex, 12));
    return new Intl.DateTimeFormat("en-US", { weekday: "short", timeZone: "UTC" }).format(value);
  }

  function gridStart(month, weekStart) {
    const { year, month: number } = monthParts(month);
    const first = new Date(Date.UTC(year, number - 1, 1));
    const weekday = first.getUTCDay();
    const offset = weekStart === "mon" ? (weekday + 6) % 7 : weekday;
    return addDays(dateKey(year, number, 1), -offset);
  }

  function queryRange(month, weekStart) {
    const from = gridStart(month, weekStart);
    return { from, to: addDays(from, 42) };
  }

  function cleanText(value, maxLength) {
    return String(value == null ? "" : value).replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/g, " ").trim().slice(0, maxLength);
  }

  function eventIsCancelled(event) {
    return event.cancelled === true || String(event.status || "").toUpperCase() === "CANCELLED";
  }

  function eventStartKey(event, timezone) {
    return event.allDay ? isoDate(event.start) : dateInZone(event.start, timezone);
  }

  function eventEndKey(event, timezone) {
    if (!event.end) return eventStartKey(event, timezone);
    return event.allDay ? isoDate(event.end) : dateInZone(event.end, timezone);
  }

  function eventDates(event, timezone) {
    if (eventIsCancelled(event)) return [];
    const startTime = event.allDay ? NaN : Date.parse(event.start);
    const endTime = event.allDay ? NaN : Date.parse(event.end || event.start);
    const first = eventStartKey(event, timezone);
    if (!first) return [];
    let last = first;

    if (event.allDay) {
      const endExclusive = eventEndKey(event, timezone);
      if (endExclusive && endExclusive > first) last = addDays(endExclusive, -1);
    } else if (Number.isFinite(startTime) && Number.isFinite(endTime) && endTime > startTime) {
      const endKey = eventEndKey(event, timezone);
      const endParts = partsInZone(endTime, timezone);
      const endsAtMidnight = endParts && endParts.hour === "00" && endParts.minute === "00" && endParts.second === "00";
      if (endKey) last = endsAtMidnight ? addDays(endKey, -1) : endKey;
      if (last < first) last = first;
    }

    const dates = [];
    let cursor = first;
    while (cursor <= last && dates.length < 43) {
      dates.push(cursor);
      cursor = addDays(cursor, 1);
    }
    return dates;
  }

  function compareEvents(a, b) {
    return String(a.start || "").localeCompare(String(b.start || ""));
  }

  function demoEvents(month) {
    const { year, month: number } = monthParts(month);
    const at = (day, hour, minute) => new Date(Date.UTC(year, number - 1, day, hour, minute)).toISOString();
    return [
      { id: "sample-open-studio", title: "Open studio", start: dateKey(year, number, 4), end: dateKey(year, number, 5), allDay: true, location: "The shared room" },
      { id: "sample-planning", title: "Planning table", start: at(8, 15, 0), end: at(8, 15, 45), allDay: false, location: "On the video call" },
      { id: "sample-market", title: "Neighborhood market", start: dateKey(year, number, 15), end: dateKey(year, number, 17), allDay: true, location: "Town square" },
      { id: "sample-supper", title: "Thursday supper", start: at(22, 22, 30), end: at(22, 23, 45), allDay: false, location: "Juniper & Rye" }
    ];
  }

  function buildEmbedUrl(options, month, view, includeMonth = false) {
    const url = new URL("/embed", window.location.origin);
    options.feeds.forEach(feed => url.searchParams.append("feed", feed));
    url.searchParams.set("title", config.safeTitle(options.title) || "Shared calendar");
    url.searchParams.set("tz", config.validTimezone(options.timezone));
    url.searchParams.set("weekStart", options.weekStart === "mon" ? "mon" : "sun");
    url.searchParams.set("background", options.background);
    url.searchParams.set("surface", options.surface);
    url.searchParams.set("text", options.text);
    url.searchParams.set("accent", options.accent);
    url.searchParams.set("font", options.font);
    url.searchParams.set("size", options.size);
    url.searchParams.set("radius", options.radius);
    url.searchParams.set("density", options.density);
    url.searchParams.set("view", view);
    if (includeMonth) url.searchParams.set("month", month);
    return url;
  }

  function escapeAttribute(value) {
    return String(value).replace(/&/g, "&amp;").replace(/"/g, "&quot;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
  }

  function makeIframe(url, title) {
    const source = escapeAttribute(url.href);
    const frameTitle = escapeAttribute(title);
    return `<iframe src="${source}" title="${frameTitle}" loading="lazy" referrerpolicy="no-referrer" style="width:100%;height:720px;border:0" allow="" ></iframe>`;
  }

  function validHex(value) { return /^#[0-9a-f]{6}$/i.test(String(value || "")); }

  function currentOptions() {
    const feeds = Array.from(document.querySelectorAll(".feed-url"))
      .map(input => config.safeHttpsUrl(input.value))
      .filter(Boolean);
    const titleInput = document.getElementById("calendar-title");
    const timezoneInput = document.getElementById("timezone");
    const timezoneRaw = timezoneInput ? timezoneInput.value.trim() : parsed.timezone;
    const timezone = config.validTimezone(timezoneRaw || parsed.timezone);
    const color = id => {
      const value = document.getElementById(id)?.value;
      return validHex(value) ? value : "#131b2e";
    };
    const result = {
      feeds,
      title: config.safeTitle(titleInput ? titleInput.value : parsed.title) || "Shared calendar",
      timezone,
      timezoneValid: !timezoneRaw || timezone === timezoneRaw,
      weekStart: document.getElementById("week-start")?.value === "mon" ? "mon" : "sun",
      background: color("color-background"),
      surface: color("color-surface"),
      text: color("color-text"),
      accent: color("color-accent"),
      font: document.getElementById("font-category")?.value || parsed.font,
      size: document.getElementById("calendar-size")?.value || parsed.size,
      radius: document.getElementById("corner-style")?.value || parsed.radius,
      density: document.getElementById("density")?.value || parsed.density,
      view: document.getElementById("calendar-view")?.value || state.view,
      month: state.month
    };
    return result;
  }

  function feedValidation() {
    const fields = Array.from(document.querySelectorAll(".feed-url"));
    const bad = [];
    fields.forEach((input, index) => {
      const value = input.value.trim();
      const invalid = value && !config.safeHttpsUrl(value);
      input.setCustomValidity(invalid ? "Enter a public HTTPS URL without credentials." : "");
      if (invalid) bad.push(index + 1);
    });
    return bad;
  }

  function updateNotice() {
    if (!notice) return;
    if (state.invalidEmbedFeeds && !state.feeds.length) {
      setMessage(notice, "This link has no usable public HTTPS feed. Showing a sample calendar instead.", "error");
    } else if (state.invalidEmbedFeeds) {
      setMessage(notice, "Some feed parameters were skipped. Only the first five valid public HTTPS feeds are used.", "");
    } else if (state.error) {
      setMessage(notice, "Couldn’t load the calendar feed. Check that it is public, then try again.", "error");
    } else if (state.loading) {
      setMessage(notice, "Loading public calendar feeds…", "");
    } else if (!state.feeds.length) {
      setMessage(notice, "Sample demo · connect at least one public feed to see your actual events.", "");
    } else if (state.loaded) {
      setMessage(notice, "Public feed loaded · refreshes at most once a minute while this page is visible.", "live");
    } else {
      setMessage(notice, "Live feed preview · loading source calendar.", "");
    }
  }

  function renderNoticeForBuilder(invalidFeeds, timezoneValid) {
    if (!notice) return;
    if (invalidFeeds.length || !timezoneValid) {
      setMessage(notice, invalidFeeds.length ? "Use a complete public HTTPS feed URL to load live events." : "Use a valid IANA time zone to load live events.", "error");
      return;
    }
    updateNotice();
  }

  function syncShare(options) {
    const urlField = document.getElementById("embed-url");
    const iframeField = document.getElementById("iframe-code");
    if (!urlField || !iframeField) return;
    const url = buildEmbedUrl(options, state.month, state.view);
    urlField.value = url.href;
    iframeField.value = makeIframe(url, options.title);
  }

  function populateBuilder(options) {
    const title = document.getElementById("calendar-title");
    if (!title) return;
    title.value = options.title;
    document.getElementById("timezone").value = options.timezone;
    document.getElementById("week-start").value = options.weekStart;
    document.getElementById("calendar-view").value = options.view;
    document.getElementById("calendar-size").value = options.size;
    document.getElementById("font-category").value = options.font;
    document.getElementById("corner-style").value = options.radius;
    document.getElementById("density").value = options.density;
    document.getElementById("color-background").value = options.background;
    document.getElementById("color-surface").value = options.surface;
    document.getElementById("color-text").value = options.text;
    document.getElementById("color-accent").value = options.accent;

    const list = document.getElementById("feed-list");
    while (list.firstChild) list.removeChild(list.firstChild);
    const feeds = options.feeds.length ? options.feeds : [""];
    feeds.slice(0, 5).forEach((feed, index) => list.appendChild(createFeedRow(index, feed)));
    updateFeedControls();

    const zones = document.getElementById("timezone-options");
    const supported = typeof Intl.supportedValuesOf === "function" ? Intl.supportedValuesOf("timeZone") : [];
    const commonZones = ["America/Detroit", "America/New_York", "America/Chicago", "America/Denver", "America/Los_Angeles", "America/Phoenix", "America/Anchorage", "Pacific/Honolulu", "Europe/London", "Europe/Paris", "Europe/Berlin", "Asia/Tokyo", "Australia/Sydney", "UTC"];
    [...new Set([...commonZones, ...supported])].forEach(zone => {
      const option = node("option");
      option.value = zone;
      zones.appendChild(option);
    });
  }

  function createFeedRow(index, value) {
    const row = node("div", "feed-row");
    row.dataset.feedRow = "";
    const label = node("label", "", `Feed ${index + 1} URL`);
    const inputId = `feed-${index}`;
    label.htmlFor = inputId;
    const wrap = node("div", "feed-input-wrap");
    const input = node("input", "feed-url");
    input.id = inputId;
    input.type = "url";
    input.inputMode = "url";
    input.maxLength = 2048;
    input.autocomplete = "off";
    input.spellcheck = false;
    input.placeholder = "https://example.org/calendar.ics";
    input.value = value || "";
    input.setAttribute("aria-describedby", "feed-help feed-warning");
    const remove = node("button", "remove-feed", "−");
    remove.type = "button";
    remove.setAttribute("aria-label", `Remove feed ${index + 1}`);
    remove.disabled = index === 0 && document.querySelectorAll(".feed-url").length <= 1;
    remove.addEventListener("click", () => {
      row.remove();
      reindexFeedRows();
      updateBuilder(true);
    });
    wrap.append(input, remove);
    row.append(label, wrap);
    return row;
  }

  function reindexFeedRows() {
    document.querySelectorAll("[data-feed-row]").forEach((row, index) => {
      const label = row.querySelector("label");
      const input = row.querySelector(".feed-url");
      const remove = row.querySelector(".remove-feed");
      input.id = `feed-${index}`;
      label.htmlFor = input.id;
      label.textContent = `Feed ${index + 1} URL`;
      remove.setAttribute("aria-label", `Remove feed ${index + 1}`);
      remove.disabled = document.querySelectorAll(".feed-url").length <= 1;
    });
    updateFeedControls();
  }

  function updateFeedControls() {
    const count = document.querySelectorAll(".feed-url").length;
    const button = document.getElementById("add-feed");
    const countLabel = document.getElementById("feed-count");
    if (button) button.disabled = count >= 5;
    if (countLabel) countLabel.textContent = `${count} / 5`;
    document.querySelectorAll(".remove-feed").forEach(remove => { remove.disabled = count <= 1; });
  }

  function builderInputsChanged(event) {
    const target = event.target;
    const dataAffectsEvents = target.classList.contains("feed-url") || target.id === "timezone";
    updateBuilder(dataAffectsEvents);
  }

  function updateBuilder(eventsChanged) {
    if (!isBuilder) return;
    const badFeeds = feedValidation();
    const options = currentOptions();
    const validation = document.getElementById("feed-validation");
    if (badFeeds.length) setMessage(validation, `Feed ${badFeeds.join(", ")} needs a complete public HTTPS URL.`);
    else if (!options.timezoneValid) setMessage(validation, "Enter a valid time zone such as America/Detroit or UTC.");
    else setMessage(validation, "");

    const invalid = badFeeds.length > 0 || !options.timezoneValid;
    state.options = options;
    state.view = options.view;
    state.feeds = invalid ? [] : options.feeds;
    state.live = state.feeds.length > 0;
    state.invalidEmbedFeeds = false;

    const urlField = document.getElementById("embed-url");
    const iframeField = document.getElementById("iframe-code");
    const copyUrl = document.getElementById("copy-url");
    const copyIframe = document.getElementById("copy-iframe");
    const exportReady = document.getElementById("export-ready");
    if (!invalid) syncShare(options);
    else {
      if (urlField) urlField.value = "Correct feed or time zone entries before copying.";
      if (iframeField) iframeField.value = "";
    }
    if (copyUrl) copyUrl.disabled = invalid;
    if (copyIframe) copyIframe.disabled = invalid;
    if (exportReady) {
      exportReady.textContent = invalid ? "Check your settings" : "Ready to copy";
      exportReady.dataset.state = invalid ? "error" : "ready";
    }

    if (!state.feeds.length) {
      state.events = demoEvents(state.month);
      state.loaded = false;
      state.loading = false;
      state.error = false;
      state.truncated = false;
    } else if (eventsChanged) {
      state.events = [];
      state.loaded = false;
      state.loading = true;
      state.error = false;
      scheduleInputLoad();
    } else if (!state.loaded && !state.loading) {
      state.loading = true;
    }
    renderNoticeForBuilder(badFeeds, options.timezoneValid);
    renderCalendar();
  }

  function safeOptionHex(value, fallback) { return validHex(value) ? value : fallback; }

  function formatTime(value, timezone) {
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return "";
    return new Intl.DateTimeFormat("en-US", { hour: "numeric", minute: "2-digit", timeZone: timezone }).format(date);
  }

  function eventTimeLabel(event, timezone) {
    if (event.allDay) return "All day";
    const start = formatTime(event.start, timezone);
    const end = event.end ? formatTime(event.end, timezone) : "";
    return end && end !== start ? `${start}–${end}` : start;
  }

  function eventRangeLabel(event, timezone) {
    const start = eventStartKey(event, timezone);
    const end = eventEndKey(event, timezone);
    if (!start) return "";
    let last = start;
    if (end && end > start) {
      if (event.allDay) last = addDays(end, -1);
      else {
        const endTime = Date.parse(event.end || "");
        const parts = partsInZone(endTime, timezone);
        last = parts && parts.hour === "00" && parts.minute === "00" && parts.second === "00" ? addDays(end, -1) : end;
      }
    }
    if (last === start) return dayLabel(start, timezone);
    const firstLabel = new Intl.DateTimeFormat("en-US", { weekday: "short", month: "short", day: "numeric", timeZone: "UTC" }).format(new Date(`${start}T12:00:00Z`));
    const lastLabel = new Intl.DateTimeFormat("en-US", { weekday: "short", month: "short", day: "numeric", timeZone: "UTC" }).format(new Date(`${last}T12:00:00Z`));
    return `${firstLabel} – ${lastLabel}`;
  }

  function appendEventTitle(parent, event, title, className) {
    const safeLink = config.safeHttpsUrl(event.url);
    const label = safeLink ? node("a", className, title) : node("span", className, title);
    label.dataset.focusKey = `event-link-${eventLinkSequence++}`;
    if (safeLink) {
      label.href = safeLink;
      label.target = "_blank";
      label.rel = "noopener noreferrer";
      label.referrerPolicy = "no-referrer";
    }
    parent.appendChild(label);
  }

  function makeMessage(title, description, loading) {
    const message = node("div", "calendar-message");
    message.setAttribute("role", loading ? "status" : "group");
    if (loading) message.appendChild(node("span", "loading-dash"));
    message.appendChild(node("strong", "", title));
    if (description) message.appendChild(node("p", "", description));
    if (!loading && state.error) {
      const retry = node("button", "outline-button", "Try again");
      retry.type = "button";
      retry.id = "calendar-retry";
      retry.addEventListener("click", () => loadEvents());
      message.appendChild(retry);
    }
    return message;
  }

  function makeMonthView(card) {
    const timezone = state.options.timezone;
    const start = gridStart(state.month, state.options.weekStart);
    const today = dateInZone(new Date(), timezone);
    const weekdays = node("div", "weekday-row");
    weekdays.setAttribute("role", "row");
    const startDay = state.options.weekStart === "mon" ? 1 : 0;
    for (let index = 0; index < 7; index += 1) {
      const dayIndex = (startDay + index) % 7;
      const label = weekdayLabel(dayIndex, timezone);
      const header = node("div", "weekday", label);
      header.setAttribute("role", "columnheader");
      header.setAttribute("aria-label", new Intl.DateTimeFormat("en-US", { weekday: "long", timeZone: "UTC" }).format(new Date(Date.UTC(2026, 0, 4 + dayIndex, 12))));
      weekdays.appendChild(header);
    }

    const grid = node("div", "month-grid");
    grid.setAttribute("role", "grid");
    grid.setAttribute("aria-label", `${monthTitle(state.month, timezone)} calendar`);
    const eventMap = new Map();
    state.events.slice(0, 500).forEach(event => {
      eventDates(event, timezone).forEach(key => {
        if (key >= start && key < addDays(start, 42)) {
          if (!eventMap.has(key)) eventMap.set(key, []);
          eventMap.get(key).push(event);
        }
      });
    });
    for (let index = 0; index < 42; index += 1) {
      const key = addDays(start, index);
      const cell = node("div", "day-cell");
      cell.setAttribute("role", "gridcell");
      cell.setAttribute("aria-label", dayLabel(key, timezone));
      if (key.slice(0, 7) !== state.month) cell.classList.add("is-outside");
      if (key === today) cell.classList.add("is-today");
      const number = node("span", "day-number", String(Number(key.slice(-2))));
      cell.appendChild(number);
      const dayEvents = (eventMap.get(key) || []).sort(compareEvents);
      const list = node("ol", "day-events");
      dayEvents.slice(0, 3).forEach(event => {
        const item = node("li");
        const title = cleanText(event.title, 100) || "Untitled event";
        appendEventTitle(item, event, title, "event-chip");
        const linked = item.firstElementChild;
        linked.title = title;
        linked.setAttribute("aria-label", `${title}, ${eventTimeLabel(event, timezone)}, ${eventRangeLabel(event, timezone)}`);
        list.appendChild(item);
      });
      if (dayEvents.length > 3) list.appendChild(node("li", "event-more", `+${dayEvents.length - 3} more`));
      cell.appendChild(list);
      grid.appendChild(cell);
    }
    card.append(weekdays, grid);
  }

  function makeAgendaView(card) {
    const { year, month } = monthParts(state.month);
    const first = dateKey(year, month, 1);
    const next = shiftMonth(state.month, 1) + "-01";
    const timezone = state.options.timezone;
    const rows = state.events.filter(event => {
      const dates = eventDates(event, timezone);
      return dates.some(key => key >= first && key < next);
    }).sort(compareEvents);
    if (!rows.length) {
      card.appendChild(makeMessage("Nothing on the calendar yet", "There are no events in this month. When a feed has entries, they will appear here.", false));
      return;
    }
    const list = node("div", "agenda-list");
    rows.slice(0, 100).forEach(event => {
      const item = node("article", "agenda-item");
      const date = node("div", "agenda-date");
      date.appendChild(node("span", "", event.allDay ? "DATE" : eventTimeLabel(event, timezone)));
      date.appendChild(document.createTextNode(eventRangeLabel(event, timezone)));
      item.appendChild(date);
      const body = node("div", "agenda-content");
      const title = node("h3");
      appendEventTitle(title, event, cleanText(event.title, 140) || "Untitled event", "agenda-title");
      body.appendChild(title);
      const details = [eventTimeLabel(event, timezone), cleanText(event.location, 150)].filter(Boolean);
      if (event.allDay) details[0] = "All day";
      if (details.length) body.appendChild(node("p", "agenda-meta", details.join(" · ")));
      const description = cleanText(event.description, 420);
      if (description) body.appendChild(node("p", "agenda-description", description));
      item.appendChild(body);
      list.appendChild(item);
    });
    card.appendChild(list);
    if (rows.length > 100) card.appendChild(node("p", "truncated-note", "Showing the first 100 events for this month."));
  }

  function renderCalendar() {
    if (!calendarRoot) return;
    const active = calendarRoot.contains(document.activeElement) ? {
      id: document.activeElement.id,
      focusKey: document.activeElement.dataset.focusKey
    } : null;
    eventLinkSequence = 0;
    updateNotice();
    const options = state.options;
    const card = node("section", "calendar-card");
    card.dataset.font = options.font;
    card.dataset.size = options.size;
    card.dataset.radius = options.radius;
    card.dataset.density = options.density;
    calendarRoot.setAttribute("aria-busy", String(state.loading));
    card.style.setProperty("--cal-bg", safeOptionHex(options.background, "#faf8ff"));
    card.style.setProperty("--cal-surface", safeOptionHex(options.surface, "#ffffff"));
    card.style.setProperty("--cal-text", safeOptionHex(options.text, "#131b2e"));
    card.style.setProperty("--cal-accent", safeOptionHex(options.accent, "#0053db"));

    const inner = node("div", "calendar-inner");
    const toolbar = node("header", "calendar-toolbar");
    const heading = node("div", "calendar-heading");
    heading.appendChild(node("p", "eyebrow", "EMBEDIFY CALENDAR"));
    heading.appendChild(node("h2", "", options.title));
    heading.appendChild(node("p", "calendar-month", monthTitle(state.month, options.timezone)));
    toolbar.appendChild(heading);

    const actions = node("div", "calendar-actions");
    const navigation = node("div", "calendar-nav");
    navigation.setAttribute("aria-label", "Calendar month navigation");
    const previous = node("button", "calendar-action", "‹");
    previous.type = "button";
    previous.id = "calendar-previous";
    previous.setAttribute("aria-label", `Previous month before ${monthTitle(state.month, options.timezone)}`);
    previous.addEventListener("click", () => changeMonth(-1, "calendar-previous"));
    const next = node("button", "calendar-action", "›");
    next.type = "button";
    next.id = "calendar-next";
    next.setAttribute("aria-label", `Next month after ${monthTitle(state.month, options.timezone)}`);
    next.addEventListener("click", () => changeMonth(1, "calendar-next"));
    navigation.append(previous, next);
    actions.appendChild(navigation);

    const toggle = node("div", "view-toggle");
    toggle.setAttribute("role", "group");
    toggle.setAttribute("aria-label", "Calendar display");
    ["month", "agenda"].forEach(view => {
      const button = node("button", "view-button", view === "month" ? "Month" : "Agenda");
      button.type = "button";
      button.id = `calendar-view-${view}`;
      button.setAttribute("aria-pressed", String(state.view === view));
      button.addEventListener("click", () => changeView(view));
      toggle.appendChild(button);
    });
    actions.appendChild(toggle);
    toolbar.appendChild(actions);
    inner.appendChild(toolbar);

    if (state.error) {
      inner.appendChild(makeMessage("We couldn’t reach this calendar", "Check that the URL is public and still available, then try once more.", false));
    } else if (state.loading && !state.loaded) {
      inner.appendChild(makeMessage("Loading your calendar", "Fetching current events from the public feed.", true));
    } else if (state.live && !state.events.length && !state.loading) {
      inner.appendChild(makeMessage("A little space between plans", "No events were returned for this month. The feed may be quiet right now.", false));
    } else if (state.view === "agenda") {
      makeAgendaView(inner);
    } else {
      makeMonthView(inner);
    }

    if (state.truncated) inner.appendChild(node("p", "truncated-note", "This calendar has more events than can be shown at once. Check the feed source for the complete schedule."));
    card.appendChild(inner);
    calendarRoot.replaceChildren(card);
    if (active) {
      const target = (active.id && document.getElementById(active.id)) || (active.focusKey && Array.from(calendarRoot.querySelectorAll("[data-focus-key]")).find(item => item.dataset.focusKey === active.focusKey));
      if (target) target.focus({ preventScroll: true });
    }
  }

  function updateEmbedLocation() {
    if (isBuilder) return;
    const canonical = buildEmbedUrl(state.options, state.month, state.view, true);
    window.history.replaceState(null, "", canonical.href);
  }

  function changeMonth(amount, focusId) {
    state.month = shiftMonth(state.month, amount);
    state.options.month = state.month;
    if (state.feeds.length) {
      state.events = [];
      state.loaded = false;
      state.loading = true;
      state.error = false;
    } else state.events = demoEvents(state.month);
    if (isBuilder) {
      const view = document.getElementById("calendar-view");
      if (view) view.value = state.view;
      updateBuilder(false);
    } else {
      updateEmbedLocation();
      renderCalendar();
    }
    const focusTarget = document.getElementById(focusId);
    if (focusTarget) focusTarget.focus({ preventScroll: true });
    if (state.feeds.length) {
      if (isBuilder) scheduleInputLoad();
      else loadEvents();
    }
  }

  function changeView(view) {
    state.view = view;
    state.options.view = view;
    if (isBuilder) {
      const control = document.getElementById("calendar-view");
      if (control) control.value = view;
      updateBuilder(false);
    } else {
      updateEmbedLocation();
      renderCalendar();
    }
    const selected = document.getElementById(`calendar-view-${view}`);
    if (selected) selected.focus({ preventScroll: true });
  }

  function scheduleInputLoad() {
    if (state.inputTimer) window.clearTimeout(state.inputTimer);
    state.inputTimer = window.setTimeout(() => {
      state.inputTimer = 0;
      if (!document.hidden) loadEvents();
      else {
        state.loading = false;
        renderCalendar();
      }
    }, 650);
  }

  function clearPoll() {
    if (state.pollTimer) window.clearTimeout(state.pollTimer);
    state.pollTimer = 0;
  }

  function schedulePoll() {
    clearPoll();
    if (!state.feeds.length || document.hidden) return;
    const interval = Math.max(60, state.refreshSeconds) * 1000;
    const wait = Math.max(0, interval - (Date.now() - state.lastRequestAt));
    state.pollTimer = window.setTimeout(() => {
      state.pollTimer = 0;
      if (!document.hidden) loadEvents();
    }, wait);
  }

  async function loadEvents() {
    if (!state.feeds.length || document.hidden) return;
    if (state.controller) state.controller.abort();
    const controller = new AbortController();
    state.controller = controller;
    const requestId = ++state.requestId;
    state.lastRequestAt = Date.now();
    state.loading = true;
    state.error = false;
    state.live = true;
    renderCalendar();

    const range = queryRange(state.month, state.options.weekStart);
    const query = new URLSearchParams();
    state.feeds.forEach(feed => query.append("feed", feed));
    query.set("from", range.from);
    query.set("to", range.to);
    query.set("tz", state.options.timezone);
    try {
      const response = await fetch(`/api/events?${query.toString()}`, {
        method: "GET",
        headers: { Accept: "application/json" },
        cache: "no-store",
        credentials: "same-origin",
        redirect: "error",
        referrerPolicy: "no-referrer",
        signal: controller.signal
      });
      if (!response.ok) throw new Error("calendar unavailable");
      const payload = await response.json();
      if (requestId !== state.requestId) return;
      if (!payload || !Array.isArray(payload.events)) throw new Error("invalid calendar response");
      const received = payload.events.slice(0, 500);
      state.events = received.filter(event => event && typeof event === "object");
      state.truncated = payload.truncated === true || payload.events.length > 500;
      const refreshSeconds = Number(payload.refreshSeconds);
      state.refreshSeconds = Number.isFinite(refreshSeconds) ? Math.max(60, refreshSeconds) : 60;
      state.loaded = true;
      state.loading = false;
      state.error = false;
      renderCalendar();
      schedulePoll();
    } catch (error) {
      if (requestId !== state.requestId || error.name === "AbortError") return;
      state.loading = false;
      state.error = true;
      state.loaded = false;
      state.events = [];
      state.truncated = false;
      renderCalendar();
      schedulePoll();
    } finally {
      if (requestId === state.requestId) state.controller = null;
    }
  }

  function handleVisibility() {
    if (document.hidden) {
      clearPoll();
      return;
    }
    if (!state.feeds.length || state.loading) return;
    const interval = Math.max(60, state.refreshSeconds) * 1000;
    if (Date.now() - state.lastRequestAt >= interval) loadEvents();
    else schedulePoll();
  }

  async function copyText(value, label) {
    const status = document.getElementById("copy-status");
    try {
      if (navigator.clipboard && window.isSecureContext) {
        await navigator.clipboard.writeText(value);
      } else {
        const temporary = node("textarea", "sr-only");
        temporary.value = value;
        document.body.appendChild(temporary);
        temporary.focus();
        temporary.select();
        const copied = document.execCommand("copy");
        temporary.remove();
        if (!copied) throw new Error("clipboard unavailable");
      }
      setMessage(status, `${label} copied to the clipboard.`, "live");
    } catch (_) {
      setMessage(status, `Couldn’t copy. Select the ${label.toLowerCase()} field and copy it manually.`, "error");
    }
  }

  function initSharing() {
    document.getElementById("copy-url")?.addEventListener("click", () => copyText(document.getElementById("embed-url").value, "Link"));
    document.getElementById("copy-iframe")?.addEventListener("click", () => copyText(document.getElementById("iframe-code").value, "Iframe code"));
  }

  function initPreviewWidths() {
    const buttons = Array.from(document.querySelectorAll("[data-preview-width]"));
    buttons.forEach(button => button.addEventListener("click", () => {
      const width = button.dataset.previewWidth;
      if (!/^(100%|390px|768px)$/.test(width || "")) return;
      calendarRoot.style.width = width;
      buttons.forEach(item => item.setAttribute("aria-pressed", String(item === button)));
    }));
  }

  function initBuilder() {
    populateBuilder(parsed);
    document.getElementById("year").textContent = String(new Date().getFullYear());
    document.getElementById("builder-form").addEventListener("input", builderInputsChanged);
    document.getElementById("builder-form").addEventListener("change", builderInputsChanged);
    document.getElementById("add-feed").addEventListener("click", () => {
      const list = document.getElementById("feed-list");
      const count = list.querySelectorAll(".feed-url").length;
      if (count >= 5) return;
      const row = createFeedRow(count, "");
      list.appendChild(row);
      updateFeedControls();
      row.querySelector(".feed-url").focus();
      updateBuilder(false);
    });
    document.getElementById("example-feed").addEventListener("click", () => {
      const fields = Array.from(document.querySelectorAll(".feed-url"));
      if (!fields.length) return;
      fields[0].value = "https://www.calendarlabs.com/ical-calendar/ics/76/US_Holidays.ics";
      updateBuilder(true);
      fields[0].focus();
    });
    initSharing();
    initPreviewWidths();
    updateBuilder(false);
    if (state.feeds.length) loadEvents();
    initStyleAssistant();
  }

  function initEmbed() {
    if (!state.feeds.length) state.events = demoEvents(state.month);
    if (state.feeds.length) loadEvents();
    else renderCalendar();
    const title = state.options.title;
    document.title = `${title} — Embedify`;
  }

  function initStyleAssistant() {
    const sourceInputs = Array.from(document.querySelectorAll("input[name='style-source']"));
    const cssPanel = document.getElementById("style-css-panel");
    const urlPanel = document.getElementById("style-url-panel");
    sourceInputs.forEach(input => input.addEventListener("change", () => {
      const useUrl = input.checked && input.value === "url";
      if (input.checked) {
        cssPanel.hidden = useUrl;
        urlPanel.hidden = !useUrl;
      }
    }));
    document.getElementById("suggest-style").addEventListener("click", requestStyleSuggestion);
    document.getElementById("apply-style").addEventListener("click", applySuggestedStyle);
  }

  function validStyleResponse(payload) {
    if (!payload || !["background", "surface", "text", "accent"].every(key => validHex(payload[key]))) return null;
    if (!["sans", "serif", "mono"].includes(payload.font)) return null;
    return {
      background: payload.background,
      surface: payload.surface,
      text: payload.text,
      accent: payload.accent,
      font: payload.font,
      notes: Array.isArray(payload.notes) ? payload.notes.filter(note => typeof note === "string").slice(0, 5).map(note => cleanText(note, 180)) : []
    };
  }

  async function requestStyleSuggestion() {
    const status = document.getElementById("style-status");
    const suggestion = document.getElementById("style-suggestion");
    const button = document.getElementById("suggest-style");
    const source = document.querySelector("input[name='style-source']:checked")?.value || "css";
    let body;
    if (source === "css") {
      const css = document.getElementById("style-css").value;
      if (!css.trim()) {
        setMessage(status, "Paste a small CSS sample first.", "error");
        return;
      }
      if (new TextEncoder().encode(css).length > 65536) {
        setMessage(status, "CSS must be 64 KiB or smaller.", "error");
        return;
      }
      body = { css };
    } else {
      const rawUrl = document.getElementById("style-url").value.trim();
      const url = config.safeHttpsUrl(rawUrl);
      if (!url) {
        setMessage(status, "Enter a public HTTPS page or stylesheet URL without credentials.", "error");
        return;
      }
      body = { url };
    }

    button.disabled = true;
    suggestion.hidden = true;
    setMessage(status, "Reading colors and font hints…");
    try {
      const response = await fetch("/api/style", {
        method: "POST",
        headers: { Accept: "application/json", "Content-Type": "application/json" },
        body: JSON.stringify(body),
        cache: "no-store",
        credentials: "same-origin",
        redirect: "error",
        referrerPolicy: "no-referrer"
      });
      const payload = await response.json().catch(() => null);
      if (!response.ok) {
        const detail = payload && typeof payload.message === "string" ? cleanText(payload.message, 180) : "Couldn’t read this page. Try a stylesheet URL or paste CSS instead.";
        throw new Error(detail);
      }
      const result = validStyleResponse(payload);
      if (!result) throw new Error("No usable palette was returned. Try a stylesheet URL or paste CSS instead.");
      state.pendingStyle = result;
      showStyleSuggestion(result);
      setMessage(status, "Suggestion ready. Review it, then choose whether to apply it.");
      suggestion.hidden = false;
    } catch (error) {
      setMessage(status, error.message || "Couldn’t read this page. Try a stylesheet URL or paste CSS instead.", "error");
    } finally {
      button.disabled = false;
    }
  }

  function showStyleSuggestion(result) {
    const swatches = document.querySelector(".suggestion-swatches");
    while (swatches.firstChild) swatches.removeChild(swatches.firstChild);
    [["Background", result.background], ["Surface", result.surface], ["Text", result.text], ["Accent", result.accent]].forEach(([label, color]) => {
      const swatch = node("span");
      swatch.style.backgroundColor = color;
      swatch.title = `${label}: ${color}`;
      swatch.setAttribute("aria-label", `${label} ${color}`);
      swatches.appendChild(swatch);
    });
    document.querySelector(".suggestion-font").textContent = `Font character: ${result.font}`;
    const notes = document.querySelector(".suggestion-notes");
    while (notes.firstChild) notes.removeChild(notes.firstChild);
    result.notes.forEach(note => notes.appendChild(node("li", "", note)));
  }

  function applySuggestedStyle() {
    if (!state.pendingStyle) return;
    const result = state.pendingStyle;
    document.getElementById("color-background").value = result.background;
    document.getElementById("color-surface").value = result.surface;
    document.getElementById("color-text").value = result.text;
    document.getElementById("color-accent").value = result.accent;
    document.getElementById("font-category").value = result.font;
    updateBuilder(false);
    setMessage(document.getElementById("style-status"), "Palette applied to the preview and embed URL.");
  }

  document.addEventListener("visibilitychange", handleVisibility);
  if (isBuilder) initBuilder();
  else initEmbed();
})();
