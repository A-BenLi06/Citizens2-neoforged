# FTB party, reconnect and repeat reward acceptance

Date: 2026-10-22, UTC+08:00. Advances ADAPT-04/05/11.

The selected NeoForge 21.1.248, FTB Quests 2101.1.36, FTB Teams 2101.1.11 and
Economy 0.5.6 fixture now runs two distinct native connected ServerPlayer sessions.
Native party invite/join/leave operations own membership; ordinary FTB task
completion and TeamData.claimReward own acceptance and repeat resets. The fixture
uses embedded Minecraft connection pipelines, rather than physical clients.

Nineteen additional assertions verify:

- One shared pending claim retains its first member beneficiary. A second member
  cannot redirect it. Reconciliation while the beneficiary is disconnected pays
  that same account and records the original claim's transaction.
- A new player instance reconnects to the same native party. Both members' retries
  preserve one shared payment. The retired same-UUID instance cannot administer
  or admit another reward, including before native acceptance/journal effects.
- Individual rewards produce two separate member-owned intents, operation keys
  and payments. Duplicate native claims create no additional generation.
- A shared repeatable reward captures the pre-reset cycle. A second member needs
  new native task completion before earning the next cycle; the new claimant
  cannot change the previous cycle's beneficiary or receipt.
- A member can leave after native acceptance while payment is pending. Settlement
  keeps the original party/cycle and beneficiary. The former member cannot claim
  through an old party object using progress that belongs to that party.
- Exact account totals and the native balanced-ledger audit remain valid.

The complete run passes 44 existing XMod/script/reward assertions, 27 existing
commerce assertions and these 19 assertions, then shuts down successfully. An
initial fixture cleanup tried to republish an already active Economy provider;
removing that redundant publication respects the provider's ownership contract.
No production claim/payment behavior was changed to accommodate the fixture.
Provider/source/log hashes are in
[ftb-party-reward-validation-2026-10-22.json](ftb-party-reward-validation-2026-10-22.json).

The XMod init script supports `-PxmodAuditBuildDir`; pair it with a dedicated
`--project-cache-dir` to keep audit classes and Gradle stale-output history separate
from ordinary builds. The normal build/source sets and release artifacts are
unchanged. No production deployment or restart occurred.

This closes the stated native member/session permutations. Arbitrary concurrent
network input, every task/delivery recovery boundary, inconsistent financial backup
restores, legacy quest provenance and physical VR/non-VR migration remain separate
acceptance. The accepted claim journal and Economy ledger remain independent
durability domains that must be backed up together.
