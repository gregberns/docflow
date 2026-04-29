#!/usr/bin/env python3
"""Live-LLM eval harness for DocFlow.

Uploads each labelled sample PDF to the running backend, polls until the
document is materialized (or timeout), and scores the detected document type
+ extracted fields against `labels.yaml`. Writes a markdown report to
`eval/reports/<UTC-timestamp>.md`.

Requires: stdlib + `requests` + `pyyaml`. Tested with Python 3.11+.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import time
import unicodedata
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

try:
    import requests
    import yaml
except ImportError as exc:
    sys.stderr.write(
        f"Missing dependency: {exc.name}. Install with: pip install requests pyyaml\n"
    )
    sys.exit(2)


REPO_ROOT = Path(__file__).resolve().parents[2]
SAMPLES_ROOT = REPO_ROOT / "problem-statement" / "samples"
LABELS_PATH = Path(__file__).parent / "labels.yaml"
REPORTS_DIR = REPO_ROOT / "eval" / "reports"


# --------------------------- normalization ---------------------------

_PUNCT_FOLD = {
    "—": "-",  # em dash
    "–": "-",  # en dash
    "‒": "-",
    "‐": "-",
    "‘": "'",  # smart single quote
    "’": "'",
    "“": '"',  # smart double quote
    "”": '"',
}

_NUMERIC_RE = re.compile(r"^-?\$?[\d,]+(?:\.\d+)?$")


def _fold(s: str) -> str:
    # NFKD splits accented chars into base + combining mark, then we drop the marks.
    decomposed = unicodedata.normalize("NFKD", s)
    out_chars = [c for c in decomposed if not unicodedata.combining(c)]
    out = "".join(out_chars)
    for k, v in _PUNCT_FOLD.items():
        out = out.replace(k, v)
    return out


def normalize_string(value: Any) -> str:
    if value is None:
        return ""
    s = _fold(str(value))
    s = re.sub(r"\s+", " ", s).strip()
    return s.lower()


def normalize_numeric(value: Any) -> str | None:
    """Return a canonical decimal string, or None if not numeric-shaped."""
    if value is None:
        return None
    s = str(value).strip()
    if not s:
        return None
    s = s.replace("$", "").replace(",", "").strip()
    if s.startswith("+"):
        s = s[1:]
    try:
        f = float(s)
    except ValueError:
        return None
    # 2 dp if decimal else integer
    if "." in s:
        return f"{f:.2f}"
    return f"{f:.2f}"


def fields_match(expected: Any, actual: Any) -> bool:
    """Match with light normalization. Numeric coercion when expected looks numeric."""
    if expected is None and actual is None:
        return True
    if expected is None or actual is None:
        return False

    exp_str = str(expected).strip()
    act_str = str(actual).strip() if not isinstance(actual, (dict, list)) else json.dumps(actual)

    # numeric path: expected matches the numeric regex
    if _NUMERIC_RE.match(exp_str.replace(" ", "")):
        en = normalize_numeric(exp_str)
        an = normalize_numeric(act_str)
        if en is not None and an is not None:
            return en == an

    return normalize_string(exp_str) == normalize_string(act_str)


# --------------------------- data classes ---------------------------


@dataclass
class Sample:
    id: str
    org_slug: str
    doc_type: str
    pdf_path: str  # relative to SAMPLES_ROOT
    expected_fields: dict[str, Any]


@dataclass
class FieldResult:
    name: str
    expected: Any
    actual: Any
    matched: bool


@dataclass
class SampleResult:
    sample: Sample
    error: str | None = None
    detected_doc_type: str | None = None
    doc_type_match: bool = False
    field_results: list[FieldResult] = field(default_factory=list)
    current_status: str | None = None
    latency_seconds: float | None = None
    document_id: str | None = None
    last_processing_step: str | None = None
    last_processing_error: str | None = None

    @property
    def fields_matched(self) -> int:
        return sum(1 for fr in self.field_results if fr.matched)

    @property
    def fields_total(self) -> int:
        return len(self.field_results)


# --------------------------- HTTP layer ---------------------------


class DocflowClient:
    def __init__(self, base_url: str, request_timeout: float = 30.0):
        self.base_url = base_url.rstrip("/")
        self.timeout = request_timeout
        self.session = requests.Session()

    def health_check(self) -> tuple[bool, str]:
        """Probe a known endpoint. Returns (ok, message)."""
        try:
            resp = self.session.get(f"{self.base_url}/api/organizations", timeout=5)
        except requests.RequestException as exc:
            return False, f"connection error: {exc}"
        if resp.status_code != 200:
            return False, f"HTTP {resp.status_code}: {resp.text[:200]}"
        try:
            payload = resp.json()
        except ValueError:
            return False, f"non-JSON response: {resp.text[:200]}"
        if not isinstance(payload, list):
            return False, f"unexpected shape: {type(payload).__name__}"
        return True, f"{len(payload)} organizations registered"

    def upload(self, org_slug: str, pdf_path: Path) -> dict:
        url = f"{self.base_url}/api/organizations/{org_slug}/documents"
        with pdf_path.open("rb") as fh:
            files = {"file": (pdf_path.name, fh, "application/pdf")}
            resp = self.session.post(url, files=files, timeout=self.timeout)
        if resp.status_code != 201:
            raise RuntimeError(
                f"upload failed: HTTP {resp.status_code}: {resp.text[:400]}"
            )
        return resp.json()

    def dashboard(self, org_slug: str) -> dict:
        url = f"{self.base_url}/api/organizations/{org_slug}/documents"
        resp = self.session.get(url, timeout=self.timeout)
        if resp.status_code != 200:
            raise RuntimeError(
                f"dashboard failed: HTTP {resp.status_code}: {resp.text[:400]}"
            )
        return resp.json()

    def get_document(self, document_id: str) -> dict:
        url = f"{self.base_url}/api/documents/{document_id}"
        resp = self.session.get(url, timeout=self.timeout)
        if resp.status_code != 200:
            raise RuntimeError(
                f"get document failed: HTTP {resp.status_code}: {resp.text[:400]}"
            )
        return resp.json()


# --------------------------- core flow ---------------------------


def load_samples(samples_filter: list[str] | None) -> list[Sample]:
    if not LABELS_PATH.exists():
        sys.exit(f"labels.yaml not found at {LABELS_PATH}")
    with LABELS_PATH.open() as fh:
        data = yaml.safe_load(fh)
    raw_samples = data.get("samples", [])
    samples = []
    for raw in raw_samples:
        s = Sample(
            id=raw["id"],
            org_slug=raw["org_slug"],
            doc_type=raw["doc_type"],
            pdf_path=raw["pdf_path"],
            expected_fields=dict(raw.get("expected_fields", {})),
        )
        samples.append(s)

    if samples_filter:
        wanted = set(samples_filter)
        samples = [s for s in samples if s.id in wanted]
        missing = wanted - {s.id for s in samples}
        if missing:
            sys.exit(f"unknown sample ids: {sorted(missing)}")

    return samples


def pick_smoke_samples(samples: list[Sample]) -> list[Sample]:
    """Pick one sample per organization for breadth."""
    by_org: dict[str, Sample] = {}
    for s in samples:
        by_org.setdefault(s.org_slug, s)
    return list(by_org.values())


def find_document_id(
    client: DocflowClient, org_slug: str, source_filename: str
) -> tuple[str | None, str | None, str | None]:
    """Return (documentId or None, lastProcessingStep, lastProcessingError)."""
    payload = client.dashboard(org_slug)
    documents = payload.get("documents", [])
    for doc in documents:
        if doc.get("sourceFilename") == source_filename:
            return doc.get("documentId"), None, None
    processing = payload.get("processing", [])
    last_step, last_error = None, None
    for item in processing:
        if item.get("sourceFilename") == source_filename:
            last_step = item.get("currentStep")
            last_error = item.get("lastError")
            break
    return None, last_step, last_error


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
        client.upload(sample.org_slug, pdf_full)
    except Exception as exc:
        result.error = f"upload: {exc}"
        return result

    deadline = started + poll_timeout
    document_id: str | None = None
    last_step, last_error = None, None
    while time.monotonic() < deadline:
        try:
            document_id, last_step, last_error = find_document_id(
                client, sample.org_slug, pdf_full.name
            )
        except Exception as exc:
            result.error = f"poll: {exc}"
            return result
        if document_id:
            break
        if last_error:
            # surface processing-side failure but keep polling briefly in case it's transient
            pass
        time.sleep(poll_interval)

    result.last_processing_step = last_step
    result.last_processing_error = last_error
    result.latency_seconds = time.monotonic() - started

    if not document_id:
        result.error = (
            f"timeout after {poll_timeout:.0f}s "
            f"(last step={last_step}, last error={last_error})"
        )
        return result

    try:
        view = client.get_document(document_id)
    except Exception as exc:
        result.error = f"get document: {exc}"
        return result

    result.document_id = document_id
    result.detected_doc_type = view.get("detectedDocumentType")
    result.current_status = view.get("currentStatus")
    result.doc_type_match = result.detected_doc_type == sample.doc_type

    extracted = view.get("extractedFields") or {}
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

    lines: list[str] = []
    lines.append(
        f"# DocFlow eval report — {datetime.now(timezone.utc).isoformat(timespec='seconds')}"
    )
    lines.append("")
    lines.append(f"- Mode: {'smoke' if smoke else 'full'}")
    lines.append(f"- Backend: `{base_url}`")
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

    lines.append("## Per-sample")
    lines.append("")
    lines.append(
        "| Sample | Org | Expected type | Detected | Match | Fields | Status | Latency (s) | Notes |"
    )
    lines.append("|---|---|---|---|:-:|:-:|---|---:|---|")
    for r in results:
        s = r.sample
        if r.error:
            lines.append(
                f"| `{s.id}` | {s.org_slug} | {s.doc_type} | — | — | — | "
                f"ERROR | {r.latency_seconds or 0:.1f} | {r.error} |"
            )
            continue
        match_mark = "Y" if r.doc_type_match else "N"
        lines.append(
            f"| `{s.id}` | {s.org_slug} | {s.doc_type} | "
            f"{r.detected_doc_type or '—'} | {match_mark} | "
            f"{r.fields_matched}/{r.fields_total} | "
            f"{r.current_status or '—'} | "
            f"{r.latency_seconds or 0:.1f} | |"
        )
    lines.append("")

    # Field-level detail for samples that didn't get a perfect score
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
    parser = argparse.ArgumentParser(description="DocFlow live-LLM eval harness")
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
        help="Seconds to wait for a sample to finish processing (default: %(default)s).",
    )
    parser.add_argument(
        "--poll-interval",
        type=float,
        default=2.0,
        help="Seconds between dashboard polls (default: %(default)s).",
    )
    parser.add_argument(
        "--report-path",
        type=Path,
        help="Override default report path (default: eval/reports/<UTC-timestamp>.md).",
    )
    parser.add_argument(
        "--skip-health-check",
        action="store_true",
        help="Skip the pre-flight health check (use only for testing the harness itself).",
    )
    args = parser.parse_args()

    samples = load_samples(args.samples)
    if args.smoke:
        if args.samples:
            sys.stderr.write("note: --smoke combined with --samples; running listed ids only\n")
        else:
            samples = pick_smoke_samples(samples)
    if not samples:
        sys.exit("no samples to run")

    client = DocflowClient(args.base_url)
    if not args.skip_health_check:
        ok, msg = client.health_check()
        if not ok:
            sys.stderr.write(
                f"health check failed against {args.base_url}: {msg}\n"
                "Backend doesn't appear to be the DocFlow Spring Boot service.\n"
                "Start the stack with: make start (from repo root)\n"
            )
            return 3
        print(f"health check OK — {msg}")

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
    report_path = args.report_path or (REPORTS_DIR / f"{timestamp}.md")
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
