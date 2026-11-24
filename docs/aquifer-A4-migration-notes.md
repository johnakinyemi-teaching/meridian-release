# Migration notes: team Aquifer (numerical model, water balance), A4

**Scope.** These notes cover the change on `team/aquifer` relative to `main` (`dc61bc1`):

| Ticket | Files | Behaviour change |
|---|---|---|
| MRD-118 | `model/cropmod.f` | cropmod now **rejects** a deck whose DOYs are not strictly ascending (out of order or duplicate). It exits with code **5** and writes no `MERIDIAN.OUT`. In-order decks are byte-identical to before. |
| MRD-204 (cross-boundary, Canopy must approve) | `model/growth.f90`, `model/cropmod.f`, `model/waterbal.f` (comment only) | The `GROWTH` **signature** gains an `RDEPTH` argument. **The water-stress formula is not actually changed** (see §3). Output is byte-identical. |
| tests | `model/tests/mrd118/*`, `tools/characterize_waterbal.py` | New test script and fixtures. Not yet run by CI. |

Everything below was checked by building and running both `origin/main` and `origin/team/aquifer` with gfortran 13.3, not by reading the diff alone.

---

## 1. The oracle: nothing moves on the default path

| Check | `main` | `team/aquifer` |
|---|---|---|
| ingest | accepted=1044 rejected=0 suspect=10 | same |
| GUELPH 2025 | 5.951 t/ha, cropmod V2.3 | same |
| BRANTFRD / CAMBRIDG / KITCHNER / MILTON / WATERLOO 2025 | 5.841 / 6.014 / 6.138 / 5.928 / 5.737 | same |
| GUELPH deck re-run with rooting depth 600, 1000 or 1500 mm | `MERIDIAN.OUT` md5 per depth | byte-identical to `main` at every depth |

**Note for whoever reads the CI log after merge.** `team/aquifer` branched before Archive's MRD-246 landed, so a local run of the branch alone has no `output QC:` warning lines. After the merge they appear. They come from Archive's change, not ours. The branch merges into current `main` without conflicts.

---

## 2. MRD-118: what changes for other teams

### 2.1 New contract: cropmod's exit codes

| Exit code | Meaning | `MERIDIAN.OUT` |
|---|---|---|
| 0 | normal run | written |
| **5** (new) | deck rejected: a DOY is `<=` the one before it (out of order or duplicate) | **not written** |

