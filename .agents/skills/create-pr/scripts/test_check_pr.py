import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from check_pr import inspect, CONFIG


class InspectTests(unittest.TestCase):
    def test_small_pr_without_diagram_is_fine(self):
        data = inspect("## 변경 이유\n\n오탈자를 수정합니다.\n")
        self.assertEqual(data["signals"], [])
        self.assertEqual(data["structure"]["mermaid_diagrams"], 0)

    def test_long_paragraph_is_only_review_candidate(self):
        data = inspect("## 변경\n\n" + "긴 문장입니다. " * 90)
        self.assertTrue(any(i["rule"] == "PRC-010" and i["status"] == "NEEDS_REVIEW" for i in data["signals"]))
        self.assertFalse(any(i["status"] == "FAIL" for i in data["signals"]))

    def test_subheadings_prevent_section_structure_signal(self):
        data = inspect("## 변경\n\n### API\n\n" + ("a" * 200 + "\n\n") * 9)
        self.assertFalse(any("긴 섹션" in i["message"] for i in data["signals"]))

    def test_markdown_table_and_mermaid_are_counted(self):
        data = inspect("## 변경\n\n| 이전 | 이후 |\n| --- | --- |\n| a | b |\n\n```mermaid\nflowchart LR\n A --> B\n```\n")
        self.assertEqual(data["structure"]["markdown_tables"], 1)
        self.assertEqual(data["structure"]["mermaid_diagrams"], 1)

    def test_code_block_does_not_trigger_long_prose(self):
        data = inspect("## 변경\n\n```kotlin\n" + "val x = 1\n" * 100 + "```\n")
        self.assertFalse(any(x["rule"] == "PRC-010" for x in data["signals"]))

    def test_placeholder_detected(self):
        data = inspect("## 변경\n\nTODO: 실제 검증 결과 작성")
        self.assertTrue(any(x["rule"] == "PLACEHOLDER" for x in data["signals"]))

    def test_registry_complete(self):
        self.assertEqual(CONFIG["rule_ids"], [f"PRC-{i:03d}" for i in range(1, 15)])


if __name__ == "__main__":
    unittest.main()
