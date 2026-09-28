# Public repository readiness audit

Audit date: September 28, 2026. Application/worktree snapshot: `0416eff`. The temporary remote mirror also includes main revision `6cff659`; its only change is two GitHub Actions build-cache settings, which were reviewed and do not resolve the findings below. The worktree was not reset or updated to that revision.

Publication status: the owner authorized publication on September 28, 2026. The repository is public, its public-state protections are verified, and the GHCR package remains private. The earlier hardening release is deployed; verification of a fresh build under the public settings is pending.

The original audit found no confirmed application exploit or real credential leak in the completed checks. Public visibility itself does not introduce the dependency issues; the hosted application is already public.

The initial audit changed documentation only. The owner subsequently authorized both recommended remediations and then repository publication. The verified settings and release evidence are recorded below. This work does not authorize a history rewrite or live attack.

## Public settings verified — September 28, 2026

- Actions were paused before changing visibility. After public protections were configured and read back, Actions were re-enabled with full commit-SHA pinning required and an allowlist limited to the five action repositories already used by CI.
- Fork workflow approval is `all_external_contributors`. The existing `pull_request_target` workflow separately rejects external head repositories before allocating TrueNAS runners. Approval policy alone is not that workflow's trust boundary.
- `main` requires one approving PR review, dismisses stale reviews, requires conversations to be resolved, and requires the up-to-date `Build and test` check from GitHub Actions app ID `15368`. Force pushes and branch deletion are disabled. The sole repository writer is the owner, whose administrator bypass remains available for owner-authored releases.
- External-fork jobs are intentionally skipped. A successful skipped check is not evidence that fork code was tested; a maintainer must inspect changes and deliberately choose a safe testing path before merging.
- Default workflow tokens remain read-only, and Actions cannot approve pull requests. Package-write permission remains limited to the publishing job.
- Private vulnerability reporting, dependency alerts, secret scanning, and secret-scanning push protection are enabled. [SECURITY.md](../SECURITY.md) documents the private reporting route.
- The repository is public; the GHCR package remains private. The hosted-use license and deployment restrictions are unchanged.

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

## Other findings and hardening

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
