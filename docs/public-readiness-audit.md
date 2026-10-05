# Public repository readiness audit

## CI storage qualification — October 5, 2026

Run `37339194224` at `9e49be3` passed application checks but failed publishing with BuildKit `native` snapshotter `no space left on device`. Its valid report measured a 1,715,294,208-byte peak against a 1,717,989,376-byte reservation. Several build steps were cached; this was not an uncached build. The vulnerability scan was not reached. The proposed 1.6 GiB class is therefore too small and is not qualified for release.

Diagnostic run `37341716311` at `0c1811b` also failed with ENOSPC before scanning on the existing 8 GiB class. Its valid storage report peaked at 8,559,665,152 of 8,589,934,592 bytes. The next measurement uses the existing 32 GiB class to obtain the full build peak. Both the unchanged High/Critical vulnerability gate and the greater-than-80% storage utilization gate remain enforced. Failed capped peaks cannot determine the final reservation; qualification must use successful-build measurements and sufficient headroom.

Run `37342617084` at `265a209` completed the image build and passed the full-image vulnerability gate: zero High/Critical matches, three Medium and two Low, zero ignored matches. The valid storage report measured 9,436,807,168 bytes (8.79 GiB), so the 32 GiB utilization gate correctly failed at 27.46%. A 10 GiB class is the next qualification candidate (87.89% utilization at that measured peak). Live-to-candidate Compose comparison confirms only Embedify classes change from `[1.6,8,32]` to `[8,10,32]`; controller source and all other settings match. The guarded deployment waits for unrelated active work rather than interrupting it.

Scheduled run `37345662949` independently measured 9,464,360,960 bytes (8.81 GiB) on the same source and 32 GiB native runner. Both measurements imported cached Maven stages from GHA with the local cache unavailable. They support a 10 GiB candidate, but do not yet qualify a fully cached successful-job export or an uncached build. The owner requested waiting for a natural idle fleet window before deploying the runner change.

The natural idle window allowed TrueNAS job `96` to deploy only the reviewed Embedify class replacement. Post-deployment verification passed for all 26 repository listeners, rootless operation, idle resources, and credential permissions; controller source remains `73389ef1bc312c56`. The shared console helper now waits for successful terminal echo suppression before sending a script; `bun scripts/check-console-transport.mjs` in the runner repository checks both services with long input, nonzero exit status, and no echoed script content. The 10 GiB CI run is the next release gate.

Run `37359981617` passed at 9,436,712,960 / 10,737,418,240 bytes (87.89%), with zero High/Critical and zero ignored findings. Its sequential repeat `37361154825` reused the complete local layer cache and peaked at only 1,166,667,776 bytes (10.87%), failing the unchanged utilization floor. A single fixed reservation cannot serve both footprints. Publishing now rebuilds container layers without cache import/export, while backend dependency caching remains enabled. A 32 GiB diagnostic measures the complete fresh native build before selecting the final publishing class; a failed diagnostic image cannot be released.

## CI failure review — October 4, 2026

Fix acceptance: retain the High/Critical gate without ignored findings; preserve jlink, HTTPS trust, UID 10001, and container restrictions; qualify a supported smaller BuildKit reservation; publish and deploy only the passing immutable image; verify runtime identity, served assets, and the synthetic embed browser regression.

Fix ownership: primary owns runtime selection, repository edits, integration, and release. `/root/storage_fix_qualification` owns a read-only reservation investigation used by the workflow decision. Base revision: `40131c2`. The October 4 native spawn catalog lists one callable Luna model. Worker request: `gpt-6-luna`, maximum supported effort `max`; backend model and priority are unexposed. Native completion releases capacity. Independent review follows the concrete patch.

Reviewed current main `40131c2617435bee3cacbd90b86d9382b7196ac6` and failed run `37240145502`. The four recent commits change the workflow, scanner action, and CI policy check; they do not change the application, Dockerfile, or Maven dependencies. The public builder, embed, license, and both health endpoints returned HTTP 200. TrueNAS reports the previously deployed image `sha256:1526d6a67d1d3bf481f517b7beb0a15dc33ca8e069688a0e88070b74f0c3ad3e` as `RUNNING`.

### 1. The vulnerability gate is correctly blocking the current runtime image

Backend/UI verification passed. Syft generated the image SBOM; Grype returned exit 2 because its `--fail-on high` policy found **15 High matches across nine CVEs and seven Debian packages**. No Critical matches appeared in the blocking set. The retained report is artifact `11316993184`, `container-security-publish-0-build-1`.

