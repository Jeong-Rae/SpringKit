#!/usr/bin/env python3
"""Conservative PR Markdown structure hints; never assigns semantic FAIL."""

import argparse
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CONFIG = json.loads((ROOT / "references" / "rules.json").read_text(encoding="utf-8"))
SIGNALS = CONFIG["mechanical_signals"]
TABLE_DELIMITER = re.compile(r"^\s*\|?\s*:?-{3,}:?\s*(?:\|\s*:?-{3,}:?\s*)+\|?\s*$")
FENCE = re.compile(r"^\s*(`{3,}|~{3,})([^`]*)$")
HEADING = re.compile(r"^\s*(#{1,6})\s+(.+?)\s*$")
LIST = re.compile(r"^\s*(?:[-*+]\s|\d+[.)]\s)")


def inspect(markdown: str) -> dict:
    lines = markdown.splitlines()
    issues = []
    headings = []
    paragraphs = []
    paragraphs_buffer = []
    in_fence = False
    fence_marker = ""
    diagram_count = 0
    table_count = 0
    has_heading_under_h2 = False
    current_section = None
    section_body = 0
    section_start = 1

    def flush_paragraph():
        nonlocal paragraphs_buffer
        if paragraphs_buffer:
            paragraphs.append({"line": paragraphs_buffer[0][0], "length": sum(len(s) for _, s in paragraphs_buffer), "text": " ".join(s for _, s in paragraphs_buffer)})
            paragraphs_buffer = []

    def flush_section():
        nonlocal section_body, section_start, has_heading_under_h2
        if current_section is not None and section_body > SIGNALS["max_prose_section_characters_warning"] and not has_heading_under_h2:
            issues.append({"rule": "PRC-010", "status": "NEEDS_REVIEW", "line": section_start, "message": "긴 섹션에서 의미별 서브헤딩이 필요한지 검토하세요."})
        section_body = 0
        has_heading_under_h2 = False

    for i, raw in enumerate(lines, 1):
        stripped = raw.strip()
        fence = FENCE.match(raw)
        if fence:
            flush_paragraph()
            kind = fence.group(1)[0]
            n = len(fence.group(1))
            if not in_fence:
                in_fence = True
                fence_marker = kind * n
                if "mermaid" == fence.group(2).strip().lower().split(" ")[0]:
                    diagram_count += 1
            elif kind == fence_marker[0] and n >= len(fence_marker):
                in_fence = False
                fence_marker = ""
            continue
        if in_fence:
            continue
        match = HEADING.match(raw)
        if match:
            flush_paragraph()
            level = len(match.group(1))
            headings.append({"line": i, "level": level, "title": match.group(2)})
            if level <= 2:
                flush_section()
                current_section = match.group(2)
                section_start = i
            elif level >= 3:
                has_heading_under_h2 = True
            continue
        if not stripped or LIST.match(raw) or stripped.startswith((">", "<!--", "-->")) or stripped.startswith("|"):
            flush_paragraph()
        elif stripped.startswith("<") and stripped.endswith(">"):  # HTML wrappers, not PR prose
            flush_paragraph()
        else:
            paragraphs_buffer.append((i, stripped))
            section_body += len(stripped)
        if TABLE_DELIMITER.match(raw):
            table_count += 1

    flush_paragraph()
    flush_section()
    for p in paragraphs:
        if p["length"] > SIGNALS["max_paragraph_characters_warning"]:
            issues.append({"rule": "PRC-010", "status": "NEEDS_REVIEW", "line": p["line"], "message": "긴 문단에 독립적인 개념이 섞였는지 확인하고 필요하면 서브헤딩으로 나누세요."})
    for term in SIGNALS["placeholder_warning"]:
        for i, line in enumerate(lines, 1):
            if term in line and not line.lstrip().startswith(("```", "~~~")):
                issues.append({"rule": "PLACEHOLDER", "status": "NEEDS_REVIEW", "line": i, "message": f"미완료 표시 후보: {term!r}"})
    if in_fence:
        issues.append({"rule": "FORMAT", "status": "NEEDS_REVIEW", "line": len(lines), "message": "닫히지 않은 코드 블록이 있습니다."})
    return {"signals": issues, "structure": {"headings": len(headings), "markdown_tables": table_count, "mermaid_diagrams": diagram_count, "prose_paragraphs": len(paragraphs)}, "note": "기계 탐지 결과는 위반 확정이 아닙니다. 도표가 없어도 위반은 아닙니다."}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("file", type=Path, help="PR Markdown text file")
    parser.add_argument("--json", action="store_true", help="JSON formatted output")
    args = parser.parse_args()
    result = inspect(args.file.read_text(encoding="utf-8"))
    if args.json:
        print(json.dumps(result, ensure_ascii=False, indent=2))
    else:
        print("구조 요약:", result["structure"])
        for issue in result["signals"]:
            print(f"[{issue['status']}] {issue['rule']} L{issue['line']}: {issue['message']}")
        if not result["signals"]:
            print("기계적 경고 없음. 의미별 루브릭 평가는 별도로 필요합니다.")
        print(result["note"])


if __name__ == "__main__":
    main()
