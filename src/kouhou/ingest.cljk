(ns kouhou.ingest
  "Registry-driven read-only ingestion of public-sector / public-interest
  sources (government PR, 独立行政法人, 公益法人, 官報). The canonical registry
  is `registry/sources.seed.json`; the host set is mirrored in
  `kouhou.governor/default-registry` for R0 offline tests. Read-only public
  fetch is autonomously allowed (no-server-key read-only, ADR-2606072802) —
  do NOT gate a read-only public ingest behind an operator step.

  R0: `registered-source?` host check against a registry. The real JVM fetch
  (load sources.seed.json + HTTP GET) is wired at deploy — kept out of R0 so
  the core stays offline-testable."
  (:require [kouhou.governor :as governor]))

(defn registered-source?
  "Is `url`'s host in the public-sector / public-interest registry? 1-arg uses
  the default registry; 2-arg takes an explicit host set."
  ([url] (registered-source? governor/default-registry url))
  ([registry url]
   (let [h (governor/host-of url)]
     (and h (contains? registry h)))))