| Packages | Blocking matches | Reported fix state |
| --- | ---: | --- |
| `libssl3t64` (`3.5.7-1~deb13u2`) | 4 | Fixed in `3.5.7-1~deb13u3` |
| `libc6` (`2.41-12+deb13u4`) | 2 | Won't-fix |
| `gcc-14-base`, `libgcc-s1`, `libgomp1`, `libstdc++6` (`14.2.0-19`) | 8 | Not-fixed |
| `zlib1g` (`1:1.3.dfsg+really1.3.1-1+b1`) | 1 | Not-fixed |

The OpenSSL matches are `CVE-2026-54873`, `CVE-2026-84782`, `CVE-2026-84784`, and `CVE-2026-72897`. The other matches are `CVE-2026-5435`, `CVE-2026-19499`, `CVE-2026-95619`, `CVE-2026-102010`, and `CVE-2026-85091`. These are scanner findings, not a demonstration of exploitability through Embedify.

`Dockerfile:52` still pins the Distroless Debian runtime to digest `54df941ed0d06a1bd95ef5e0ce391fd8d9f94b64782dc9a60062727849ee3f97`. Rebuilding the same pinned image daily does not pick up a newer base. Refreshing that pin to a verified patched base is the first remedy, but the other eleven matches have no fix in the retained report. They need mitigation or a deliberate, reviewed exception before this strict policy can pass; lowering severity or silently ignoring unfixed findings would change the requested protection.

### 2. The BuildKit reservation fails the separate utilization policy

`ci.yml:50` requests `truenas-embedify-storage-32g`. The always-run storage check at `ci.yml:124-126` failed independently after the scanner. Artifact `11316838689` contains a valid report with 32 GiB requested/available, a 1,495,375,872-byte peak (about 1.393 GiB, **4.35%** utilization), 3,544 samples, and no measurement error.

The runner helper intentionally fails at or below 80% utilization (`5 × peak <= 4 × requested`). This is an oversized-reservation failure, not an out-of-space condition. At 32 GiB, passing requires a peak above 25.6 GiB. An 8 GiB reservation would also fail at this measured peak.

Right-size the publish reservation from representative cold and warm builds, retaining enough headroom. This single run does not establish a safe quota; at this peak the current rule requires a quota below approximately 1.74 GiB. The internal helper's local predicate and matching runtime message were reviewed, but its deployed source digest was not independently verified.

### Fix qualification

The replacement runtime is digest-pinned `cgr.dev/chainguard/glibc-dynamic` (`82edc253a57efee78d0fb504e11a93b7c74687b1b736110ad3a2a4f3edf632ab`). Grype 0.118.0 with an explicit empty config and the unchanged `--fail-on high` threshold reported zero High/Critical matches for the base and complete clean application image. Latest Distroless Debian 12/13 alternatives still had 17/11 blocking matches. No findings were ignored. The new base retains its Wolfi package metadata; CI continues generating a complete-image Syft SBOM and Grype report.

The clean amd64 build ran as UID 10001 with the production read-only filesystem, dropped capabilities, no-new-privileges, 512 MiB memory limit, and 16 MiB `/tmp`. Health, builder, embed, license, and CalendarLabs HTTPS feed requests succeeded. All three served JavaScript/CSS assets matched source; the existing synthetic event-title browser regression passed. An initial local cloud-backed build contained an empty `app.js`; its browser check failed, so it was rejected and rebuilt from the clean temporary checkout.

The runner proposal is `truenas-embedify-storage-1p6g`, an exact page-rounded quota of 1,717,989,376 bytes. The previous peak would use 87.04%, leaving 222,613,504 bytes. This is a proposal until actual new-base builds qualify it. The runner change adds numeric `1.6` only to Embedify's existing classes; the rendered Compose change is limited to that target payload. Both CI gates remain intact.

Worker handoffs: `/root/storage_fix_qualification` completed read-only; its exact quota/parser and guarded deployment findings were accepted, while primary verified the renderer requires `all`. `/root/runtime_fix_review` completed read-only with no runtime blocker. Primary verified Docker dependency updates are already configured, superseding the review's claim that only Actions updates exist. Native library paths exercised by startup/HTTPS and the browser regression pass; `jdeps` is not a full ELF audit. Both workers were accepted and released through native completion. The primary remains responsible for CI and deployment proof.

