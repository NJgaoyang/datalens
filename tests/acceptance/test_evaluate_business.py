import copy
import hashlib
import unittest
from evaluate_business import CATEGORIES, canonical, evaluate


class EvidenceEvaluatorTest(unittest.TestCase):
    # Synthetic cases test the validator only; never count as business acceptance.
    def setUp(self):
        self.suite = {"frozen_at": "fixture", "reviewer": "fixture", "dataset_version": "fixture",
                      "target_correctness": 1, "cases": [
                          {"id": str(i), "question": "fixture", "reviewed_by": "fixture",
                           "categories": sorted(CATEGORIES), "expected": {"amount": "9007199254740993.01"}}
                          for i in range(100)]}
        self.results = {"dataset_version": "fixture", "commit_sha": "fixture", "executed_at": "fixture",
                        "cases": [{"id": c["id"], "actual": copy.deepcopy(c["expected"])} for c in self.suite["cases"]]}
        self.bind()

    def bind(self):
        self.results["suite_sha256"] = hashlib.sha256(canonical(self.suite).encode()).hexdigest()

    def test_exact_pass(self):
        self.assertTrue(evaluate(self.suite, self.results)["accepted"])

    def test_precision_difference_fails_and_reports_no_values(self):
        self.results["cases"][0]["actual"] = {"amount": 9007199254740993.01}
        report = evaluate(self.suite, self.results)
        self.assertFalse(report["accepted"])
        self.assertEqual(report["failed_ids"], ["0"])
        self.assertNotIn("900719", canonical(report))

    def test_less_than_100_fails(self):
        self.suite["cases"].pop()
        self.bind()
        with self.assertRaises(ValueError): evaluate(self.suite, self.results)

    def test_missing_duplicate_extra_results_fail(self):
        for cases in [self.results["cases"][:-1], self.results["cases"] + [self.results["cases"][0]],
                      self.results["cases"] + [{"id": "extra", "actual": None}]]:
            with self.subTest(cases=len(cases)), self.assertRaises(ValueError):
                evaluate(self.suite, dict(self.results, cases=cases))

    def test_mutated_suite_fails(self):
        self.suite["target_correctness"] = .5
        with self.assertRaises(ValueError): evaluate(self.suite, self.results)

    def test_missing_review_fails(self):
        del self.suite["cases"][1]["reviewed_by"]
        self.bind()
        with self.assertRaises(ValueError): evaluate(self.suite, self.results)

    def test_missing_category_fails(self):
        for case in self.suite["cases"]: case["categories"].remove("joins")
        self.bind()
        with self.assertRaises(ValueError): evaluate(self.suite, self.results)

    def test_missing_actual_is_failure_not_empty_success(self):
        del self.results["cases"][0]["actual"]
        self.assertFalse(evaluate(self.suite, self.results)["accepted"])

    def test_threshold_required(self):
        self.suite["target_correctness"] = True
        self.bind()
        with self.assertRaises(ValueError): evaluate(self.suite, self.results)
