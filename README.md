# com-etzhayyim-kouhou (広報)

**kouhou** — a public-interest / public-sector information curator + social
poster. It ingests news/info from **registered** public-sector and public-
interest sources (government PR, 独立行政法人, 公益法人, 官報), organizes each into a
faithful summary with provenance, and **publishes a social briefing to
app-aozora** (collection `com.etzhayyim.apps.kouhou.publicBriefing`).

**DID**: `did:web:etzhayyim.github.io:com-etzhayyim-kouhou` (depth-1 self-minted
`did:key` carried in the published record; RAD identity journal at
`orgs/etzhayyim/root/80-data/kotoba-rad/kouhou.identity.journal.edn`).
**Namespace**: `com.etzhayyim.apps.kouhou.*`.
**ADR**: ADR-2607022210 (superproject, R0 scaffold) + `docs/adr/0001-architecture.md` (正本).
**Status**: **R1** (2026-07-19) — real HTTP fetch (`src/kouhou/live_fetch.cljc`,
RSS 2.0 / RSS 1.0 (RDF) / Atom 1.0) + a non-interactive live-ingest entrypoint
(`src/kouhou/run_live_ingest.clj`, `clojure -M:live-ingest`) now exist,
mirroring kawaraban's already-landed R0→R1 live-fetch (ADR-2607110200). Gated
behind `KOUHOU_ALLOW_LIVE_INGEST` (default OFF — code-complete but off by
default until an operator sets the env var). Uses the framework default
`mock-advisor` for curation by default (deterministic faithful-excerpt
summarizer) + the real `kouhou.aozora` Publisher; a real-LLM organizer
(`langchain.model` on Murakumo) also already exists and is proven live
(`kouhou.deploy`) but is a separate, not-engaged-by-default seam. Source
registry (`registry/sources.seed.json`) expanded 2026-07-19 from a
Japan-only fictional placeholder set to a small, spot-verified **world-scope**
set (see "Source registry" below) — the live-ingest path loads this file at
runtime as the real whitelist (via context `:registry`), not the offline
`default-registry` fixture (see "Important limitation" below for what that
fixture is still for).
**First-touch channel**: app-aozora (`com.atproto.repo.createRecord`).
**Cross-actor**: distinct from **kawaraban** (general news mirror, no curation/
posting), **kataribe** (religious press), **danjo** (gov-data discrepancy
oversight). kouhou briefings are candidate inputs to **tashikame** (fact-check)
and **danjo** (discrepancy).

## Overview

kouhou is the etzhayyim organism's public-interest wire. Per the autonomous-
publication doctrine (ADR-2606281500, 種をまく), it publishes briefings
**autonomously by default** — no per-post operator/Council prior restraint. The
safety rails are the actor's OWN seed: a self-`did:key` + a revocable member
CACAO leash (off-switch) + a Rider §2 catastrophe-veto scan + a **source
whitelist** (only registered public-sector/public-interest sources).

Containment + independent governor + append-only ledger: the intelligence node
(`organizer`) is sealed into one graph node and returns a *proposal only*; an
independent **PublicInfoGovernor** censors it; only `:commit` writes the Store +
publishes. Publication is SPEECH, not ACTUATION. Aggregate-first: one run =
one briefing (no flood).

## StateGraph (one source digest = one run)

```
intake → advise(organizer) → govern(PublicInfoGovernor) → decide → commit | hold
```

No `interrupt-before` (autonomous). The PublicInfoGovernor's HARD violations
are the only thing that withholds publication.

| node | role |
|---|---|
| `:advise` | `organizer` (contained) — faithful summary + provenance + domain/tags. Proposal only. |
| `:govern` | `PublicInfoGovernor` — independent censor (separate system). |
| `:commit` | writes briefing to Store + append-only ledger; publishes to app-aozora when phase allows. |
| `:hold`   | records the rejection as a hold; no SSoT mutation, no publish. |

## PublicInfoGovernor gates

**HARD → HOLD (never publish):**
- `:no-actuation` — proposal `:effect ≠ :assessment`.
- `:no-provenance` — a briefing with a blank source-url.
- `:source-not-in-registry` — source host not in the public-sector/public-interest whitelist.
- `:commercial-content` — ad / sponsored markers (off-mission).
- `:catastrophe-veto` — Rider §2 catastrophe-veto scan hit.

