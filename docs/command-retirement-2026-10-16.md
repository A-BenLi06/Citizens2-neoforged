# Retire delayed NPC commands with their owners

Command dispatch now captures the trait's lifecycle revision and each selected
command's exact object identity. Removal/replacement of a command, clearing the
list, replacement/removal of the trait, despawn/respawn, NPC destruction and
registry replacement prevent old work from reaching payment or the native
dispatcher. Reusing an ID or UUID does not restore ownership. Editing another
command leaves the selected command valid, and fresh clicks after respawn work.

Admission rechecks ownership after dispatch events and payment preflight. If a
payment callback retires the owner while withdrawal is running, the completed
transaction is refunded before usage is published. Refund failures are logged.
The final queued native command also checks ownership; the original public
`NPCCommand.run(NPC, ServerPlayer)` entry point is retained for extensions.

Successful payment remains the usage boundary. This batch does not refund an
already admitted command whose owner is retired afterward, or reverse arbitrary
external command effects. Global click-cost policy and permission/scheduling
failures are documented separately from owner retirement.

The isolated creation fixture exercises ordinary and packet NPCs across removal,
replacement, two scheduler boundaries, fresh clicks and payment-provider callback
retirement. Its existing ordering assertions now wait for admitted commands to
execute before deleting them, so they test sequence rotation independently of
retirement.

Validation records and artifact hashes are in
`artifacts/command-retirement-validation-2026-10-16.json`.
