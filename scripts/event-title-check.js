// Run with playwright-cli run-code --filename scripts/event-title-check.js
// Open the target site first. Calendar responses below are synthetic and stay in the browser.
async (page) => {
  const origin = page.url().split("/").slice(0, 3).join("/");
  const check = (condition, message) => { if (!condition) throw new Error(message); };
  const linkedTitle = ("Quarterly planning: " + "Long event names must remain inside their calendar cell. ".repeat(3)).trim();
  const plainTitle = '<img src=x onerror="window.tooltipInjected=true">' + "Unbroken".repeat(22);
  const fixture = {
    events: [
      { id: "linked", title: linkedTitle, start: "2026-09-05", end: "2026-09-06", allDay: true, url: "https://example.org/event" },
      { id: "plain", title: plainTitle, start: "2026-09-06", end: "2026-09-07", allDay: true },
      { id: "short", title: "OK", start: "2026-09-05", end: "2026-09-06", allDay: true },
      { id: "medium", title: "Team planning", start: "2026-09-06", end: "2026-09-07", allDay: true, url: "https://example.org/team" }
    ], refreshSeconds: 60, truncated: false
  };
  const route = "**/api/events?**";
  const respond = request => request.fulfill({ json: fixture });
  const errors = [];
  const onError = error => errors.push(error.message);
  page.on("pageerror", onError);
  await page.route(route, respond);
  const params = { feed: "https://example.org/tooltip-test.ics", month: "2026-09", tz: "UTC", surface: "#151b2b", text: "#f5f7ff", accent: "#80c7ff" };
  const query = () => Object.entries(params).map(([key, value]) => `${key}=${encodeURIComponent(value)}`).join("&");
  const tooltip = page.locator("#event-title-tooltip");
  const chip = title => page.locator(".event-chip").and(page.getByText(title, { exact: true }));
  const closed = () => tooltip.waitFor({ state: "hidden" });
  const contained = async () => {
    const result = await page.locator(".event-chip").evaluateAll(labels => labels.every(label => {
      const cell = label.closest(".day-cell").getBoundingClientRect();
      const rect = label.getBoundingClientRect();
      return rect.left >= cell.left && rect.right <= cell.right && label.clientWidth > 0;
    }));
    check(result, "An event title escaped its day cell");
  };
  const tooltipInViewport = async () => check(await tooltip.evaluate(element => {
    const rect = element.getBoundingClientRect();
    return rect.left >= 0 && rect.top >= 0 && rect.right <= document.documentElement.clientWidth && rect.bottom <= innerHeight;
  }), "Tooltip escaped the viewport");
  try {
    await page.setViewportSize({ width: 960, height: 700 });
    await page.goto(`${origin}/embed?${query()}`);
    await page.locator(".event-chip").first().waitFor();
    await contained();
    check(await page.locator("#preview-notice").textContent() === "", "Loaded-feed banner is still visible");
    check(await chip(linkedTitle).getAttribute("title") === null, "Native tooltip duplicates the themed tooltip");
    await chip(linkedTitle).hover();
    await tooltip.waitFor({ state: "visible" });
    check(await tooltip.textContent() === linkedTitle, "Tooltip lost part of the full title");
    const colors = await tooltip.evaluate(element => {
      const style = getComputedStyle(element);
      return [style.backgroundColor, style.color, style.borderTopColor];
    });
    check(JSON.stringify(colors) === JSON.stringify(["rgb(21, 27, 43)", "rgb(245, 247, 255)", "rgb(128, 199, 255)"]), "Tooltip does not use the chosen palette");
    await tooltipInViewport();
    await tooltip.hover();
    await page.waitForTimeout(200);
    check(await tooltip.isVisible(), "Tooltip disappeared while hovering it");
    await page.mouse.move(1, 1);
    await closed();
    await chip("OK").hover();
    check(!await tooltip.isVisible(), "A fitting title opened a tooltip");
    await chip("Team planning").focus();
    check(!await tooltip.isVisible(), "A fitting focused title opened a tooltip");
    await page.setViewportSize({ width: 320, height: 700 });
    await tooltip.waitFor({ state: "visible" });
    await contained();
    await tooltipInViewport();
    await page.keyboard.press("Escape");
    await closed();
    await page.setViewportSize({ width: 390, height: 700 });
    check(!await tooltip.isVisible(), "Resize reopened an Escape-dismissed tooltip");
    await chip(plainTitle).focus();
    await tooltip.waitFor({ state: "visible" });
    check(await tooltip.textContent() === plainTitle, "Unlinked title was truncated or interpreted as HTML");
    check(await tooltip.locator("img").count() === 0, "Feed text became HTML");
    check(await chip(plainTitle).getAttribute("role") === "group", "Unlinked focus target lacks named semantics");
    await page.keyboard.press("Escape");
    await closed();
    check(await chip(plainTitle).evaluate(element => element === document.activeElement), "Escape moved focus");
    check(await chip(plainTitle).getAttribute("aria-describedby") === null, "Dismissed tooltip left a stale description");
    await page.mouse.move(1, 1);
    await chip(linkedTitle).hover();
    await tooltip.waitFor({ state: "visible" });
    await page.locator("#calendar-next").click();
    await closed();
    params.surface = "#ffffff";
    params.text = "#172b4d";
    params.accent = "#0053db";
    await page.setViewportSize({ width: 1100, height: 800 });
    await page.goto(`${origin}/?${query()}`);
    await chip(linkedTitle).waitFor();
    await contained();
    check(await page.locator("#preview-notice").textContent() === "", "Builder still has the loaded-feed banner");
    await chip(linkedTitle).hover();
    await tooltip.waitFor({ state: "visible" });
    check(await tooltip.evaluate(element => getComputedStyle(element).backgroundColor) === "rgb(255, 255, 255)", "Builder tooltip has the wrong palette");
    await tooltipInViewport();
    await page.keyboard.press("Escape");
    await page.locator("#calendar-view-agenda").click();
    check(await page.locator(".agenda-title").filter({ hasText: plainTitle }).count() === 1, "Agenda lost the full event title");
    check(errors.length === 0, `Browser errors: ${errors.join("; ")}`);
    return { passed: true, checks: "cell bounds, overflow-only tooltips, palettes, keyboard, resize, cleanup, and hidden success banner" };
  } finally {
    await page.goto(origin);
    await page.unroute(route, respond);
    page.off("pageerror", onError);
  }
}