* **The diagnostic goes to stdout, not stderr.** For a rejected deck, cropmod writes this to **stdout**:
  `CROPMOD: deck out of order at record 3 DOY 11 follows DOY 12`
  Its **stderr** holds only `STOP 5` (gfortran's runtime prints that).
* The check runs while the deck is being read, before `WATBAL`/`GROWTH` and before `MERIDIAN.OUT` is opened. A rejected run therefore leaves **no** output file. It does not leave a partial or empty one.
* What is rejected: any DOY `<=` the previous DOY, including duplicates. Gaps (10, 12, 13) are still accepted, as before. The `-1` terminator and the `MAXDAY = 366` cap behave as they did.
* DOY values themselves are not range-checked (for example `0` or `400` is still read). That was never part of MRD-118.

### 2.2 Nightshift (orchestration: `orchestrator.py`, `parallel.py`, `qc.py`)

* **On the nightly path, nothing changes in practice.** `Store.readings()` returns `ORDER BY doy`. The `reading` primary key `(stnid, year, doy)` makes duplicate DOYs impossible. `qc._check_monotonic` already raises `ORDER`/`DUPDOY` as blocking ERRORs before the deck is written. Exit 5 is a second line of defence that this path does not reach.
* **If exit 5 is ever reached** (a spec that did not come from the store, or input QC switched off), `NightshiftRun.__enter__` and the `parallel.py` worker raise:
  `ModelRunError("cropmod exited 5: STOP 5")`
  They include `proc.stderr` and **not** `proc.stdout`, so the error message loses the useful text (which record, which DOY). Under the default `stop_on_error=False`, that station-year is skipped and the batch continues.
  *Suggested fix, not part of our PR:* include `proc.stdout.strip()[-200:]` in the `ModelRunError` message. Aquifer could alternatively write the message to unit 0 (stderr). See §5.
* **A comment is now out of date.** The `qc._check_monotonic` docstring and the `qc.py` module header say "cropmod assumes this and does not check (MRD-118)". cropmod now checks. The QC check should stay: it rejects the deck earlier and gives a better message. Only the wording is wrong. Nightshift owns that file, so we are not editing it.
* **MRD-201 (Nightshift owns it; Aquifer and Canopy approve).** The fixed filenames stay the same. If you ever reuse a working directory instead of a fresh scratch dir, a rejected run leaves the *previous* run's `MERIDIAN.OUT` in place. **Check the exit code. Do not test whether `MERIDIAN.OUT` exists.** `ScratchRun` already uses a fresh directory, so today's code is safe.

### 2.3 Archive (parse and store)

* The `MERIDIAN.OUT` format (`docs/OUTFMT.txt`, `docs/CONTRACTS.md`) is **unchanged**. The banner still reads `cropmod V2.3`.
* Previously a scrambled deck produced a full-looking but wrong forecast, which could be persisted and reported. Now it produces **no forecast**: the run raises before `_absorb`, so nothing is parsed, saved or QC-scanned. The `suspect=10` count does not change.

### 2.4 Relay, Watchtower, Frontier, Almanac (API, consoles, reporting)

* No change to any stored row, column, route or response for in-order data.
* The only observable difference: a station-year whose deck is rejected has **no** `forecast` row, where before it would have had a wrong one. The API, consoles and reports already have to handle a missing station-year, so no code change is expected.

### 2.5 Anyone who runs cropmod directly (Canopy's harness, tools, hand-made decks)

* Decks must have strictly ascending DOYs. Canopy's `model/fixtures/MERIDIAN.DAT` already does. We ran Canopy's `make -C model test` on a merge of `main + team/canopy + team/aquifer` and it passes, as does our `run_mrd118.sh`.
* `tools/characterize_waterbal.py` builds an ascending deck (DOY 1, 2, …), so it is unaffected.

---

## 3. MRD-204: read this before approving it, Canopy

### 3.1 What actually changed: the interface only

`GROWTH` gained an argument, inserted **between `AWC` and `XLAT`**:

```fortran
! before
subroutine growth(n, idoy, tmax, tmin, srad, sw, awc,         xlat, biom, xlai, yield)
! after
subroutine growth(n, idoy, tmax, tmin, srad, sw, awc, rdepth, xlat, biom, xlai, yield)
```

* `growth` is an **external procedure** (not in a module), and `cropmod.f` is fixed-form F77 with no explicit interface. **The compiler will not catch a call with the old argument list.** Such a call would compile and silently bind `xlat` to `rdepth`, `biom` to `xlat`, and so on.
* We checked `main`, `team/aquifer`, `team/canopy`, `canopy/MRD-241-trace-validation` and `canopy/MRD-239-nutrient-conversion`. `cropmod.f` is the **only** caller on any of them. **Any new caller, including in Canopy's harness or `trace_replay.f90`, must pass 12 arguments in the new order.**

### 3.2 What did *not* change: the formula

On `team/aquifer`, `growth.f90` lines 63–68 read:

```fortran
stress = min(sw(i) / swmax, 1.0)          ! new line (MRD-204)
if (awc > 0.0) then
   stress = min(sw(i) / (awc * 100.0), 1.0)   ! old line, still here: overwrites the new one
else
   stress = 1.0
end if
```

The new assignment is a **dead store**. The old `SW/(AWC*100)` formula still decides the stress fraction. That is why output is byte-identical at rooting depths of 600, 1000 and 1500 mm, even though MRD-204 is defined as the case "rooting depth is not 1000 mm".

**However, the comments in `growth.f90` and `waterbal.f` now say "MRD-204 resolved".** That is not true, and the next reader will trust it.

### 3.3 What would happen if the formula were made live

We removed the old block locally and re-ran:

| Deck | Yield, old formula (current) | Yield, `SW/(AWC*RDEPTH)` live |
|---|---|---|
| GUELPH 2025 (real deck, rooting depth 1100 mm) | **5.951** | **0.974** |
| GUELPH at 1000 mm | 5.951 | 0.962 |
| GUELPH at 600 mm | 5.951 | 0.948 |
| GUELPH at 1500 mm | 5.951 | 1.039 |

With `AWC = 0.15`, the old denominator is 15 mm. Profile SW is about 80–100 mm, so the old fraction is always above 1 and growth is never water-stressed. The new denominator is about 165 mm, so the crop is stressed almost every day. **The real MRD-204 fix is not a refactor: it moves the oracle by roughly 84 %.** It would need the oracle value changed by the instructional team, the change stated in the PR, and Canopy's written approval of the new number.

### 3.4 What we recommend, given the Nov 25 freeze

1. **Do not claim MRD-204 is fixed.** Either:
   **(a)** keep the `RDEPTH` plumbing, delete the dead `stress = min(sw(i)/swmax, 1.0)` line, and change both comments to say *"GROWTH now receives RDEPTH; the stress formula is deliberately unchanged pending MRD-231 because unifying it moves the oracle (5.951 → 0.974)"*; or
   **(b)** revert the `growth.f90`/`cropmod.f` change entirely and keep MRD-204 open in `ISSUES.md`.
   Option (a) keeps the interface work and is honest. Either way, **Canopy has to approve it in writing on the PR**, because `growth.f90` is their file.
2. Restore the trailing newline in `growth.f90`, and the deleted `2021-02-11 K. Osei …` history line, or move that history into the dragons register. Removing it is an unrelated change in a file another team owns.

---

## 4. Tests: what CI runs and what it does not

* `model/tests/mrd118/run_mrd118.sh` is **not run by CI.** `.github/workflows/meridian.yml` on `main` has no model test step. Canopy's branch adds `make -C model test`, but that target does not include our script. Until one of those changes, the PR must say so and give the command:

  ```sh
  make all && sh model/tests/mrd118/run_mrd118.sh     # expects 6 PASS lines, exit 0
  ```

  The script is committed as mode `100644`, so run it with `sh`, not `./`.
* `tools/characterize_waterbal.py` writes its fixture to `model/characterization/`, and **that directory is in `.gitignore`.** The WATBAL characterization is therefore not committed and cannot act as a safety net for anyone else. Either commit the JSON, or do not describe it as a pinned fixture.
* If Canopy merges first and Aquifer wants the MRD-118 test in CI, the clean route is a `test-mrd118` line in Canopy's `model/Makefile` `test` target. That is Canopy's file, so agree it on the PR.

---

## 5. Contract and register entries this change makes wrong

| Where | What to change | Owner |
|---|---|---|
| `docs/CONTRACTS.md`, model deck section | Add: "daily records strictly ascending in DOY; otherwise cropmod exits 5, prints the reason on stdout, and writes no MERIDIAN.OUT." This is a shared file, so agree it on the PR. | Aquifer drafts; Nightshift/Archive acknowledge |
| `ISSUES.md` MRD-118 | Mark fixed (cropmod rejects; exit 5). | Aquifer |
| `ISSUES.md` MRD-204 | **Leave open** (see §3). | Aquifer + Canopy |
| Dragons register, MRD-118 entry ("cropmod trusts DOY order") | Now: "cropmod rejects non-ascending decks with exit 5. The reason goes to stdout, which the orchestrator's error message does not include." | Aquifer |
| `analytics/meridian/qc.py` comments | "does not check (MRD-118)" is now stale. | Nightshift |
| A2 UML / interface model for `GROWTH` | Add the `rdepth` parameter. | Aquifer (diagram + source beside the code) |

**Optional, small and on our side.** Change `WRITE (*,*)` to `WRITE (0,*)` in `cropmod.f`. gfortran's unit 0 is stderr, so the reason would reach `ModelRunError` without anyone touching the Python. `run_mrd118.sh` merges `2>&1`, so it still passes. Only do this if it can be reviewed before the freeze.

---

## 6. One-paragraph version (for the PR description)

> **Migration (Aquifer, MRD-118 / MRD-204).** cropmod now exits **5** and writes **no** `MERIDIAN.OUT` when a deck's DOYs are not strictly ascending (out of order or duplicate). The reason is printed on **stdout**, and stderr carries only `STOP 5`. In-order decks are byte-identical: the oracle holds at GUELPH 5.951, 1044/0/10, and all six 2025 stations are unchanged. The nightly path cannot reach the new stop, because the store orders by DOY, the primary key forbids duplicates, and blocking input QC rejects ORDER/DUPDOY first. `GROWTH` now takes `RDEPTH` between `AWC` and `XLAT`. It is an external procedure with no interface checking, so any new caller must use the 12-argument order. The water-stress formula is **unchanged**: unifying it with WATBAL would move GUELPH from 5.951 to 0.974 t/ha, so MRD-204 stays open. CI does not run our test; run `make all && sh model/tests/mrd118/run_mrd118.sh`.
