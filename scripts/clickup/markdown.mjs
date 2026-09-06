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
    renderParagraphSection(1, "Goal", content.goal),
    renderListSection(1, "Requirements", content.requirements),
    [
      "# Scope",
      renderListSection(2, "In", content.scope.in),
      renderListSection(2, "Out", content.scope.out),
    ].join("\n\n"),
    renderListSection(1, "Verification", content.verification),
    renderListSection(1, "Issues", content.issues),
    renderListSection(1, "References", content.references),
  ].join("\n\n")}\n`;
}

export function renderStepMarkdown(content) {
  return `${[
    renderParagraphSection(1, "Objective", content.objective),
    renderListSection(1, "Work", content.work),
    renderListSection(1, "Verification", content.verification),
    renderListSection(1, "Issues", content.issues),
    renderListSection(1, "References", content.references),
  ].join("\n\n")}\n`;
}
