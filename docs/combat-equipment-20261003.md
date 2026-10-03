# Combat and equipment recovery, 2026-10-03

The campaign keeps Source map geometry, furniture, lights, NAV, fixed supply positions and source weapon choices. Create stays WAITING with the original inventory/location; only the host's explicit start prepares players and enters the protected start room. Leaving that room starts the director.

## Damage diagnosis and mapping

Real Minecraft engine probes used the accepted previous jar and candidate jar with the installed Champions configuration: nine infected kinds, four room difficulties, and four world difficulties (144 cases each). Baseline Normal common front hits lost 4.08 HP on Peaceful/Normal, 3.04 on Easy and 6.12 on Hard; eight entities gained Champion state in that baseline run. Candidate Normal common hits lose 0.4 HP in all four world difficulties; zero tagged campaign mobs gained Champion state in the 144-case candidate run. Armor reductions remain effective; campaign mobs are excluded from extra world-difficulty scaling and Champion spawning, while ordinary-world mobs retain Champions.

Source common front damage 1/2/5/20 converts once from 100 survivor HP to Minecraft 20 HP: **0.2/0.4/1/4**, with half damage from behind. The primary community-update discussion explicitly describes the common values as existing per-hit damage, but its proposed new cvar names and averaged SI factors are proposals, not engine defaults: [firsthand mechanics discussion](https://github.com/Tsuey/L4D2-Community-Update/issues/208#issuecomment-1257076547). The [Valve cvar list](https://developer.valvesoftware.com/wiki/List_of_Left_4_Dead_2_console_commands_and_variables) documents the rear-hit multiplier 0.5.

Special infected claw and domination attacks are different channels, and player-controlled versus cvars cannot stand in for all cooperative bot attacks. Charger pounding is reported as 15 in every difficulty while other domination damage scales separately: [firsthand test report](https://github.com/Tsuey/L4D2-Community-Update/issues/208#issuecomment-1726676824). Witch/Tank incap rules also differ from ordinary claws. The native campaign currently uses Minecraft mob attacks; this fix converts its existing relative special-melee profiles once into MC units and preserves their difficulty curve. It does **not** implement Source pounce, choke, acid or exact Witch special AI. Existing TaCZ gun profiles already use MC damage and remain unchanged; gun body/head damage and survivor friendly fire are separate factors, not the incoming common damage curve. Room teammate friendly fire is 0/0.1/0.3/0.5; foreign room/player damage is rejected.

No temporary campaign armor was needed. Existing external inventory, armor/offhand, components, selected slot, exact position, mode/health and dimension are restored by the existing return snapshot transaction on victory/failure.

## Supply and exchange behavior

F uses a server ray within 3.5 blocks and wall clipping, with no client-controlled item ID/quantity. Source supply identities and finite per-player starting cache claims remain unchanged. Stackable equipment merges only with matching item/components up to 16; replacing an occupied category drops its previous exact stack into finite room stock. Gun exchange preserves magazine, chamber and upgrade components and does not refill dropped guns.

Native Q drops one and Ctrl+Q drops the exact removed stack for teammates. Drop placement searches nearby supported collision-free cells before taking stock; rejection returns the native removed stack. Generated depleted pickups are retired; source node identities remain inspectable. This prevents pickups from landing inside a wall/slab and avoids indefinitely exhausting the node cap during exchange.

Shared medical, grenade/projectile and native melee code lives in the framework; Outbreak supplies session policy and compatibility facades. See the framework `docs/shared-equipment.md` contract for stable canonical/legacy IDs and consume-once callbacks. Physical global F/key priority remains owned by the terminal integration module.

## Reproducible isolated QA

`tools/run_damage_probe.py` takes explicit --server, --framework, --jdk, --home, --artifact, --lr and --port paths. This is an engine fixture with synthetic server players, not multiplayer evidence.

`tools/run_equipment_network_qa.py` additionally takes --game and --world (a copied accepted native QA world). It starts a new loopback server and **two actual hidden Minecraft clients**, uses native network actions, item use and Q/Ctrl+Q, and stops all three normally. All launch homes must be new directories under this repository. Client GLFW visibility/focus receipts prove no Session2 focus was requested. The QA-only hardware mixin sets the OSHI WMI timeout to 2000 before SystemReport; no system service restarts are used.

The network route and combat are assisted by server teleports and kill calls to test lifecycle deterministically. Spawn/director entities, doors/checkpoints, finale clock/waves/Tank, actual medical and grenade consumption, win/all-down failure and snapshot returns use the real engine and real client connections. This is not an unassisted manual balance playthrough. Output: run-result.json, server/equipment-evidence.json, server/equipment-result.json and server/equipment-restore-receipts.json. QA mods must never be shipped to production.
