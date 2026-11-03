"""Offline acceptance only. No SQL, network, provider calls or production writes.
Canonical exact JSON comparison intentionally requires decimal/bigint strings.
Reports contain case IDs, never business results. This is evidence validation,
not proof that supplied evidence is authentic or independently reviewed.
"""
import argparse
import hashlib
import json
from pathlib import Path

CATEGORIES = {"aggregation", "dates", "joins", "nulls", "distinct", "units", "ambiguity"}


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False)


def evaluate(suite, results):
    cases = suite.get("cases", [])
    if len(cases) < 100:
        raise ValueError("At least 100 independently reviewed business cases are required")
    if not suite.get("frozen_at") or not suite.get("reviewer") or not suite.get("dataset_version"):
        raise ValueError("Frozen date, human reviewer and dataset version are required")
    target = suite.get("target_correctness")
    if isinstance(target, bool) or not isinstance(target, (float, int)) or not 0 < target <= 1:
        raise ValueError("Agree target correctness after freezing the business cases")
    digest = hashlib.sha256(canonical(suite).encode()).hexdigest()
    if results.get("suite_sha256") != digest or results.get("dataset_version") != suite["dataset_version"]:
        raise ValueError("Results must bind the exact frozen suite and dataset")
    if not results.get("commit_sha") or not results.get("executed_at"):
        raise ValueError("Commit and execution time are required")
    ids = [case.get("id") for case in cases]
    if any(not isinstance(i, str) or not i for i in ids) or len(set(ids)) != len(ids):
        raise ValueError("Case IDs must be nonempty and unique")
    coverage = set()
    for case in cases:
        if not case.get("question") or not case.get("reviewed_by") or "expected" not in case:
            raise ValueError("Every case needs a question, independent reviewer and expected result")
        coverage.update(case.get("categories", []))
    if not CATEGORIES <= coverage:
        raise ValueError("Missing required coverage: " + ", ".join(sorted(CATEGORIES - coverage)))
    actual = results.get("cases", [])
    actual_ids = [case.get("id") for case in actual]
    if len(set(actual_ids)) != len(actual_ids) or set(actual_ids) != set(ids):
        raise ValueError("Actual results must include every case exactly once, no extras")
    lookup = {case["id"]: case for case in actual}
    passed = [case["id"] for case in cases if "actual" in lookup[case["id"]]
              and canonical(case["expected"]) == canonical(lookup[case["id"]]["actual"])]
    score = len(passed) / len(cases)
    return {"suite_sha256": digest, "commit_sha": results["commit_sha"], "total": len(cases),
            "passed": len(passed), "correctness": score, "target": target,
            "accepted": score >= target, "failed_ids": [i for i in ids if i not in passed]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("suite", type=Path)
    parser.add_argument("results", type=Path)
    args = parser.parse_args()
    try:
        report = evaluate(json.loads(args.suite.read_text()), json.loads(args.results.read_text()))
        print(json.dumps(report, indent=2))
        return 0 if report["accepted"] else 1
    except (ValueError, KeyError, TypeError) as error:
        print(json.dumps({"accepted": False, "error": str(error)}))
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
