# CmdCam and Yuuniverse Economy integration

Provider decisions recorded on 2026-09-13, UTC+08:00: old `cam-server` actions are handled by CmdCam; economic services/API compatibility used by the old plugin stack belong to Yuuniverse Economy.

## Implemented bridge behavior

- The effective alias snapshot ignores the old generated empty `shop` and `cam-server` placeholders. Explicit nonempty alias overrides remain effective, unrelated empty aliases remain unavailable, and the configuration file is not rewritten. A malformed reload retains the previous complete snapshot.
- `shop <catalog-id>` player actions call Economy's public validation/presentation API. Unicode IDs and quoted names with spaces do not depend on Brigadier's limited unquoted-string alphabet. Native literal subcommands, such as `shop list`, continue through the real dispatcher. Console commands do not silently acquire the dialogue player's identity to open a menu.
- Economy owns catalog lookup, online recipient validation, account initialization and menu creation. Missing shops/pages fail before payment. The NPC mod does not create its own shop inventory, balances, prices or stock database.
- `eco give|take|set <recipient> <amount> [currency]` resolves the selected currency through Economy's catalog and applies that currency's precision. The recipient can be a current online name, a unique stored offline name, or an existing UUID. Unknown/ambiguous names do not create guessed offline identities or receive money. `balance <recipient> [currency]` uses the same resolution.
- `cam-server start` uses CmdCam's registered command. Before execution, the bridge checks the actual scene, nonempty points and player selection. CmdCam's zero command return value is not itself classified as a failure; provider state supplies the required preconditions.

## Camera provider and saved data

Validated provider artifacts:

| Provider | Version |
|---|---|
| Minecraft / NeoForge | 1.21.1 / 21.1.248 |
| CmdCam | `CMDCam_NEOFORGE_v2.2.9_mc1.21.1.jar` |
| CreativeCore | `CreativeCore_NEOFORGE_v2.13.41_mc1.21.1.jar` (CmdCam requires at least 2.13.32) |
| Yuuniverse Economy | New local `0.4.2-SNAPSHOT` API/build |

CmdCam was obtained from its published [Modrinth version](https://modrinth.com/mod/cmdcam/version/ZibX3UR8). The downloaded jar's verified SHA-512 is:

`5a3bc84321d5d795e20f68e5b439e8f3daa5a00de0cea379d8d2579f727b5a01336aaa6dc0da24ca5b8e41e1b0eff449f1af47ee0473a9fccc9d97b68aa7638f`.

The scene lookup is dimension-specific:

| Original scene | World data file |
|---|---|
| `dead_end1` | `<world>/data/cmdcam_Scenes.dat` |
| `uDays_intro`, `uDays_intro2`, `uDays_intro3` | `<world>/DIM1/data/cmdcam_Scenes.dat` |

The initial fixture copied only overworld data, and its four-scene check failed. Direct NBT inspection established that the intro scenes exist in the old End save. The fixture was corrected to preserve the original dimension layout and execute each action in its proper dimension. No replacement path was fabricated, and the four-scene acceptance requirement was retained.

Production currently has compatible CreativeCore but no CmdCam jar. Clients that should play the camera paths need the corresponding CmdCam/CreativeCore support. Preserve existing production scene files during deployment; the preparation script writes only the isolated fixture.

## Economic API ownership

| Old consumer/capability | Owner in the NeoForge design |
|---|---|
| GemsEconomy balances/currencies, Vault-style monetary consumers, Citizens payments/rewards | Yuuniverse Economy ledger and public currency/account/transaction APIs; callers adapt their original syntax. |
| EconomyShopGUI system shops and NPC shop entry | Yuuniverse Economy catalog, UI, stock, settlement and delivery claims; public shop validation/opening API. |
| QuickShop coordinate shops | Yuuniverse Economy coordinate-shop services, including physical inventory and settlement rules. |
| TimeIsMoney payouts | Yuuniverse Economy payout service and ledger. |
| Essentials-style balance/eco calls and future quest monetary rewards | Economy API for the financial operation; nonfinancial command/quest behavior remains with its own service. |
| Financial item issuance/redemption | Belongs to Economy's commerce/financial service contract; generic NPC item rewards and old item-metadata migration remain distinct work. |

This ownership decision is not a claim that every old economic feature is already complete. The new public API work in this batch is shop preflight/presentation, currency resolution and existing-player account resolution. The previously implemented monetary provider, ledger, shop and payout services remain the foundation.

## Fresh data findings

The old dialogue census contains **2,277 `eco` actions; 2,276 explicitly select a currency**. The prior adapter accepted only the four-token default-currency form, so the earlier audit understated this compatibility gap. Most of these actions address named recipients, including the tax account, rather than the current player. The new adapter handles the five-token format and known offline recipients through Economy.

The current three referenced currency names all resolve to the existing Economy catalog. Production's four configured currencies have scale 4; tests additionally use scale 2 and scale 3 to detect accidental reuse of the default scale.

Of **58 shop actions / 33 unique IDs**, 55 actions / 31 IDs match the current catalog exactly. These two old references are absent from both the original shop files and the imported catalog:

- `金城银行存款`: one reference.
- `风巽贵金属积存赎回`: two references. A similarly named `风巽银行贵金属积存赎回` exists, but that mapping has not been assumed.

Two named financial recipients lack a confirmed current account mapping:

- `VerticalYeti503`: 29 actions.
- `Santoesia`: one action.

Old/new user caches and the checked Essentials/GemsEconomy files did not provide a confirmed mapping. The user has been asked for the intended shop destinations and recipient UUIDs/names. Until clarified, these references fail rather than changing a different account or shop. Catalog ID matches alone do not certify every old shop offer or full dialogue workflow.

## Evidence and delivery scope

- `artifacts/repair-native-services-runtime248.log`: initial incomplete-dimension fixture failure.
- `artifacts/repair-native-services-runtime248-dimensions.log`: 36 successful actual-provider checks, including all four scene payloads and the new finance behavior.
- `artifacts/repair-native-services-runtime248-final.log`: final packaged-provider rerun, all 36 checks pass.
- `artifacts/repair-native-services-dialogue-tests.xml`: all 40 dialogue audit tests pass.
- `artifacts/repair-native-services-regression248.log`: all 107 NPC assertions and 223 existing runtime probes pass.
- `artifacts/repair-native-services-final248.log`: normal release build and all 124 ordinary tests pass.
- The paired Economy implementation is committed as `b44ced0`; all 46 economy tests and its normal build pass. Both projects' release/source jars exclude runtime audit classes.
- `tools/service-runtime-audit/README.md`: reproducible isolated provider audit.
- `artifacts/service-shop-routing-audit.json`: the unmatched shop references and source filenames.
- Final regression/build results and commits are recorded in `walkthrough.md`.

No production jar, configuration, scene file or balance was changed. Existing full-parity gaps—such as permission-group mutation, quests, other dialogue features and native command differences—remain separate work. Real client rendering/handshake and full-modpack acceptance remain outstanding.