### Release behavior and checks

The build uploads a candidate digest to private GHCR so it can be scanned. Release SHA tags are only created by the later publish step, which did not run after either failure. Production was not updated by these failed CI runs.

Existing CI trust-policy checks and shell syntax validation passed locally. The latest completed run's backend/UI job also passed. No private calendar feed was requested. This review establishes current endpoint availability and the two release blockers; it does not establish all calendar inputs work or that every scanner match is reachable in the running service.

## September 28 publication audit


Audit date: September 28, 2026. Application/worktree snapshot: `0416eff`. The temporary remote mirror also includes main revision `6cff659`; its only change is two GitHub Actions build-cache settings, which were reviewed and do not resolve the findings below. The worktree was not reset or updated to that revision.

Publication status: the owner authorized publication on September 28, 2026. The repository is public, its public-state protections are verified, and the GHCR package remains private. A fresh build passed CI under those settings and is deployed with successful live checks.

The original audit found no confirmed application exploit or real credential leak in the completed checks. Public visibility itself does not introduce the dependency issues; the hosted application is already public.

The initial audit changed documentation only. The owner subsequently authorized both recommended remediations and then repository publication. The verified settings and release evidence are recorded below. This work does not authorize a history rewrite or live attack.

## Public settings verified — September 28, 2026

- Actions were paused before changing visibility. After public protections were configured and read back, Actions were re-enabled with full commit-SHA pinning required and an allowlist limited to the five action repositories already used by CI.
- Fork workflow approval is `all_external_contributors`. The existing `pull_request_target` workflow separately rejects external head repositories before allocating TrueNAS runners. Approval policy alone is not that workflow's trust boundary.
- `main` requires one approving PR review, dismisses stale reviews, requires conversations to be resolved, and requires the up-to-date `Build and test` check from GitHub Actions app ID `15368`. Force pushes and branch deletion are disabled. The only listed human collaborator is the owner, whose administrator bypass remains available for owner-authored releases.
- Pre-existing repository ruleset `24134307` also restricts branch creation/updates, requires linear history and signed commits, and requires PR review. It was preserved unchanged, including its existing administrator and Integration `5102173` bypasses. The integration's display name was not exposed by the available API lookups; this review did not grant it new access. Owner-authorized release pushes used the existing bypass.
- External-fork jobs are intentionally skipped. A successful skipped check is not evidence that fork code was tested; a maintainer must inspect changes and deliberately choose a safe testing path before merging.
- Default workflow tokens remain read-only, and Actions cannot approve pull requests. Package-write permission remains limited to the publishing job.
- Private vulnerability reporting, dependency alerts, secret scanning, and secret-scanning push protection are enabled. [SECURITY.md](../SECURITY.md) documents the private reporting route.
- The repository is public; the GHCR package remains private. The hosted-use license and deployment restrictions are unchanged.

## Public release verified — September 28, 2026

- Release commit `38974d3089623fa5114c72625cd3620f1beef572` passed verification and private image publication in GitHub Actions run `36496999156`, with the public repository settings active.
- Published and deployed image: `ghcr.io/javadevjt/embedify@sha256:1526d6a67d1d3bf481f517b7beb0a15dc33ca8e069688a0e88070b74f0c3ad3e`. TrueNAS image-only update job `212377` succeeded, preserved the remaining live configuration, and was followed by a `RUNNING` inspection on the same digest.
- LAN/public health, builder, embed, and license returned HTTP 200. Served JavaScript/CSS matched source bytes. The live synthetic-feed browser check passed cell bounds, overflow-only tooltips, palette matching, keyboard/resize behavior, narrow iframe bounds, cleanup, and hidden success banners.
- An unauthenticated GitHub API request returned HTTP 200 with `visibility: public` and `private: false`. GHCR remains private. The local ignored image pin was updated to the deployed digest.

## Authorized remediation

