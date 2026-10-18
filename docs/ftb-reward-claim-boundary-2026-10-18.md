# FTB repeatable reward claim boundary

The [native monetary reward bridge](ftb-monetary-rewards-2026-10-18.md) now persists
explicit pre-reset intents and native acceptance before callbacks, then replays
Economy receipts durably. The historical inspection below explains its boundary;
full kill-point/financial-backup acceptance remains separately documented.

Inspected the pinned FTB Quests 2101.1.36 public signatures and bytecode locally on
2026-10-18, UTC+08:00. `TeamData.claimReward(player, reward, notify, timestamp)`
first calls `markRewardAsClaimed`, then calls `Reward.claim` if accepted.
`markRewardAsClaimed` stores the claim, calls the quest's repeatable reset, and
increments its completion count before returning. `CustomReward.claim` emits the
custom reward event after that boundary. Consequently a repeatable claim may
already have reset its claim-time/task state when a custom reward handler runs.

A callback that merely calls Economy credit, or derives a generation only from
current claim-time lookup, cannot establish durable retry/recovery across this
boundary. The remaining reward adapter needs explicit claim-generation and
beneficiary identity, durable intent/outcome handling and restart reconciliation
with FTB's ownership/reset semantics. A failed callback must not silently lose a
claimed reward; an event replay must not credit a new payment. The current task
query/delivery adapter and Economy receipt journal do not claim this requirement
is complete. The selected public contracts remain the starting point; no private
FTB state mutation or guessed legacy-ID mapping was added.

Local inspection artifacts: `artifacts/ftb-reward-public-contracts-2026-10-18.txt`
and `artifacts/ftb-teamdata-bytecode-2026-10-18.txt`. The provider artifact identity
is pinned in `docs/ecosystem-mods-1.21.1.lock.json`.
