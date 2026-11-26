# ADR 0009 — External Surface checks a domain on the officer's declaration (MVP)

- **Status:** accepted
- **Date:** 2026-11-26
- **Issues:** #168 (review, point 7), #186 (DNS TXT verification, post-MVP)
- **Refs:** `plugins/provider/external-surface` (`MonitoringService.activate`), `plugins/feature/organization`
  (`DomainName`), `workers/job_consumer/src/alinha_job_consumer/net.py`

## Context
The External Surface provider checks the organization's public domain from the internet: DNS records (SPF, DMARC,
DKIM, MTA-STS), the TLS certificate and versions, the HTTPS redirect, security headers and `security.txt`. It runs
once a day and on request. The domain comes from the organization profile, and the extra hosts must be subdomains
of it.

Nothing proves that the organization controls that domain. A responsible person can enter someone else's domain (a
competitor's, a public body's), tick "authorized" and the platform then contacts that third party every day. The
review of #168 asked for a proof of ownership (DNS TXT or `.well-known`) before activation, or at least a recorded
decision.

## Decision
For the MVP, the checks start on the **Designated Officer's declaration**, with these limits:

- Activation needs an explicit `authorized: true`. The declaration, the domain and the hosts are written to the
  audit trail in the same transaction, so we can tell who authorized which domain and when.
- The profile refuses domains shared by many organizations (webmail, free hosting such as `github.io`,
  `wixsite.com`), and the extra hosts must be subdomains of the profile's domain (at most 10, plus `www`).
- The checks are what any visitor does: a few DNS lookups, TLS handshakes and HTTPS `GET`s per host. No port scans,
  no crawling, no login attempts. `net.py` only connects to public addresses, does not follow redirects, caps the
  body size and enforces a deadline per exchange.
- Load is bounded: one scheduled check a day and on-demand checks no closer than 10 minutes apart per tenant.

**Proof of control is deferred to #186**: a DNS TXT record (`_alinha-verification.<domain>`) checked before
activation and on every scheduled check. It needs no request to the organization's own servers, so it is preferred
over `.well-known`.

## Consequences
- (+) The MVP demo and pilots can activate monitoring without a DNS change at the client, which many SMEs cannot do
  quickly.
- (+) Misuse is traceable to a named user through the audit trail, and its reach is small (a visit's worth of
  traffic per day).
- (−) Until #186, the platform can be pointed at a domain the organization does not own. The terms of use must make
  the officer responsible for the declaration.
- (−) When #186 lands, already active surfaces need a grace period and then pause until verified.

## Alternatives considered
- **DNS TXT verification now:** the right end state, but it adds a contract, a worker lookup, UI and migration work
  to the MVP, and asks every pilot client for a DNS change before the first result.
- **`.well-known` file on the website:** needs a request to the organization's server and a deploy on their side;
  does not cover domains with no website.
- **Consultant approval of each domain:** puts a manual step in every activation and does not prove control either.