- Spring Boot 4.1.1 remained the newest published 4.1 maintenance release when Maven Central was checked on September 28. Its managed Jackson/Tomcat versions still matched the findings, so `pom.xml` now explicitly overrides the Jackson BOM to **3.1.7** and Tomcat to **11.0.26**, retaining the existing framework release. Remove the overrides when Spring Boot manages these fixes.
- The workflow now uses `pull_request_target`, so the job guard comes from the trusted base workflow. A job-level guard excludes every different head repository before allocating the TrueNAS runner. Allowed same-repository PRs explicitly check out their head SHA with checkout credential persistence disabled. Manual verification and publication require `main`; PR events never publish.
- `scripts/ci-policy-check.mjs` reads the actual workflow guards and checks external forks, same-owner forks, same-repository PRs, main pushes/manual runs, and non-main manual runs. It also rejects a return to the PR-controlled `pull_request` trigger. This protects the committed workflow; it is not a claim that repository YAML can prevent every newly introduced workflow.
- Before publication, the private-repository settings disabled fork-PR workflows, write tokens, and secrets/variables. Public protections became configurable after visibility changed, with Actions disabled during that transition. The current verified public settings are listed above. No shared runner architecture was changed.
- Local `mvn -B -ntp verify` passed **34 tests**. CI policy and existing UI checks passed. The rebuilt package contains Jackson Databind 3.1.7 and Tomcat 11.0.26; **44 runtime libraries** were queried against OSV with no matches and no unresolved package coordinates. This does not replace the separate OS/JDK scan that was outside the audit scope.
- `/root/fork_guard_review` approved the implemented guard and regression check. Same-repository branch writers remain trusted to execute CI; the regression script is not an independent authorization boundary. Requested configuration was `gpt-6-luna` / `max` from the current single-Luna native catalog, with backend fields and priority unexposed. Findings accepted; native completion released the worker. Primary owns implementation and release.

## Verified release — September 28, 2026

- Application commit: `19f4b12ff3489c9f32ac3b68cdc0d530e229fe29`. GitHub Actions run `36494103220` passed both verification and private image publication.
- Published and deployed image: `ghcr.io/javadevjt/embedify@sha256:c9b0adb5c2328fe6c743294bd255b8f311529c91bc1d07c75bf6841141ebb9db`, resolved from the exact application commit tag.
- The scoped TrueNAS image update completed as job `212316`; saved configuration equality confirmed that only the image changed. A subsequent inspection reported Embedify `RUNNING` on that digest.
- LAN and public health checks returned HTTP 200. The public builder, embed, and license also returned HTTP 200; served `app.css`, `app.js`, and `config.js` matched the committed source byte for byte.
- The live synthetic-feed browser regression passed cell bounds, overflow-only tooltips, palette matching, keyboard behavior, resize, narrow iframe bounds, cleanup, and the hidden success banner. No private feed was requested.
- Repository and GHCR visibility were read back as private. The original checkout was later synchronized to this release after its cloud-backed files became readable; its prior audit edits are preserved in a named Git stash.

## Scope and evidence

Reviewed the application trust boundaries, tracked files and remote Git history, CI/public-fork execution, container configuration, runtime Maven dependencies, licensing, and public documentation. Live checks were read-only metadata/header requests. No private calendar feed was requested.

## Original release gates (audit snapshot)

### 1. High: explicitly gate public-fork CI before exposing the repository

`.github/workflows/ci.yml:4,16,29` accepts `pull_request` and executes the submitted Maven build and JavaScript on `truenas-embedify`. Those are arbitrary-code execution surfaces. The known one-job/rootless runner architecture is a useful control; this finding does **not** establish a host escape or negate that isolation.

The public-fork approval endpoint returns HTTP 422 while this repository is private, so its future public approval policy cannot currently be verified. Current runner metadata also does not establish outbound LAN isolation. An empty runner list is expected for transient workers and proves neither safety nor failure.

Smallest remedy: prevent external-fork PR jobs from targeting the TrueNAS runner in the workflow itself. If external contribution testing is desired, use a deliberately approved path with verified isolation and restricted network access. Preserve the existing transient architecture. Before enabling public-fork execution, verify approval is required for **all** external contributors. If changing visibility before the setting can be configured, keep Actions disabled through that transition.

Acceptance: a fork-origin PR cannot automatically execute on TrueNAS; intended owner approval and runner/network controls are demonstrated. Also establish the intended public-branch review/check rules. Current protection/ruleset API reads return HTTP 403 under the private-repository plan, so this audit cannot certify them.

### 2. Medium: update dependencies with published security advisories

`pom.xml:4` uses Spring Boot 4.1.1. The local packaged JAR embeds the same POM as the audited source. All 44 nested runtime libraries were checked against OSV, with a follow-up of the relevant upstream advisories.