**SOFT → publish with a transparency tag (not a block):**
- `:low-confidence` — overall confidence below floor; the briefing still publishes, tagged.

## Source registry

The canonical whitelist is `registry/sources.seed.json` (`source-id` / `name` /
`host` / `kind` / `domain` / `country` / `url` / `read-only` / `verified` /
`comment`).

**2026-07-19 (ADR-2607197800): generalized from Japan-only to world-scope.**
The original 3 entries were all fictional `*.example.*` placeholders (not
real hosts). They have been replaced with 8 entries spanning Japan (2),
the United States, the United Kingdom, Germany, France, the European
Commission, and the United Nations — each spot-checked with a direct live
HTTP fetch on 2026-07-19 and marked `"verified": true/false` honestly:

- `verified: true` (7 of 8) — a direct fetch on 2026-07-19 returned HTTP 200
  with real RSS/Atom/RDF feed content (not an HTML placeholder or error page).
  See each entry's `comment` for what was observed.
- `verified: false` (1 of 8, `kanpou` / 官報) — the official gazette site
  itself is real and live, but no machine-readable feed could be found at any
  of the common paths tried; it needs either a confirmed feed URL or a
  different ingest mechanism before it can be treated as live.

This is a **small, best-effort, spot-verified set — not a claim of exhaustive
world coverage.** Most of the world's governments and public bodies are not
yet represented here (that broader "collect everything" job belongs to
kawaraban's outlet allowlist and, longer-term, mikurabe per ADR-2607197800);
this registry only needed to stop being Japan-only fiction. Gaps should be
filled incrementally and honestly flagged (`"verified": false` + a comment
explaining what's unconfirmed), never silently guessed. The old
`koueki-hojin` (公益法人協会) fictional entry/category was dropped rather than
re-guessed with a fake host — a real 公益法人-adjacent source can be added
later following the same honesty discipline.

**Important limitation, updated 2026-07-19 (R0→R1 live-fetch): this file IS now
what a live run enforces, but `kouhou.governor/default-registry` intentionally
still is not.** `kouhou.governor/default-registry` (in `src/kouhou/governor.cljc`)
still contains only the OLD fictional `.example.` hosts, and stays that way ON
PURPOSE — it is the R0 offline-test fixture the existing test suite
(`governor-contract-test` et al.) is written against, and changing it would be
a silent breaking change to tests that have nothing to do with the real
registry. The REAL runtime whitelist now comes from
`kouhou.live-fetch/load-registry` (`registry/sources.seed.json`, real JVM file
I/O + injected JSON reader) → `kouhou.live-fetch/registry->host-set`, passed
into the actor graph via context `:registry` by
`kouhou.run-live-ingest`/`run-source!` — the SAME `context :registry` override
seam `kouhou.governor/check` already supported. `kouhou.ingest/registered-source?`
(unchanged) remains the host-check function either way; only which host set
gets passed to it differs between an offline test and a live run.

## Phase rollout

| Phase | label | publish? |
|---|---|---|
| 0 | observe | no — governor-clean briefings recorded only (shadow) |
| 1 | autonomous-publish (**default**, 種をまく) | yes |

## Storage — canonical EDN in git, feed bytes in the annex

A live-ingest run persists to three planes. Nothing is kept in two of them for
the same reason; each answers a question the others cannot.

| plane | path | what it is | where the bytes live |
|---|---|---|---|
| briefings | `data/briefings/<UTC-day>.edn` | every curated briefing, appended | git |
| ledger | `data/ledger/<UTC-day>.edn` | every `:commit` / `:hold` decision fact | git |
| corpus receipts | `data/corpus/<UTC-day>.edn` | one per fetch: feed URL, item, disposition, `{:path :sha256 :bytes}` of the archived feed | git |
| raw feeds | `raw/<UTC-day>/<source-id>.xml` | the fetched document itself | git-annex → `s3.kotobase.net` |

Line-delimited **canonical** EDN (`kouhou.canonical`): maps are key-sorted
before printing, so equal values have equal bytes — otherwise `git diff` shows
reordered lines and the same record digests two ways.

**Append-only**, and that is not a contradiction of the superproject's
「文書は最新状態のみを表す」 rule — that rule names measurement and event
series as its standing exception, and a ledger of past decisions is exactly
that. `store/briefing` still reads the latest per source; the history behind it
is a fold over the shards, not something overwritten.

The **corpus receipt is the join** between the two planes: given a briefing you
can name the exact feed it was derived from and verify its digest; given a feed
you can find every briefing that came out of it. Without the digest the pairing
would be by filename, which proves nothing about content.

Feed bytes go to git-annex rather than git because 201 sources × one feed each
is ~10 MB per pass — a year of daily passes would make cloning the *actor*
require downloading its *corpus*. Small semantic EDN stays an ordinary git blob
where it can be reviewed. This is the superproject's `large-binary-datalad`
rule; the annex remote is `kotobase` (`s3.kotobase.net`, bucket
`cloud-itonami-kouhou`).

```bash
clojure -M:verify-corpus                # every receipt vs the file on disk
git annex copy raw/ --to kotobase --jobs 1   # jobs=1: one head per bucket
datalad drop raw/                       # local bytes go, pointers stay
datalad get  raw/2026-08-11             # fetch a day back
```

`verify-corpus` answers **identity** (is this file still what the receipt
describes) and treats dropped bytes as absent, not failed — that is what the
annex is for. **Custody** — does the object plane still hold them — is a
different question with a different command:

```bash
git annex find --in kotobase raw/ | wc -l    # what is actually recorded there
```

**Do not read `git annex fsck --from kotobase` output as a custody count.** A
key the location log does not place in the remote is printed `ok` because
there was nothing to check, so a run can print 48 `ok` lines while only 38
objects are there. Measured 2026-08-11: the first `copy` left 10 of 48 behind,
fsck then printed 48 `ok`, and `--in kotobase` was the only command that said
38. A second `copy` finished them. Count what is in the remote; do not count
lines that did not fail.

## Injected seams (each a swap, core unchanged)

- **Store** — `EdnStore` (**default for live-ingest**, durable) ‖ `MemStore` (tests) ‖ `DatomicStore` (langchain.db `:db-api`) ‖ kotoba-server pod.
- **Advisor** — `mock-advisor` (deterministic) ‖ real LLM on `langchain.model` / Murakumo.
- **Publisher** — `MockPublisher` ‖ real app-aozora createRecord (`kouhou.aozora`).
- **Phase** — 0 observe → 1 autonomous-publish.
- **Registry** — public-sector host whitelist (context :registry override).

## Run

```bash
clojure -M:lint          # clj-kondo, errors fail
clojure -M:dev:test      # cognitect test-runner (canonical)
clojure -M:dev:run       # offline demo (one registered + one unregistered source)

# real fetch + real publish over every "verified": true registry/sources.seed.json
# source — refuses (no network call) unless the gate is set. Founder/Council explicit
# go-live instruction required to set this (ADR-2607110200 precedent).
KOUHOU_ALLOW_LIVE_INGEST=1 clojure -M:live-ingest

# ingest + persist WITHOUT publishing — the two capabilities are separable.
# Fetching a registered government feed onto our own disk is read-only and
# reversible; publishing to a shared PDS is neither. This is the form an
# unattended corpus run should take.
KOUHOU_ALLOW_LIVE_INGEST=1 KOUHOU_PUBLISH=0 clojure -M:live-ingest
```

| env | default | effect |
|---|---|---|
| `KOUHOU_ALLOW_LIVE_INGEST` | unset (**off**) | the network gate; nothing is fetched without it |
| `KOUHOU_PUBLISH` | `1` | `0` = store only, no app-aozora write |
| `KOUHOU_DATA_DIR` / `KOUHOU_RAW_DIR` | `data` / `raw` | where the planes above are written |
| `KOUHOU_MAX_SOURCES` | unset (all) | cap the pass — for a smoke run against real feeds |

## Related files

- `docs/adr/0001-architecture.md` — design 正本.
- `../../../90-docs/adr/2607022210-com-etzhayyim-kouhou-public-info-actor-r0.md` — superproject ADR.
- `CLAUDE.md` — repo invariants / conventions.
- `MATURITY.md` — R0→R1 status, what's verified vs not, honesty ladder.
