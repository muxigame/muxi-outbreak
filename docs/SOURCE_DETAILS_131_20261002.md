# Lost School source details recovery — 131, 2026-10-02

Existing `dev` in the original checkout, based on `10a50629e0be0c8dde38e3e2c0f3fb05340e5189`. No deployment, push, new branch/worktree or production 008 changes.

The former conversion omitted Source prop instances and retained only point-light origin blocks. The new converter records all 1,644 static/dynamic instances and uses reviewed native approximations at original X/Z with cardinal yaw and bounded vertical support. 25 beds, 6 chairs, 16 tables, 6 cabinets and 44 crate forms are installed. These are cosmetic vanilla approximations; no Source mesh asset or extra loot is introduced. Off lamp models do not create emitters.

All 1,999,012 original non-air blockstates, including properties, remain at identical coordinates. Source NAV rectangles, start/checkpoint rooms and door approaches, spawn headroom and supply pickup space are reserved. All 1,435 reconstructed directed NAV route cells retain foot/head clearance and support. All 133 source supplies, shared stock, item limits, checkpoint/finale rules and chapter anchors are unchanged. Campaign beds reject both-hand interaction before vanilla use in the non-sleeping dimension, preventing explosions and map destruction.

VBSP21 WORLDLIGHTS v1 is decoded as 100-byte records, including the shadow offset; L4D2 header order is explicitly version/offset/length/fourCC. The selected HDR family contains 66 point lights matching source light entities and nine sky/ambient records. Point attenuation is approximated with occluded monochrome native light fields sampled every three cells, capped at radius 36 and level 15. The RGB/directional Source renderer is not reproduced. Gamma, fixed world time and dimension ambient light are unchanged.

Coverage: {'unmapped': 1178, 'skipped': 269, 'placed': 97, 'manual_review': 100}. Every excluded instance retains its model, source position/angles, scale and reason. Missing base-game meshes, tilted/scaled props, unsupported bed halves and occupied/protected space remain visible limitations; this is not full mesh fidelity. `data/muxi_outbreak/outbreak_details/lostschool.json` is the authoritative per-instance report.

Supply import now respects custom source paths and runs against the staged campaign before publication. Source details are included in the main BSP conversion pipeline. The standalone `tools/source_details.py --source <private maps> --resource <existing data/muxi_outbreak> --output <separate staging>` never writes directly over its input. Private BSP/NAV/VPK files and QA-only mixins are excluded from the distributable.

Validation: 18 Python tests with no skips; executable director regression; 3,110 supply-rule assertions. An isolated hidden real Minecraft network player exercised WAITING/start, infection ticking, both checkpoint doors, three finale waves, actual Tank death, natural 60-second rescue and client/server inventory restoration, then exited normally with code 0. Movement/combat were assisted. A separate real client sampled 133 identical route points at gamma 0.5, rendered chapter furniture, clicked beds with both hands in all three chapters and restored/exited normally.

Eye block-light zero samples: 110 → 66; both sky/block zero samples: 66 → 29; brighter 67, dimmer 0. Zero block light alone does not mean a black screen.

Local artifact `muxi-outbreak-0.3.6-source-details-qa.1.jar`, SHA-256 `96b14814ee23bea92e49673b8913b3f123d6ce73a8c41abf689d0ab479a7a32d`. Exact loaded artifact hashes and normal-exit JSON are retained in the two QA homes. The platform's MCEF two-client invitations/authenticated result receipt are not claimed by this isolated map acceptance.

A third small hidden client recorded ten unobstructed furniture closeups and six actual server `RightClickBlock` events: both hands in all three chapters, all canceled with SUCCESS and beds retained. It restored inventory/dimension and exited normally with code 0. `tests/map-details/NativeClientQA.java` and `tools/qa_map_details_client.py` preserve this QA outside the product. Pass `--details-only` for closeups; omit it for the complete route photometry plus closeups. Supply explicit installed `--server`, `--framework`, `--jdk`, `--game` and a new isolated `--home` under this repository.

Primary format cross-check: SourceIO's VBSP21 WorldLight decoder includes the 12-byte shadow offset: https://github.com/REDxEYE/SourceIO/blob/master/library/source1/bsp/datatypes/world_light.py . Private compiled point records were independently matched to all 66 light entity origins. No Source program was executed.
