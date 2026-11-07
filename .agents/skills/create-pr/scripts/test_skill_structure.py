"""Verify general rules and synthetic cases remain portable."""

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
REFERENCES = ROOT / "references"


class SkillStructureTests(unittest.TestCase):
    def test_cases_cover_every_rule_id(self):
        catalog = (REFERENCES / "problem-catalog.md").read_text(encoding="utf-8")
        examples = (REFERENCES / "cases.md").read_text(encoding="utf-8")
        ids = set(re.findall(r"PRC-\d{3}", catalog))
        self.assertEqual(len(ids), 14)
        self.assertFalse(ids - set(re.findall(r"PRC-\d{3}", examples)))

    def test_instructions_do_not_embed_single_repository_case(self):
        files = [
            ROOT / "SKILL.md",
            REFERENCES / "index.md",
            REFERENCES / "visual-structure.md",
            REFERENCES / "rubric.md",
            REFERENCES / "cases.md",
        ]
        for path in files:
            with self.subTest(path=path.name):
                content = path.read_text(encoding="utf-8")
                self.assertNotRegex(
                    content,
                    r"SpringKit|github\.com/Jeong-Rae|PR\s*#\d+|\b(?:BC|Slice|SQLite|UnitOfWork)\b",
                )

    def test_cases_are_optional_and_single_case_removed(self):
        skill = (ROOT / "SKILL.md").read_text(encoding="utf-8")
        self.assertIn("[cases.md](references/cases.md)", skill)
        self.assertIn("선택", skill)
        self.assertFalse((REFERENCES / "case-pr38.md").exists())
        self.assertFalse((REFERENCES / "sources.md").exists())

    def test_examples_contain_counterexamples(self):
        cases = (REFERENCES / "cases.md").read_text(encoding="utf-8")
        self.assertGreaterEqual(cases.count("**판단·반례:**"), 10)
        self.assertIn("가상", cases)


if __name__ == "__main__":
    unittest.main()
