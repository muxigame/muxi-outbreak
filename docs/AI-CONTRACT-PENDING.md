# Outbreak AI teammate candidate: native acceptance pending

The existing `dev` work is preserved. This candidate is not deployed and is not runtime acceptance.

## Shared interface

The module implements framework contract v1: `supportsAiTeammates()` and `startWithAiTeammates(ServerPlayer, List<RoomAiSeat>)`. It verifies the immutable plan against `RoomTeam.aiSeats()` and `aiLocked()`, then configures those exact IDs before the existing host start. Native start actions route through `runtime.roomAi.start`. Configured seats share the four-place capacity through `occupiedSeats()`, but never enter human membership, accounts or settlement. AI entities spawn only after explicit host start, not in WAITING.

## Native implementation

`CampaignMaid` uses TLM's registered entity type and client renderer. Its server task dispatch retains the native TaskGunAttack subclass, constrains targets to room-owned infected and guards firing through allies. TLM Brain navigation, native TaCZ projectiles, magazines, reserve extraction and reload are used. Starting weapons come from the scene's shared stock, with one starting-cache claim per seat. Ammo limits use the same implementation as humans, including launcher and ammo-pile exclusions. No infinite inventory or direct-damage shooting simulation is added.

Human and AI survivor states participate in director counts, infected targeting, friendly-fire rules, incapacitation, five-second rescue, safe-room arrival, finale and team failure. Chapter transition revives eliminated AI using the existing half-health rule. Temporary owned entities, their projectiles and bounded expiring chunk tickets are removed on room completion or failure. Empty-human rooms close. Native TLM blanket gun/explosion immunity is restricted for these campaign-owned entities so game damage rules remain authoritative; unrelated maids are unaffected. Bot GUI, external pickups, building and follow teleport are disabled.

## Appearance and checks

Installed resource metadata confirms `config/yes_steve_model/builtin/wine_fox/01_taisho_maid/ysm.json`. `WineFoxSkin` accepts model and texture only when YSM's actual native command registry suggestions contain the approved identities. No arbitrary client-selected skin is accepted. The resolver and rendering still require native verification; no model registration or visual success is claimed yet.

The frozen candidate uses the shared owner's formal framework artifact, commit f7001319e32e6915766962608cdc96bc2eca2b07, SHA256 c8818fece84986ddeb3878befb7edeafb2e69b432970bbf2b0c4c911182eb3b6. The earlier private compile-only dependency is superseded. The owner-only snapshot excludes the preexisting foreign stopping-priority edit while preserving it in the working tree. Frozen Python tests: 22 pass, 2 skips (independent voxel fixture and private VPK unavailable); director regressions pass; supply rules: 3,110 assertions; AI rescue/admission rules: 39 assertions. Native server/client QA fixtures compile and cover real terminal packets, physical navigation, native bullets, reserve/reload observations, friendly damage, downing/rescue, chapter boundaries and independent owned-entity cleanup counts. No MC instance has been started for this feature.

Pending input: the parent-coordinated MC window after the store releases it. The private Outbreak test configuration must use spawn-animals=true because false deletes TLM maids; the shared debug script and production configuration are not changed. Native movement, combat, rescue, full chapter/finale progression, exit/settlement, YSM identity and appearance must pass before release acceptance or publication. No production files, credentials, branches or worktrees were created or changed.