| Bundled component | Evidence | Reachability assessment and remedy |
| --- | --- | --- |
| `tools.jackson.core:jackson-databind:3.1.5` | OSV matches `GHSA-gx83-3vf8-gh7j`, `GHSA-q4xh-88c3-wmh7`, `GHSA-wjgm-6hv5-3cvf`. | These concern polymorphic `Comparable`, XML datatype, and `Path` deserialization. The app's only request-body DTO is `StyleController.Request(String css, String url)`; no default typing, `@JsonTypeInfo`, custom mapper, or affected DTO type was found. No reachable exploit was established. The advisories identify 3.1.6 / 3.2.2 as fixes for the affected release lines. |
| `org.apache.tomcat.embed:tomcat-embed-core:11.0.24` | OSV matches `GHSA-9xv2-5v5q-p794`, `GHSA-gcx9-497g-6cp6`, `GHSA-h3x4-894j-xpx5`. Apache's current security page also lists later fixes absent from this OSV result. | The three OSV matches concern Tomcat authentication/security-constraint features that this public, account-free app does not configure. Apache additionally lists an HTTP/1.0 reverse-proxy desynchronization issue, CVE-2026-45336, fixed in 11.0.26; this audit did not demonstrate an exploit through NPM. Upgrade to a compatible managed dependency set incorporating at least the applicable 11.0.26 fixes, rather than stopping at the older 11.0.25 fixes. |

Prefer a compatible Spring Boot maintenance update over independent untested library overrides. Re-run the backend checks, UI/browser checks, packaged dependency scan, and normal image/deployment verification. Updating is the recommended release gate even though source review did not establish reachability of the six OSV matches. The advisory count is database- and date-specific, not an exhaustive vulnerability count.

## Repository exposure checks

The current source tree has 54 tracked files. `.env.example` is tracked; `.env` and environment variants are ignored. Ignored environment-file contents were not read.

The original checkout contains cloud-placeholder Git objects, which stalled traversal. A temporary mirror resolved that limitation. The mirror scan covered **7 advertised refs, 27 commits, 113 unique historical blobs, and 829,260 bytes**, with no missing referenced objects. It covered remote branches/history beyond the local checkout's 19 commits. Object hashes were verified before inspecting contents.

The custom signature scan checked private keys, common GitHub/AWS/provider tokens, credential assignments/URLs, and private calendar-feed paths. Eight URL matches were reviewed: they were synthetic `example.org`, `example.com`, and `.example` test fixtures, not real credentials. No private calendar-provider hostname or personal filesystem path was detected by those patterns. Commit author metadata remains part of public Git history.

Infrastructure addresses and deployment details are intentionally present in `.env.example`, `compose.yaml`, `docs/deployment.md`, and `scripts/truenas.mjs`. These are not credentials, but publishing them is a disclosure choice. Replace deployment-specific defaults with examples if that information should remain private. Deleting a current file alone would not remove its history.

Actions history: **18 runs inventoried; logs from 16 runs scanned (1,722,276 characters)** with no matches for the tested token/private-key/private-calendar patterns. The other two runs have zero jobs, explaining their missing job logs. All **15 build-record artifacts** were downloaded and inspected in memory, including decoded nested archives: 148 archive/content nodes and 37,842,144 bytes were checked, with no unsupported containers or matches for the tested token/private-key/private-calendar patterns. No archives were extracted into the worktree.

These were custom bounded signature checks, not gitleaks/trufflehog or a guarantee against every secret format. Deleted/unreachable server-side Git objects and records no longer returned by GitHub were outside scope. A known previously exposed credential must still be rotated regardless of scan results.

## Other findings and hardening (original audit)

- **Low — manual publishing accepts any authorized dispatch ref.** `ci.yml:36` allows `workflow_dispatch` without checking `main`. Only a write-authorized dispatcher can use this; it is not a public-fork privilege bypass. Restrict publication to `main` if that is the intended release policy, or document deliberate branch builds.
- **Low — no HSTS header on the checked public responses.** The builder, embed, license, and health routes returned HTTPS 200, but no `Strict-Transport-Security` header. Consider enabling it at the public proxy. This observation does not establish the absence of any parent-domain/preload protection.
- **Conditional availability hardening — slow request bodies.** `RequestSecurityFilter.java:59-90` holds one of 48 API permits while reading a body synchronously. The documented NPM configuration caps bodies at 512 KiB and sets a 10-second client-body timeout; default request buffering mitigates slow public uploads before forwarding. This is not a confirmed DoS finding. Verify the live proxy retains those controls, or add a connector-level upload deadline if direct exposure is ever introduced.
- **Low — public security-reporting guidance.** Provide a private vulnerability-reporting route before inviting public scrutiny; clarify whether external contributions are accepted under the restricted license.

