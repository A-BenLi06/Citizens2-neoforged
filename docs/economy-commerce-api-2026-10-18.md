# Accepted commerce API provider validation

Validated 2026-10-18, UTC+08:00. Neighboring Economy commit `698fbde`
(0.5.4-SNAPSHOT) exposes optional actor-scoped commerce capability revision 1
through Economy API revision 4. Read-only quotes include exact accepted terms;
execution rejects changed terms inside settlement and retains original receipts
on replay. Outcomes expose pending claims and recorded inventory recovery.

Economy passes 86 ordinary tests and 35 native inventory/SQLite checks on
NeoForge 21.1.248. Citizens and separate Interactions pass all 121 actual-provider
service checks with this probe-free provider, CMDCam and CreativeCore. The audit
now asserts the additive commerce capability. Ordinary consumer packaging was
restored and binary/source jars contain no runtime audit probes. Local logs and
hashes are in `artifacts/economy054-consumer-validation-2026-10-18.json`.

Existing NPC/dialogue menu consumers continue to delegate presentation and trades
to Economy. This batch does not add automatic NPC purchases or quest advancement.
Durable receipt subscriptions, process-crash reconciliation, FTB/crafting, item
migration and selected specialist/physical-client acceptance remain open.
Production and its prepared restart package are unchanged.
