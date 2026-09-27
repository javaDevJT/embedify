# Small-ad options for Embedify

Research checked September 27, 2026. This is a proposal; no ads or tracking have been enabled.

## Recommendation

Use an optional, clearly labeled **direct-sponsor footer** inside calendars. Keep AdSense outside the calendar iframe, on an approved top-level content page if the owner wants that separate channel.

| Option | Fit | Constraint |
| --- | --- | --- |
| AdSense inside the calendar iframe | Not viable under the published rule | Google's AdSense policy FAQ explicitly prohibits ads in a frame within another page. |
| AdSense on an owned top-level page | Possible after review | Site ownership, Ready status, meaningful publisher content, applicable consent, and Google resource permissions are required. The builder's suitability is uncertain because it is primarily an interactive tool. |
| Direct sponsor inside the embed | Best fit for a very small footer | Requires a sponsor relationship and a clear customer-facing choice; avoid third-party ad scripts and tracking. |
| Existing Buy Me a Coffee support link | Already available on the builder | Voluntary support, not advertising. |

## Other platforms for calendar embeds

Checked September 27, 2026. An ad tag that creates an iframe is not, by itself, permission to distribute that ad inside a calendar widget on arbitrary customer domains.

| Platform | What is established | Embedify decision |
| --- | --- | --- |
| Adsterra | Publisher terms clause 4.7 permits an ad tag in an iframe only with prior written consent and restricts it to approved publisher sites. Small banner formats include 320×50. | A possible automatic-network option only after written approval of the exact distributed-calendar model and host-domain requirements. |
| A-ADS | Offers iframe units and small banner examples. One official guide says cross-domain reuse is not prohibited, but limits unique impressions to the assigned domain; its separate FAQ says each website needs its own unit. | Conditional lead. The conflicting domain rules and unclear nested-frame attribution need provider clarification before integration. |
| AdButler | Documents iframe and HTML-only iframe zone tags. Its setup documentation says a zone is empty until a campaign and creative are assigned. | Can deliver direct-sponsor campaigns through iframe tags; it is not automatic advertiser demand. Confirm any separately connected network's rules. |
| Revive Adserver | Documents an iframe invocation tag for banner/button/rectangle zones and delivery of campaigns linked to each zone. | Another direct-campaign delivery option. Its tag support does not authorize another network's ads or provide automatic advertiser demand. |

For automated inventory, the clearest next investigation is Adsterra's written-consent route. Describe an Embedify-hosted calendar iframe on customer-controlled domains, a single 320×50 banner, and the required host registration, referrer, consent, and content-review behavior. This is a proposed approval request, not approval already obtained; no providers have been contacted.

For the smallest design, retain the proposed 32–36 px direct-sponsor footer. A plain operator-configured sponsor link needs no additional ad-server service. Add campaign-management software only when sponsor rotation/reporting makes it useful. Neither network above has been verified as a blanket-approved, automatic-fill solution for arbitrary customer embeds.

Sources:

- [Adsterra publisher terms, clause 4.7](https://adsterra.com/publishers-terms-managed/)
- [Adsterra banner formats](https://adsterra.com/blog/how-banner-ads-make-money/)
- [A-ADS placement guide](https://help.aads.com/en/article/how-to-place-an-ad-unit-code-correctly-12n1ti5/)
- [A-ADS multiple-websites FAQ](https://help.aads.com/en/article/can-i-use-the-same-ad-unit-code-for-multiple-websites-eqnhjo/)
- [A-ADS iframe formats](https://help.aads.com/en/article/accepted-ad-formats-at-aads-1g5jiec/)
- [A-ADS embedding examples](https://help.aads.com/en/article/how-to-embed-ad-units-9frqcl/)
- [AdButler tag types](https://www.adbutler.com/help/article/types-of-zone-tag)
- [AdButler zone setup and empty-zone behavior](https://www.adbutler.com/help/article/creating-zones)
- [Revive zone invocation tags](https://revive-adserver.atlassian.net/wiki/spaces/DOCS/pages/721005)

## Calendar footer concept

A 32–36 px row beneath the calendar, separated by a light border:

`Sponsored · Example sponsor                                      Learn more ↗`

Use the calendar's existing surface, text, font, and accent colors. Keep the sponsorship label legible and visually separate from dates, events, navigation, and the copy controls. Reserve the row's height so loading never shifts events. It should never cover calendar content or float over the page.

A direct sponsor can use plain text and a single operator-configured HTTPS link. Do not accept sponsor scripts, HTML, or ad destinations from embed URL parameters. Do not transmit calendar URLs or event data to an advertiser. Start without ad cookies, tracking pixels, animation, or personalization. Give calendar owners an explicit sponsorship choice.

This is a product recommendation, not an approved Google integration. A direct sponsor would have to be arranged before a real paid placement exists.

## If using AdSense on another page

Google's fixed-size display-unit rules allow a minimum width of 120 px and minimum height of 50 px. Those are dimensions, not a guarantee of ad inventory; Google warns that some small sizes may lack inventory. A 320×50 responsive banner is a more conventional small candidate, but it would still need approval and a suitable placement on a top-level page.

The page/domain must be owned and verified, added to the publisher's Sites list, reviewed, and Ready before ads run. A customer's separate site approval cannot be assumed to authorize Embedify's iframe. Google also excludes screens without publisher content, with low-value content, or used primarily for navigation/actions. Applying that restriction to the builder is a risk assessment, not a determination from Google; a substantive help or editorial page is a stronger candidate.

Serving AdSense to the EEA, UK, or Switzerland requires a Google-certified consent management platform integrated with TCF. AdSense would add third-party requests and require changes to the current strict content security policy. Its privacy/disclosure requirements also apply. Revenue cannot be estimated from ad dimensions alone; traffic, geography, viewability, inventory, consent, and blockers all matter.

## Official sources

- [AdSense policy FAQs — Part 1: Ad implementation, iframe question](https://support.google.com/adsense/answer/3394713?hl=en)
- [Guidelines for fixed-sized display ad units](https://support.google.com/adsense/answer/9185043?hl=en)
- [Connect your site to AdSense](https://support.google.com/adsense/answer/12131223?hl=en)
- [Check the status of your AdSense sites](https://support.google.com/adsense/answer/9131547?hl=en)
- [Show ads on a new site](https://support.google.com/adsense/answer/91205?hl=en)
- [Google Publisher Policies](https://support.google.com/publisherpolicies/answer/10502938?hl=en)
- [Consent management requirements](https://support.google.com/adsense/answer/13554116?hl=en)