## Positive controls and checks

- Feed/style fetching validates public HTTPS destinations, validates and pins resolved addresses, rechecks redirects, limits redirects/bytes, and imposes fetch deadlines. Parsed calendars have bounded cache, size, component, recurrence, occurrence, and concurrency controls.
- Frontend event text uses `textContent`, event links are validated, and palette colors are allowlisted. No concrete SSRF, parser exhaustion, error-reflection, or DOM-XSS exploit was found in the reviewed paths.
- The public builder/license/health pages returned `frame-ancestors 'none'` and `X-Frame-Options: DENY`; `/embed` intentionally allows framing. All four checked routes returned `nosniff`, `no-referrer`, and `no-store` controls. Feed URLs are visible capabilities in embeds/API requests; secret feeds remain unsuitable for public embedding.
- Docker/Compose specify a digest-pinned nonroot runtime, UID 10001, read-only root filesystem, dropped capabilities, no-new-privileges, resource limits, and LAN-bound host port. Actions are pinned by commit; the verify token is read-only; package-write is scoped to publishing; provenance and SBOM generation are enabled.
- The existing successful CI run `36464831635` verified the application revision `e7de34a` with 34 backend tests. `0416eff` adds test/documentation evidence only. During this audit, `node scripts/ui-check.mjs` and `bun scripts/truenas.mjs self-test` passed. Prior release browser checks remain recorded in [deployment.md](deployment.md); no new browser regression was needed for this documentation-only audit.

## Licensing and limitations

The README and hosted-use license consistently describe a restricted, source-available project. Hosted use and embedding are permitted; self-hosting/modification/redistribution require separate permission, subject to the stated exceptions. `LICENSE:65-68` and README already acknowledge GitHub's platform rights, including public viewing/forking. Public visibility is therefore compatible with the documented exception, but cannot satisfy an absolute prohibition on all viewing/copying/forking. This is not an open-source release.

No test/build-plugin dependency scan, container OS/JDK vulnerability scan, full accessibility audit, load test, active penetration test, or independent verification of live runner egress/NPM configuration was performed. Actions cache entries were not downloaded; the newly added `mode=max` build cache is distinct from the 15 inspected build-record artifacts. No container vulnerability scanner was installed. The conclusions distinguish source controls and read-only observations from runtime guarantees.

## Sources

- [GitHub: changing repository visibility](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/managing-repository-settings/setting-repository-visibility)
- [GitHub: security hardening for Actions](https://docs.github.com/en/actions/security-for-github-actions/security-guides/security-hardening-for-github-actions)
- [GitHub Terms of Service, section D](https://docs.github.com/en/site-policy/github-terms/github-terms-of-service#d-user-generated-content)
- [Apache Tomcat 11 security advisories](https://tomcat.apache.org/security-11.html)
- Jackson advisories: [polymorphic Comparable](https://github.com/advisories/GHSA-gx83-3vf8-gh7j), [XML datatypes](https://github.com/advisories/GHSA-q4xh-88c3-wmh7), [Path](https://github.com/advisories/GHSA-wjgm-6hv5-3cvf)
- [OSV API](https://google.github.io/osv.dev/api/), queried September 28, 2026 using public package names and versions only.

## Review ownership

- Primary: dependency checks, licensing/documentation, integration checks, and final readiness decision; sole report editor.
- `/root/public_app_audit`: completed; application/security findings accepted with the slow-body concern qualified against documented proxy controls.
- `/root/public_repo_audit`: completed with a blocked history scan; inventory accepted. Primary superseded the incomplete scan using the temporary mirror and performed Actions exposure checks.
- `/root/public_delivery_audit`: completed; CI/container findings accepted with the fork item framed as a validation gate and manual dispatch as authorized-ref hardening.

Workers use the swarm skill's resolved `gpt-6-luna` / `max` configuration. The September 28 native spawn catalog lists one callable Luna model, with `max` its highest supported effort. Model/effort are requested explicitly; actual backend model fields and priority are unexposed. Findings require source evidence and a concrete public-release implication. Native worker completion releases capacity.
