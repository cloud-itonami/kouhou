# com-etzhayyim-kouhou

kouhou (広報) — public-interest / public-sector information curator + social
poster. See `README.md` for the core contract and full-repo `../../../CLAUDE.md`
"Actors" section for the pattern this follows (containment + independent
governor + append-only ledger). Superproject decision record:
`../../../90-docs/adr/2607022210-com-etzhayyim-kouhou-public-info-actor-r0.md`.
Design 正本: `docs/adr/0001-architecture.md`.

## Invariant

kouhou NEVER publishes a briefing the PublicInfoGovernor rejects. Every
published briefing cites a REGISTERED public-sector / public-interest source
(host whitelist; canonical registry `registry/sources.seed.json`). Unregistered-
source / no-provenance / commercial-content / catastrophe-veto / no-actuation
proposals are HELD — recorded as a hold in the append-only ledger, never
published. Only `:commit` writes the Store + publishes; every commit/hold is an
immutable ledger fact. Publication is AUTONOMOUS by default (ADR-2606281500,
種をまく) — no per-post operator/Council prior restraint; the off-switch is the
revocable member CACAO leash. Low-confidence briefings still publish, tagged
`:low-confidence`. Aggregate-first: one run publishes ONE briefing (no flood).

## Conventions

- `.cljc` for anything portable (operation/governor/advisor/publisher/phase/
  store/ingest/sim/**live-fetch**) — `.clj` only for JVM-only I/O (cacao,
  aozora, deploy, **run-live-ingest**).
- `kouhou.cacao` / `kouhou.aozora` are faithful ports of `tsumugu.cacao` /
  `tashikame.aozora` (self-sovereign Ed25519 identity + app-aozora createRecord).
- The actor's own Ed25519 identity lives in `.kouhou/identity.edn` (gitignored)
  — NEVER commit a private key.
- `clojure -M:lint` (clj-kondo, errors fail) / `clojure -M:dev:test`.

## Source registry (2026-07-19, ADR-2607197800)

`registry/sources.seed.json` was generalized from a Japan-only fictional
`*.example.*` placeholder set to a small, spot-verified **world-scope** set
(JP ×2, US, GB, DE, FR, EU, UN). 7 of 8 entries were confirmed live via a
direct HTTP fetch on 2026-07-19 (real RSS/Atom/RDF content, not HTML); the
8th (`kanpou`/官報) is honestly marked `"verified": false` — the site is real
but no feed was found. This is still a small best-effort set, NOT
exhaustive world coverage — see the "Source registry" section of `README.md`
for the full honesty accounting. Do not silently mark a new entry `verified`
without an actual fetch.

## Live fetch (2026-07-19, R0→R1)

`src/kouhou/live_fetch.cljk` (real HTTP GET + RSS 2.0 / RSS 1.0 (RDF) / Atom
1.0 parsing, mirroring kawaraban's already-landed `live_fetch.cljc`,
ADR-2607110200) + `src/kouhou/run_live_ingest.cljk` (non-interactive
entrypoint, `clojure -M:live-ingest`) close the "real registry fetch" gap
`README.md`'s R0 status line used to name as not-yet-wired. Gated behind
`KOUHOU_ALLOW_LIVE_INGEST` (default OFF). **`src/kouhou/governor.cljk`'s
`default-registry` was intentionally NOT synced to `sources.seed.json`** — it
stays the R0 offline-test fixture the existing test suite is written against;
the live path instead loads the real registry at runtime
(`kouhou.live-fetch/load-registry` + `registry->host-set`) and passes it in
via the actor graph's existing context `:registry` override seam. See
`kouhou.run-live-ingest`'s namespace docstring for the full design note
(aggregate-first: one StateGraph run per source per invocation, never one per
feed item).
