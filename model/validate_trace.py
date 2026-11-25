#!/usr/bin/env python3
"""
validate_trace.py - Automated pass/fail validation harness for MERIDIAN_TRACE (MRD-241).

Parses 4-line NMINK records from a reference MERIDIAN_TRACE fixture and validates
either:
  1. A live MERIDIAN.TRC file produced by cropmod (MERIDIAN_NBAL=1 MERIDIAN_TRACE=1), or
  2. A replay/port executable (--replay <cmd>) fed each record's inputs on stdin.

Exits 0 if all records match within floating-point tolerance; exits non-zero on
any structural error, missing/extra record, or numerical mismatch.
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import List, Optional

HEADER_RE = re.compile(
    r"^NMINK\s+DOY=\s*(\d+)\s+TFAC=\s*([-+]?\d+\.\d+)\s+"
    r"WFAC=\s*([-+]?\d+\.\d+)\s+RATE0=\s*([-+]?\d+\.\d+)\s+NLAY=\s*(\d+)\s*$"
)


@dataclass
class TraceRecord:
    doy: int
    tfac: float
    wfac: float
    rate0: float
    nlay: int
    orgn: List[float]
    minout: List[float]
    total: float


def parse_trace(text: str, source_name: str) -> List[TraceRecord]:
    lines = [ln.rstrip("\r\n") for ln in text.splitlines() if ln.strip()]
    if not lines:
        raise ValueError(f"{source_name}: trace is empty")

    if not lines[0].startswith("MERIDIAN NMIN KERNEL TRACE"):
        raise ValueError(f"{source_name}: unexpected header line: {lines[0]!r}")

    body = lines[1:]
    if len(body) % 4 != 0:
        raise ValueError(
            f"{source_name}: expected 4 lines per record after header, got {len(body)} body lines"
        )

    records: List[TraceRecord] = []
    for idx in range(0, len(body), 4):
        l_hdr, l_orgn, l_minout, l_total = body[idx : idx + 4]

        m = HEADER_RE.match(l_hdr)
        if not m:
            raise ValueError(f"{source_name}: malformed NMINK line at record {idx // 4 + 1}: {l_hdr!r}")
        doy = int(m.group(1))
        tfac = float(m.group(2))
        wfac = float(m.group(3))
        rate0 = float(m.group(4))
        nlay = int(m.group(5))

        orgn_parts = l_orgn.split()
        if len(orgn_parts) != 1 + nlay or orgn_parts[0] != "ORGN":
            raise ValueError(f"{source_name}: malformed ORGN line at DOY={doy}: {l_orgn!r}")
        orgn = [float(x) for x in orgn_parts[1:]]

        minout_parts = l_minout.split()
        if len(minout_parts) != 1 + nlay or minout_parts[0] != "MINOUT":
            raise ValueError(f"{source_name}: malformed MINOUT line at DOY={doy}: {l_minout!r}")
        minout = [float(x) for x in minout_parts[1:]]

        total_parts = l_total.split()
        if len(total_parts) != 2 or total_parts[0] != "TOTAL":
            raise ValueError(f"{source_name}: malformed TOTAL line at DOY={doy}: {l_total!r}")
        total = float(total_parts[1])

        records.append(
            TraceRecord(
                doy=doy,
                tfac=tfac,
                wfac=wfac,
                rate0=rate0,
                nlay=nlay,
                orgn=orgn,
                minout=minout,
                total=total,
            )
        )
    return records


def compare_traces(
    ref: List[TraceRecord],
    actual: List[TraceRecord],
    tol: float,
) -> tuple[List[str], float]:
    errors: List[str] = []
    max_diff = 0.0

    if len(ref) != len(actual):
        errors.append(f"Record count mismatch: expected {len(ref)}, got {len(actual)}")
        return errors, max_diff

    for i, (r, a) in enumerate(zip(ref, actual), start=1):
        if r.doy != a.doy:
            errors.append(f"Record {i}: DOY mismatch (expected {r.doy}, got {a.doy})")
        if r.nlay != a.nlay:
            errors.append(f"Record {i} (DOY={r.doy}): NLAY mismatch (expected {r.nlay}, got {a.nlay})")
            continue

        for field, rv, av in (
            ("TFAC", r.tfac, a.tfac),
            ("WFAC", r.wfac, a.wfac),
            ("RATE0", r.rate0, a.rate0),
            ("TOTAL", r.total, a.total),
        ):
            d = abs(rv - av)
            if d > max_diff:
                max_diff = d
            if d > tol:
                errors.append(
                    f"DOY={r.doy} {field}: expected {rv:.6f}, got {av:.6f} (diff={d:.6g} > tol={tol:.6g})"
                )

        for k, (rv, av) in enumerate(zip(r.orgn, a.orgn), start=1):
            d = abs(rv - av)
            if d > max_diff:
                max_diff = d
            if d > tol:
                errors.append(
                    f"DOY={r.doy} ORGN({k}): expected {rv:.4f}, got {av:.4f} (diff={d:.6g} > tol={tol:.6g})"
                )

        for k, (rv, av) in enumerate(zip(r.minout, a.minout), start=1):
            d = abs(rv - av)
            if d > max_diff:
                max_diff = d
            if d > tol:
                errors.append(
                    f"DOY={r.doy} MINOUT({k}): expected {rv:.6f}, got {av:.6f} (diff={d:.6g} > tol={tol:.6g})"
                )

    return errors, max_diff


def run_replay(replay_cmd: str, records: List[TraceRecord]) -> str:
    stdin_lines = []
    for r in records:
        padded_orgn = r.orgn + [0.0] * (8 - len(r.orgn))
        orgn_str = " ".join(f"{v:.4f}" for v in padded_orgn)
        stdin_lines.append(f"{r.doy} {r.nlay} {r.tfac:.4f} {r.wfac:.4f} {r.rate0:.6f} {orgn_str}")
    payload = "\n".join(stdin_lines) + "\n"

    proc = subprocess.run(
        [replay_cmd],
        input=payload,
        text=True,
        capture_output=True,
        check=False,
    )
    if proc.returncode != 0:
        raise RuntimeError(
            f"Replay command {replay_cmd!r} exited with {proc.returncode}: {proc.stderr.strip()}"
        )
    return proc.stdout


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="Validate MERIDIAN_TRACE against reference fixture.")
    parser.add_argument("reference", type=Path, help="Path to reference MERIDIAN_TRACE.REF")
    parser.add_argument(
        "candidate",
        nargs="?",
        type=Path,
        help="Path to candidate MERIDIAN.TRC file (omit when using --replay)",
    )
    parser.add_argument(
        "--replay",
        metavar="CMD",
        help="Replay executable reading trace inputs on stdin and writing NMINK trace on stdout",
    )
    parser.add_argument(
        "--tol",
        type=float,
        default=None,
        help="Absolute floating-point tolerance (default: 1e-5 for trace files, 5e-4 for --replay)",
    )
    args = parser.parse_args(argv)

    if bool(args.candidate) == bool(args.replay):
        parser.error("Provide either <candidate> trace file or --replay <CMD> (not both).")

    tol = args.tol if args.tol is not None else (5e-4 if args.replay else 1e-5)

    try:
        ref_records = parse_trace(args.reference.read_text(encoding="utf-8"), str(args.reference))
        if args.replay:
            actual_text = run_replay(args.replay, ref_records)
            actual_name = f"replay({args.replay})"
        else:
            actual_text = args.candidate.read_text(encoding="utf-8")
            actual_name = str(args.candidate)
        actual_records = parse_trace(actual_text, actual_name)
    except Exception as exc:
        print(f"TRACE VALIDATION ERROR: {exc}", file=sys.stderr)
        return 1

    errors, max_diff = compare_traces(ref_records, actual_records, tol)
    if errors:
        print(
            f"FAIL: {len(errors)} trace mismatch(es) against {args.reference} (tol={tol:.1e}):",
            file=sys.stderr,
        )
        for err in errors[:20]:
            print(f"  - {err}", file=sys.stderr)
        if len(errors) > 20:
            print(f"  ... and {len(errors) - 20} more", file=sys.stderr)
        return 1

    print(
        f"PASS: {len(ref_records)} trace records validated against {args.reference} "
        f"(max diff = {max_diff:.2e}, tol = {tol:.1e})."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
