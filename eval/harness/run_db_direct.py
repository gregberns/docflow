#!/usr/bin/env python3
"""Live-LLM eval harness for DocFlow — DB-direct variant.

Workaround for the broken dashboard (df-txl: DashboardRepository is a stub).
Instead of polling `GET /api/organizations/{orgId}/documents` for materialized
state, this script queries Postgres directly via `docker exec basata-postgres-1
psql ...` to find the row in `documents` (or a FAILED row in
`processing_documents`) that corresponds to the just-uploaded `storedDocumentId`.

Reuses normalization + reporting helpers from `run.py`.

CLI: `python3 eval/harness/run_db_direct.py [--smoke]`
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

try:
    import requests
except ImportError as exc:
    sys.stderr.write(
        f"Missing dependency: {exc.name}. Install with: pip install requests pyyaml\n"
    )
    sys.exit(2)

# Reuse the canonical harness's helpers; do not duplicate.
HERE = Path(__file__).resolve().parent
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))

from run import (  # type: ignore  # noqa: E402
    REPO_ROOT,
    SAMPLES_ROOT,
    REPORTS_DIR,
    Sample,
    FieldResult,
    SampleResult,
    DocflowClient,
    fields_match,
    load_samples,
    pick_smoke_samples,
)


PG_CONTAINER = os.environ.get("DOCFLOW_PG_CONTAINER", "basata-postgres-1")
PG_USER = os.environ.get("DOCFLOW_PG_USER", "docflow")
PG_DB = os.environ.get("DOCFLOW_PG_DB", "docflow")


# --------------------------- DB layer ---------------------------


def psql(sql: str, timeout: float = 10.0) -> str:
    """Run a SQL statement via `docker exec ... psql -tAc`. Returns stdout text.

    `-t` strips headers, `-A` unaligned output. We use a custom field separator
    (`|`) which is the psql default for `-A`.
    """
    cmd = [
        "docker",
        "exec",
        PG_CONTAINER,
        "psql",
        "-U",
        PG_USER,
        "-d",
        PG_DB,
        "-tAc",
        sql,
    ]
    proc = subprocess.run(
        cmd,
        capture_output=True,
        text=True,
        timeout=timeout,
        check=False,
    )
    if proc.returncode != 0:
        raise RuntimeError(
            f"psql failed (rc={proc.returncode}): {proc.stderr.strip() or proc.stdout.strip()}"
        )
    return proc.stdout


def fetch_document_row(stored_document_id: str) -> dict | None:
    """Return {id, detected_document_type, extracted_fields} or None.

    Uses `row_to_json` so we get a single JSON line that survives shell quoting
    and never collides with the `|` field separator (extracted_fields can
    contain pipes).
    """
    sql = (
        "SELECT row_to_json(t) FROM ("
        "  SELECT id::text AS id, detected_document_type, extracted_fields"
        "  FROM documents"
        f"  WHERE stored_document_id = '{stored_document_id}'"
        ") t"
    )
    out = psql(sql).strip()
    if not out:
        return None
    return json.loads(out)


def fetch_processing_failure(stored_document_id: str) -> tuple[str | None, str | None]:
    """Return (current_step, last_error) for any processing row, or (None, None)."""
    sql = (
        "SELECT row_to_json(t) FROM ("
        "  SELECT current_step, last_error"
        "  FROM processing_documents"
        f"  WHERE stored_document_id = '{stored_document_id}'"
        "  ORDER BY created_at DESC"
        "  LIMIT 1"
        ") t"
    )
    out = psql(sql).strip()
    if not out:
        return None, None
    row = json.loads(out)
    return row.get("current_step"), row.get("last_error")


# --------------------------- core flow ---------------------------


def run_one(
    client: DocflowClient,
    sample: Sample,
    poll_timeout: float,
    poll_interval: float,
) -> SampleResult:
    result = SampleResult(sample=sample)
    pdf_full = SAMPLES_ROOT / sample.pdf_path
    if not pdf_full.exists():
        result.error = f"pdf not found: {pdf_full}"
        return result

    started = time.monotonic()
    try:
        upload_resp = client.upload(sample.org_slug, pdf_full)
    except Exception as exc:
        result.error = f"upload: {exc}"
        return result

    stored_id = upload_resp.get("storedDocumentId")
    if not stored_id:
        result.error = f"upload response missing storedDocumentId: {upload_resp!r}"
        return result

    deadline = started + poll_timeout
    row: dict | None = None
    last_step, last_error = None, None
    while time.monotonic() < deadline:
        try:
            row = fetch_document_row(stored_id)
        except Exception as exc:
            result.error = f"db poll: {exc}"
            return result
        if row:
            break
        try:
            last_step, last_error = fetch_processing_failure(stored_id)
        except Exception as exc:
            result.error = f"db poll (processing): {exc}"
            return result
        if last_step == "FAILED":
            break
        time.sleep(poll_interval)

    result.last_processing_step = last_step
    result.last_processing_error = last_error
    result.latency_seconds = time.monotonic() - started

    if row is None:
        if last_step == "FAILED":
            result.error = f"processing FAILED: {last_error}"
        else:
            result.error = (
                f"timeout after {poll_timeout:.0f}s "
                f"(last step={last_step}, last error={last_error})"
            )
        return result

    result.document_id = row.get("id")
    result.detected_doc_type = row.get("detected_document_type")
    result.current_status = "FILED"
    result.doc_type_match = result.detected_doc_type == sample.doc_type

    extracted_raw = row.get("extracted_fields") or {}
    # row_to_json returns JSONB as a nested object already (psycopg-style would
    # need parsing, but psql -t returns parsed JSON via row_to_json), but if
    # something stringified it, decode defensively.
    if isinstance(extracted_raw, str):
        try:
            extracted_raw = json.loads(extracted_raw)
        except json.JSONDecodeError:
            extracted_raw = {}
    extracted = extracted_raw if isinstance(extracted_raw, dict) else {}

    for name, expected in sample.expected_fields.items():
        actual = extracted.get(name)
        result.field_results.append(
            FieldResult(
                name=name,
                expected=expected,
                actual=actual,
                matched=fields_match(expected, actual),
            )
        )

    return result


# --------------------------- reporting ---------------------------


def write_report(
    results: list[SampleResult],
    output_path: Path,
    base_url: str,
    smoke: bool,
) -> None:
    output_path.parent.mkdir(parents=True, exist_ok=True)
    total = len(results)
    completed = [r for r in results if r.error is None]
    errored = [r for r in results if r.error is not None]
    doc_type_hits = sum(1 for r in completed if r.doc_type_match)
    field_hits = sum(r.fields_matched for r in completed)
    field_total = sum(r.fields_total for r in completed)

    by_org: dict[str, list[SampleResult]] = {}
    for r in results:
        by_org.setdefault(r.sample.org_slug, []).append(r)

    # Per-org per-doc-type accuracy
    by_org_type: dict[tuple[str, str], list[SampleResult]] = {}
    for r in results:
        by_org_type.setdefault((r.sample.org_slug, r.sample.doc_type), []).append(r)

    lines: list[str] = []
    lines.append(
        f"# DocFlow eval report (DB-direct) — "
        f"{datetime.now(timezone.utc).isoformat(timespec='seconds')}"
    )
    lines.append("")
    lines.append(f"- Mode: {'smoke' if smoke else 'full'}")
    lines.append(f"- Backend: `{base_url}`")
    lines.append(f"- DB: `docker exec {PG_CONTAINER} psql -U {PG_USER} -d {PG_DB}`")
    lines.append("- Discovery: poll `documents` table directly (df-txl workaround)")
    lines.append(f"- Samples: {total} (completed: {len(completed)}, errored: {len(errored)})")
    if completed:
        lines.append(
            f"- Doc-type accuracy: **{doc_type_hits}/{len(completed)}** "
            f"({(doc_type_hits / len(completed)) * 100:.1f}%)"
        )
    if field_total:
        lines.append(
            f"- Field accuracy (over completed): "
            f"**{field_hits}/{field_total}** ({(field_hits / field_total) * 100:.1f}%)"
        )
    if completed:
        latencies = [r.latency_seconds for r in completed if r.latency_seconds]
        if latencies:
            lines.append(
                f"- Latency: avg {sum(latencies)/len(latencies):.1f}s, "
                f"min {min(latencies):.1f}s, max {max(latencies):.1f}s"
            )
    lines.append("")

    lines.append("## Per-org breakdown")
    lines.append("")
    lines.append("| Org | Samples | Doc-type hits | Field hits | Field total |")
    lines.append("|---|---:|---:|---:|---:|")
    for org, rs in sorted(by_org.items()):
        comp = [r for r in rs if r.error is None]
        dh = sum(1 for r in comp if r.doc_type_match)
        fh = sum(r.fields_matched for r in comp)
        ft = sum(r.fields_total for r in comp)
        lines.append(f"| {org} | {len(rs)} | {dh}/{len(comp)} | {fh} | {ft} |")
    lines.append("")

    lines.append("## Per-org per-doc-type accuracy")
    lines.append("")
    lines.append("| Org | Doc-type (expected) | Samples | Doc-type hits | Field hits / total |")
    lines.append("|---|---|---:|---:|---:|")
    for (org, dt), rs in sorted(by_org_type.items()):
        comp = [r for r in rs if r.error is None]
        dh = sum(1 for r in comp if r.doc_type_match)
        fh = sum(r.fields_matched for r in comp)
        ft = sum(r.fields_total for r in comp)
        lines.append(f"| {org} | {dt} | {len(rs)} | {dh}/{len(comp)} | {fh}/{ft} |")
    lines.append("")

    lines.append("## Per-sample")
    lines.append("")
    lines.append(
        "| Sample | Org | Expected type | Detected | Match | Fields | Latency (s) | Notes |"
    )
    lines.append("|---|---|---|---|:-:|:-:|---:|---|")
    for r in results:
        s = r.sample
        if r.error:
            lines.append(
                f"| `{s.id}` | {s.org_slug} | {s.doc_type} | — | — | — | "
                f"{r.latency_seconds or 0:.1f} | ERROR: {r.error} |"
            )
            continue
        match_mark = "Y" if r.doc_type_match else "N"
        lines.append(
            f"| `{s.id}` | {s.org_slug} | {s.doc_type} | "
            f"{r.detected_doc_type or '—'} | {match_mark} | "
            f"{r.fields_matched}/{r.fields_total} | "
            f"{r.latency_seconds or 0:.1f} | |"
        )
    lines.append("")

    imperfect = [
        r for r in results
        if r.error is None and (not r.doc_type_match or r.fields_matched < r.fields_total)
    ]
    if imperfect:
        lines.append("## Misses — field detail")
        lines.append("")
        for r in imperfect:
            s = r.sample
            lines.append(f"### `{s.id}` ({s.org_slug} / {s.doc_type})")
            lines.append("")
            if not r.doc_type_match:
                lines.append(
                    f"- doc-type: expected `{s.doc_type}`, got `{r.detected_doc_type}`"
                )
            for fr in r.field_results:
                if fr.matched:
                    continue
                lines.append(
                    f"- field `{fr.name}`: expected `{fr.expected!r}`, got `{fr.actual!r}`"
                )
            lines.append("")

    if errored:
        lines.append("## Errors")
        lines.append("")
        for r in errored:
            lines.append(f"- `{r.sample.id}`: {r.error}")
        lines.append("")

    output_path.write_text("\n".join(lines))


# --------------------------- entry ---------------------------


def main() -> int:
    parser = argparse.ArgumentParser(
        description="DocFlow eval harness — DB-direct variant (df-txl workaround)"
    )
    parser.add_argument(
        "--base-url",
        default=os.environ.get(
            "DOCFLOW_BASE_URL",
            f"http://localhost:{os.environ.get('BACKEND_HOST_PORT', '8551')}",
        ),
        help="DocFlow backend base URL (default: %(default)s)",
    )
    parser.add_argument(
        "--samples",
        nargs="+",
        help="Run only the listed sample ids (from labels.yaml).",
    )
    parser.add_argument(
        "--smoke",
        action="store_true",
        help="Run only one sample per org (3 samples) for a quick sanity pass.",
    )
    parser.add_argument(
        "--poll-timeout",
        type=float,
        default=60.0,
        help="Seconds to wait for materialized row (default: %(default)s).",
    )
    parser.add_argument(
        "--poll-interval",
        type=float,
        default=1.0,
        help="Seconds between DB polls (default: %(default)s).",
    )
    parser.add_argument(
        "--report-path",
        type=Path,
        help="Override default report path (default: eval/reports/db_direct_<UTC>.md).",
    )
    args = parser.parse_args()

    samples = load_samples(args.samples)
    if args.smoke:
        if args.samples:
            sys.stderr.write(
                "note: --smoke combined with --samples; running listed ids only\n"
            )
        else:
            samples = pick_smoke_samples(samples)
    if not samples:
        sys.exit("no samples to run")

    # Health check — both the API and the DB.
    client = DocflowClient(args.base_url)
    ok, msg = client.health_check()
    if not ok:
        sys.stderr.write(f"backend health check failed: {msg}\n")
        return 3
    print(f"backend OK — {msg}")

    try:
        psql("SELECT 1")
    except Exception as exc:
        sys.stderr.write(f"DB health check failed (psql via docker exec): {exc}\n")
        return 3
    print(f"DB OK — psql via {PG_CONTAINER}")

    print(f"running {len(samples)} sample(s) against {args.base_url}")
    results: list[SampleResult] = []
    for i, sample in enumerate(samples, start=1):
        print(f"  [{i}/{len(samples)}] {sample.id} ...", end="", flush=True)
        result = run_one(client, sample, args.poll_timeout, args.poll_interval)
        results.append(result)
        if result.error:
            print(f" ERROR: {result.error}")
        else:
            print(
                f" type={result.detected_doc_type} "
                f"({'hit' if result.doc_type_match else 'miss'}), "
                f"fields={result.fields_matched}/{result.fields_total}, "
                f"{result.latency_seconds:.1f}s"
            )

    timestamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    report_path = args.report_path or (REPORTS_DIR / f"db_direct_{timestamp}.md")
    write_report(results, report_path, args.base_url, args.smoke)
    print(f"\nreport: {report_path}")

    completed = [r for r in results if r.error is None]
    if completed:
        dh = sum(1 for r in completed if r.doc_type_match)
        fh = sum(r.fields_matched for r in completed)
        ft = sum(r.fields_total for r in completed)
        print(
            f"summary: doc-type {dh}/{len(completed)}, fields {fh}/{ft}, "
            f"errors {len(results) - len(completed)}/{len(results)}"
        )
    else:
        print(f"summary: all {len(results)} samples errored")
    return 0


if __name__ == "__main__":
    sys.exit(main())
