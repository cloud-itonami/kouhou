# kouhou 広報 — Maturity

**Stage: R1** (2026-07-19, this change) — was R0 scaffold (ADR-2607022210,
2026-07-02). Public-interest / public-sector information curator + social
poster: containment (`organizer`) + independent censor (`PublicInfoGovernor`)
+ append-only ledger, publishing autonomously to app-aozora
(`com.etzhayyim.apps.kouhou.publicBriefing`).

| Dimension | State |
|---|---|
| StateGraph | ✅ `kouhou.operation` — intake → advise → govern → decide → commit\|hold, langgraph-clj |
| Governor | ✅ `PublicInfoGovernor` — 5 HARD gates (hold, never override) + 1 SOFT (tag, still publish) |
| Store | ✅ `MemStore` ‖ `DatomicStore` (langchain.db `:db-api`), contract-tested |
| Identity | ✅ self-sovereign Ed25519 `did:key` (`kouhou.cacao`), depth-1 self-minted CACAO |
| Publisher | ✅ real app-aozora `createSession`→`createRecord` (`kouhou.aozora`), live-verified (identify-live) |
| Advisor (curation) | ✅ deterministic `mock-advisor` (default) ‖ real-LLM `llm-advisor` on Murakumo, live-verified (`kouhou.deploy`) |
| Registry | ✅ `registry/sources.seed.json` — 8 entries, world-scope (ADR-2607197800), 7 spot-verified live |
| **Live fetch** | ✅ **NEW (this change)** — `kouhou.live-fetch`: real HTTP GET + RSS 2.0 / RSS 1.0 (RDF) / Atom 1.0 |
| **Live-ingest entrypoint** | ✅ **NEW (this change)** — `kouhou.run-live-ingest`, `clojure -M:live-ingest` |
| Tests | ✅ `clojure -M:dev:test`: **38 tests / 97 assertions / 0 failures** (2026-07-19) |
| Lint | ✅ `clojure -M:lint`: 0 errors |

## What "R1" means here, honestly

Before this change, `README.md`'s own status line read: "real LLM summarizer
… + real registry fetch + real aozora Publisher wired at deploy" — naming
**real registry fetch** as the one still-missing piece (the LLM summarizer and
the aozora Publisher were already real and already live-verified via
`kouhou.deploy`). This change closes exactly that gap:

- **`src/kouhou/live_fetch.cljk`** — a minimal, dependency-free RSS 2.0 /
  RSS 1.0 (RDF) / Atom 1.0 scanner (ported/adapted from kawaraban's
  already-landed `live_fetch.cljc`, ADR-2607110200 — same "inherit the gate,
  don't reinvent it" discipline: parsed items flow through
  `kouhou.ingest/registered-source?` UNCHANGED). Covers all three feed
  formats the world-scope registry actually uses: RSS 2.0 (US/DE/FR/EU/UN),
  RSS 1.0/RDF (JP 政府広報オンライン), Atom (GB gov.uk).
- **`src/kouhou/run_live_ingest.cljk`** — `clojure -M:live-ingest`, gated
  behind `KOUHOU_ALLOW_LIVE_INGEST` (default OFF — **code-complete but
  off-by-default**, same honesty ladder as kawaraban's own R0→R1: the code
  exists and is tested, but a real network fetch + real publish only happens
  when an operator explicitly flips the env var). 3000ms inter-source delay
  (same order of magnitude as kawaraban's measured aozora-graph
  read-latency mitigation) + per-source error isolation (one source's HTTP
  failure never aborts the run).

## What is verified vs not (2026-07-19)

- **Verified live, this session**: all 7 `"verified": true` registry sources
  return real feed content over a direct HTTP fetch (US whitehouse.gov RSS,
  UK gov.uk Atom, DE bundesregierung.de RSS, FR elysee.fr RSS, EU Commission
  presscorner RSS, UN press.un.org RSS, JP gov-online.go.jp RDF) — see
  `registry/sources.seed.json`'s per-entry `comment` for what was observed.
  `kouhou.live-fetch/parse-feed` is unit-tested against fixture text matching
  each of these three formats (RSS 2.0 / RDF / Atom), not live network calls
  in the test suite itself.
- **Honestly unverified**: `kanpou` (官報, Japan's official gazette) —
  `"verified": false` in the registry; no machine-readable feed was found, so
  `verified-sources` excludes it from any live-ingest run. This is not a
  code gap, it is an upstream-source gap (no feed exists at the paths
  checked) — do not silently mark it verified without an actual fetch
  confirming a real feed.
- **Not yet exercised end-to-end against the real PDS by this change alone**:
  whether a real fetched item from each of the 7 verified sources actually
  clears the PublicInfoGovernor (provenance-clean article link on the SAME
  registered host as the feed, no catastrophe-veto/commercial-content hit) is
  a property of the real feed CONTENT on the day the run happens, not
  something this code can guarantee in advance — a governor HOLD on a live
  item is the governor working correctly, not a bug. See the founder/Council
  go-live run's own report (superproject task log) for what actually
  happened on first real invocation.

## R1 → R2 (future)

- Real-LLM curation (`kouhou.advisor/llm-advisor`, already proven live via
  `kouhou.deploy`) is not the DEFAULT advisor in `kouhou.run-live-ingest` —
  swapping it in is a deliberate, separate follow-up (needs
  ADR-2607173100's `murakumo-main` alias resolution wired into the
  live-ingest entrypoint, not the direct-Ollama-endpoint pattern
  `kouhou.deploy` currently uses).
- `kanpou`/官報 needs either a confirmed real feed/API or a page-scrape
  ingest path before it can be added to a live-ingest run.
- A high-water-mark (kawaraban's `data/ingest/last-seen.edn` pattern,
  ADR-2607110200 addendum 2) is not yet implemented here — a re-run of
  `clojure -M:live-ingest` will re-propose each source's then-current latest
  item every time, not just genuinely new items since the last run. Because
  `kouhou.operation`'s `:rkey` is the source-id (deterministic), a repeat
  commit for the same source safely UPDATES that source's one aozora record
  rather than creating a duplicate — so this is a "reruns don't advance
  usefully until the feed does" limitation, not a flood/duplication risk.
