## ActionGraph extraction eval

Extractor: rule-based (offline)

| Set | Meetings | Items P / R / **F1** | Explicit P / R / **F1** | Implicit P / R / **F1** | Decisions F1 | Deadline acc. | Hallucination rate | Routed to review | Auto-accept precision |
|---|---|---|---|---|---|---|---|---|---|
| synthetic | 200 | 1.00 / 0.98 / **0.992** | 1.00 / 0.98 / **0.988** | 1.00 / 1.00 / **1.000** | 1.000 | 1.000 | 0.000 | 29.1% | 1.000 |
| hand-written dev set | 3 | 1.00 / 1.00 / **1.000** | 1.00 / 1.00 / **1.000** | 1.00 / 1.00 / **1.000** | 1.000 | 1.000 | 0.000 | 29.4% | 1.000 |
| **blind set** (held out) | 3 | 1.00 / 0.80 / **0.889** | 1.00 / 0.67 / **0.800** | 0.75 / 1.00 / **0.857** | 0.667 | 0.857 | 0.000 | 33.3% | 1.000 |

Evidence verifier stress test: rejected **100%** of 200 fabricated quotes, falsely rejected 0.0% of 3626 exact quotes, flagged 100% of 2263 one-word paraphrases for review.

Gold counts: synthetic 1010 items (721 explicit / 289 implicit); dev set 17 (12 / 5); blind set 15 (12 / 3).

Errors on the blind set (FP = extracted but wrong, FN = missed):

- FN lines [6] [Grace, EXPLICIT] app store screenshots (Mobile release huddle)
- FN lines [7, 8] [Omar, EXPLICIT] double check the deep link (Mobile release huddle)
- FN lines [12] [Rui, EXPLICIT] send the icon exports (Design review: settings page)
