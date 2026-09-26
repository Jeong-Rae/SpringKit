# Worker Question Schema

Before modifying files, respond with this exact shape.

Put the question first when one exists. Keep each free-text field to 1-3 lines when practical.

```text
WORKER_QUESTION

STATUS
<QUESTION | READY | OUT_OF_SCOPE | BLOCKED>

QUESTION
<1-3 lines. Question requiring an orchestrator answer or decision. Use None. when READY.>

UNDERSTANDING
<1-3 lines. Facts, responsibility, and boundary the worker currently understands>

QUESTION INTENT
<1-3 lines. What the question is trying to determine and which decision the answer will resolve>
```

When `STATUS` is `QUESTION`, ask only one question. Ask another question in the next response after receiving the answer.

When `STATUS` is `READY`, write `None.` under `QUESTION` and `QUESTION INTENT`, and briefly state only the final understanding under `UNDERSTANDING`.
