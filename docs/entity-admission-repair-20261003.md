# Shared grenade admission compatibility repair, 2026-10-03

The completed zombie equipment-path diagnostic reached server Item.use but addFreshEntity returned false: the join event was cancelled and the Snowball discarded. Frozen Outbreak `0.4.0-equipment.1` validated `CampaignThrowables.TAG` (an alias of the shared throw tag) as an Outbreak room ID. The shared launcher stores a player UUID there, so Outbreak incorrectly rejected another game's grenade.

Main session explicitly assigned this one Outbreak repair to the zombie owner after the previous Outbreak owner stopped. Original repository/dev HEAD was `81b57bab`; no dirty work or active scoped build/test processes were present before the write. No branch, worktree, production or parent gitlink was changed.

EntityJoinLevelEvent now calls the small executable `OutbreakEntityAdmission` policy, which reads only the existing private `muxi_outbreak_supply_session` and `muxi_outbreak_equipment_session` tags. Either nonblank invalid private session still cancels and discards the entity. The shared tag is not queried or interpreted as a room ID. Untagged entities proceed through all existing owner/session-dimension tagging, infected membership and campaign-Mob guards. There is no event un-cancellation or unconditional success; item consumption, projectile spawning and effects are unchanged.

The directed regression executes this exact production policy with shared UUIDs, valid and stale private tags, mixed-tag attempts to bypass invalid ownership, multiple/closed rooms and exact queried-key checks. All 19 cases passed. Production wiring checks preserve cancellation/discard, owner/dimension association and infected boundaries. The complete Outbreak source compiled and packaged against the SHA-verified frozen framework `75aead1ee9f55fafeac46cb394c2e3b759737befc8cee1a6efb7852e114342b7`. No shared source or newly split dependency was built.

Run the directed checks with:

```text
py -3.12 tests/run_entity_admission.py --java-home <JDK21>
```

The frozen candidate is `build/entity-admission-repair/muxi-outbreak-0.4.0-equipment.1.jar`, SHA256 `ccb99f2a5d42b4c965b54da18fcce7ca6d9acc963b4f7aebe1dd2b21e174869e`, 2113747 bytes. Version remains unchanged; select the exact candidate by SHA/path. The previous native baseline is preserved separately as `build/entity-admission-repair/frozen-before-repair.jar`. This is a development candidate, not a deployment.

No new MC round was started while the performance owner owns the next window. Native validation remains pending a released window: run both zombie and Outbreak contexts with frozen Core71 SHA `c05a6bdb6cb97befb6e1d76e9120c7d1d6916028a0c9d759d21814ff728febbd`, frozen framework and this exact Outbreak candidate. Observe one real network grenade use per context, actual server entity acceptance, exactly one consumed item, native effect, cross-room/dimension isolation and exact original-state restoration on normal exit. Preserve unchanged UI and default watchdog; do not combine freshly split unverified dependencies. Unit coverage is not claimed as native cross-room acceptance. Shared Endgame remains paused.

Prior evidence: `../../muxi-zombie-challenge/docs/equipment-path-native-result-20261003.json`. Static build/test receipt: `entity-admission-repair-20261003.json`.

## Completed first native confirmation

The one scheduled replay confirmed zombie server CONSUME/count1->0, successful entity join, exactly one native explosion and rejection of both invalid private Outbreak tags. It stopped on an unresolved other-room target assertion before Outbreak throw stages. Target damage provenance was not durably captured; no production isolation defect or acceptance is claimed. Three exact zombie restoration receipts and normal0/0/0 process exits passed; the window was released. No second MC round was started. See `../../muxi-zombie-challenge/docs/shared-grenade-pair-native-20261003.md`.
