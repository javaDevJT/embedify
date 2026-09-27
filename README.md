# Embedify

Calendar feeds, made to fit your website. A single Spring Boot 4 service serves a URL builder, live preview, and embeddable calendars. No accounts, database, or file uploads.

- [Design, API, and work record](docs/design.md)
- [Deployment and operations](docs/deployment.md)

Repository stays private until its owner approves publication. Public hosting is intended at `embedify.javadevjt.tech`.

## Run locally

Use Java 25 or newer and Maven 3.9+:

```sh
mvn spring-boot:run
```

Open `http://localhost:8080`. Run `mvn verify` for the backend checks. The frontend has no package installation or build step.

## Make an embed

1. Paste one to five **public HTTPS iCalendar (.ics) feed URLs** into the builder. `webcal://` links are converted to HTTPS. Files, calendar credentials, and private network URLs are not accepted.
2. Choose month or agenda view, timezone, week start, title, colors, and typography. Preview the result at desktop or narrow width.
3. Optionally paste CSS text or a public website/stylesheet URL into the style assistant. Review its suggested colors and apply them to the preview.
4. Copy the embed URL or iframe markup into your website. The calendar refreshes while visible, at most once per minute.

**Anyone who can view an embed can read its feed URLs.** Do not use private or secret calendar links. Calendar data is held in a bounded memory cache; no accounts or calendar database are created. HTTPS secures transport, not the privacy of a link placed on a public webpage.

URL parameters are encoded by the builder; use `URLSearchParams` if generating links yourself. Repeat `feed` for multiple calendars. The embed route is `/embed`.

| Parameter | Meaning | Default |
|---|---|---|
| `feed` | Public HTTPS calendar URL; repeat up to five times | Demo until supplied |
| `title` | Display title | Shared calendar |
| `view` | `month` or `agenda` | month |
| `month` | Initial month in `YYYY-MM` format | Current month |
| `tz` | IANA timezone, for example `America/Detroit` | Browser timezone |
| `weekStart` | First day of the week | sun |
| `background`, `surface`, `text`, `accent` | Hex colors | Stitch slate and blue palette |
| `font` | `serif`, `sans`, or `mono` | sans |
| `size`, `radius`, `density` | Presets selected in the builder | standard, soft, comfortable |

Style suggestions are deterministic and local to this service. They extract literal CSS colors, simple custom properties, and a local font category. They do not execute page scripts, run arbitrary CSS, download fonts, or use an external AI service. A page's computed design may therefore differ from the suggestion.

## Operations

The service shares feed pulls through a bounded 60-second cache, coalesces concurrent misses, and applies request, upstream, size, and recurrence limits. It has no persistent volume requirement. Restarting clears the cache. `GET /healthz` is the minimal health endpoint.

The container uses a jlink runtime, an unprivileged user, a read-only root filesystem, and dropped capabilities. See [deployment](docs/deployment.md) for exact build, registry, TrueNAS, and proxy settings.

Support development at [Buy Me a Coffee](https://buymeacoffee.com/javadevjt).
