function renderParagraphSection(level, title, value) {
  return `${"#".repeat(level)} ${title}\n\n${value}`;
}

function renderListSection(level, title, values) {
  const heading = `${"#".repeat(level)} ${title}`;
  if (values.length === 0) return heading;
  return `${heading}\n\n${values.map((value) => `- ${value}`).join("\n")}`;
}

export function renderTaskMarkdown(content) {
  return `${[
    renderParagraphSection(1, "목표", content.goal),
    renderListSection(1, "요구사항", content.requirements),
    [
      "# 범위",
      renderListSection(2, "입력", content.scope.in),
      renderListSection(2, "출력", content.scope.out),
    ].join("\n\n"),
    renderListSection(1, "검증", content.verification),
    renderListSection(1, "이슈", content.issues),
    renderListSection(1, "참고자료", content.references),
  ].join("\n\n")}\n`;
}

export function renderStepMarkdown(content) {
  return `${[
    renderParagraphSection(1, "목표", content.objective),
    renderListSection(1, "작업", content.work),
    renderListSection(1, "검증", content.verification),
    renderListSection(1, "이슈", content.issues),
    renderListSection(1, "참고자료", content.references),
  ].join("\n\n")}\n`;
}
