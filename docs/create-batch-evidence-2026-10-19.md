# Native Create saw and crushing-wheel evidence

The selected Create `6.0.10-mc1.21.1-performance-v6` extends the existing
[millstone adapter](create-millstone-evidence-2026-10-18.md) with native mechanical
saw cutting and crushing-wheel crushing/milling. Operators use the existing
`/citizens craftevidence assignmachine <beneficiary UUID> <x> <y> <z>` command.
For crushing wheels, assign the controller block between the two wheels. This
assignment attributes task output to an online player; it grants no block access.

Both adapters wrap the provider's actual `applyRecipe` call. They preserve the
complete input stack and previous outputs before the native inventory clear,
copy every actual random result before Create merges its stacks, and retain the
actual recipe instance. All native rolls and container callbacks finish before
the first output slot write admits durable evidence. The saw calls the container
callback once per input; crushing calls it once for the whole stack. Additional
component-sensitive saw remainders remain separate from task output and preserve
the original single-remainder journal encoding when none are present.

Confirmation requires an empty final input, no displaced previous outputs, and
exact component-sensitive multiset equality for every random output and remainder.
Native output truncation or storage exceptions cannot confirm task progress,
including when all primary products survived. They retain operator review.
Callback failure before admission preserves native exceptions and consumed input;
it does not invent completed evidence. The adapter never selects another recipe,
rerolls results, changes machine speed, or alters native storage behavior.

FTB bindings count actual items matching the selected recipe's first display
output, capped at the native task maximum. Extraction, moving or buying products
cannot supply evidence. Recipe identity is the current registry object, preventing
retired cached recipes and reused IDs from acquiring a new revision. Crushing's
native milling fallback retains its milling recipe identity. Saw stonecutting,
woodcutting, package unpacking and sequenced intermediate recipes remain outside
this cutting adapter. Guidance for the other processing types does not claim
completion evidence.

## Actual process termination and persistence

The opt-in runner `tools/run-machine-crash-audit.ps1` terminates only the JVM whose
PID appears in the fixture's fresh marker and whose command line matches the
opt-in server arguments. It uses synthetic FTB definitions, an actual connected
player, native motor/saw ticks, independently saved FTB/world state and a real
pack recipe selected through Create's public output filter. Each stage restarts
the same saved fixture and verifies six recovery conditions:

- PREPARED before output storage reopens as REVIEW/MISSING. It receives no
  progress; an authenticated operator can reject the original observation.
- CONFIRMED during FTB's native completion callback survives the old FTB save.
  Explicit reconciliation projects its absolute total once. World and journal
  remain distinct; an operator must consider whether saved inputs survived.
- APPLIED before FTB/world save retains evidence with old progress and original
  input. Reconciliation does not silently restore progress. Reprocessing that
  rolled-back input is fenced because native progress differs from applied totals.

These checks prove real termination/reopen behavior, not an atomic transaction
between world, quest and evidence files. Killing before durable admission can
lose the observation. Arbitrary financial backup rollback, every machine kill
point, cross-mod contraption copying and physical clients remain separate gates.
Preserve the evidence journal with the corresponding world/FTB backups; world
rollback never authorizes a payment reversal or another reward generation.

Validation counts, exact provider/JAR/log hashes and limitations are retained in
`artifacts/create-batch-validation-2026-10-19.json`. Standard Citizens and separate
Interactions artifacts exclude all probes, provider classes and fixture scripts.
