# Dialogue condition grammar and operand expansion

Implementation base: `c2ed1b4`. Reference: installed Interactions 2.14.1; target: Minecraft 1.21.1 / NeoForge 21.1.248, JDK 21.

## Original contract and active data

The actual `ConditionsManager` and `ConditionalType` bytecode, variable scanner, source hashes and conversation inventory are retained in `artifacts/legacy-condition-grammar.txt`. The reference jar SHA-256 was rechecked as `ce4824a2bb4317eb9acd40cd4388eebedfab521d96310bf74f2d26f0ac85293e`.

Of 3,886 requirements in the 146 original conversation files, 2,468 contain literal ` or ` and 2,542 contain multiple placeholders in an operand. The previous evaluator supported one symbolic comparison and isolated placeholders, making these ordinary ticket/shop routes unavailable. These counts describe syntax usage, not completed acceptance of every conversation or external provider.

Requirement list entries are ANDed. Inside each entry, the exact lowercase delimiter ` or ` separates alternatives. There is no Boolean `and`: `%itemA% and %itemB% == yes and no` compares the complete expanded string `yes and no`. Both complete operands expand, left first. Operators have one ASCII space on each side; extra operand spaces remain string content.

| Operators | Comparison |
|---|---|
| `==`, `equals`; `!=`, `!equals` | Case-sensitive equality and its negation |
| `equalsIgnoreCase`, `!equalsIgnoreCase` | Case-insensitive equality and its negation |
| `startsWith`, `!startsWith` | Case-insensitive prefix and its negation |
| `contains`, `!contains` | Case-insensitive substring and its negation |
| `>`, `>=`, `<`, `<=` | Numeric ordering |

The original enum order above determines interpretation when several different operators occur: each operator's first delimiter is tried, and the first successful interpretation wins. Repeated identical delimiters stay in the right operand after the first split. Thirty original branches contain two equality delimiters without an intervening OR; this implementation does not invent missing syntax or rewrite those files.

## Native implementation

`Conditions` now implements these operators, original delimiter/case/spacing rules, OR short-circuiting and complete operand expansion. Grammar is identified before values are expanded. Single passes over original percent tokens and their inner brace arguments preserve literal text and do not reinterpret returned values as grammar or recursively expand them. As in the original scanner, percent/brace candidates with a boundary ASCII space are ordinary text.

Recognized native player and progress placeholders use the existing `Text` resolution. CheckItem remains the inventory provider. Each complete token must resolve; an unknown token embedded in text cannot pass inequality or any other negated comparison. An unresolved alternative fails independently, allowing a later valid alternative. Missing player context and unreadable progress cannot establish progress predicates.

Item queries are parsed before their Boolean answer is used. Unsupported kinds or invalid amounts remain unresolved instead of becoming `no`. Checks use the original token with the non-consuming inventory evaluator, even when the token uses `remove_`; no global string replacement can corrupt a lore argument containing `checkitem_remove_`. Only action execution consumes items.

All five session requirement gates remain in place and read current state: pending option execution, line eligibility, conditional redirect, option filtering, and choice acceptance. A stale option cannot take payment or grant rewards after every applicable alternative is revoked. Valid alternative progress can enable a previously offered option without changing its identity.

## Deliberate corrections and limits

- OR and operator delimiters inside a placeholder argument are data. The original raw split could break lore text containing such delimiters.
- Numeric comparisons retain finite, exact `BigDecimal` values and reject malformed numbers. Surrounding numeric whitespace is ignored, as by the original numeric parser. The original swallowed parse failures and could compare default zero values; its floating-point precision loss and infinities are not reproduced.
- Prefix/substring case folding uses `Locale.ROOT`, avoiding the original JVM-locale-dependent result.
- Unknown providers and corrupt progress fail unresolved rather than allowing literal placeholder comparisons or fabricated absent values. A null requirement entry fails; an empty requirement list remains true.

This is not a general PlaceholderAPI runtime. Quests, ParseOther and other missing external expansions/event variables still require their actual providers and semantics. The supported brace pass does not supply those providers. Nor does this change implement editor inventories, incoming-chat restoration, remaining configuration, saved-item fidelity, group capabilities, Citizens API/parameters, Sentinel or full client/modpack acceptance.

## Validation

Ten new unit cases cover all fourteen operators and negations, case and whitespace, OR ordering, list AND, literal `and`, complete operands, unknowns, embedded grammar, enum interpretation, precise numbers, Turkish locale and single-pass brace expansion. The complete dialogue suite has 93 tests.

The isolated connected-player fixture adds 39 checks with required marker `[CONDITIONAUDIT] COMPLETE`. These use actual inventory components, progress records and sessions: item/lore and native/brace expansion, player isolation, repeated non-consuming removal predicates, malformed queries, unknown providers, corrupt-file preservation, conditional routing, line gates, filtered options, eligibility changes and pending execution before payment/rewards/progress. The six prior dialogue phases remain mandatory.

Final NeoForge 21.1.248 validation passes: 39 condition, 51 transfer, 89 world, 61 influence, 84 scheduling, 91 native-action and 127 display checks; 93 dialogue unit tests; 107 NPC assertions, 223 general runtime probes and 166 ordinary tests. Unit failures/errors/skips are zero. The normal build succeeds and release/source jars contain no opt-in probes. Log, source and jar hashes are recorded in `artifacts/conditions-validation-summary.json`; Interactions SHA-256 is `a1c22b4617e65a2c99ad5e6c35c5d3e70216f65478cf9c46def5a08af97cc213`. Unrelated dedicated suites retain their earlier evidence. No production files are changed by this batch.
