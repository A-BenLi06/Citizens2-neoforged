# Prepare walkto parameters before navigation

Walkto previously started navigation before assigning speed, range and distance margin,
so initial path construction could use old parameters. It also accepted non-finite or
negative navigation options before changing navigator state.

Coordinates and optional parameters are now evaluated and validated first. A valid
request uses Navigator's configuration callback, which applies its local parameters
before strategy construction. Default parameters remain untouched. Invalid values,
unspawned NPCs and unavailable stored worlds return failure without starting a request.

Direct tests reject invalid input before navigator access. Native tests assert parameter
values inside the strategy factory and verify that bad input preserves an existing request
on ordinary/packet NPCs. Completion/cancellation ownership and arbitrary provider side
effects remain separate work. Interactions remains an independent NeoForge JAR.
