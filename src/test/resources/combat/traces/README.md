# Phase 0.4 combat trace fixtures

These are the labelled Phase 0.4 traces cited by
[the trace report](../../../../../docs/superpowers/audit/2026-10-29-trace-report.md).
Each folder contains the client trace (`client.jsonl`), the server ground truth
(`server.jsonl`) and the run metadata (`metadata.json`). Delays are one-way
milliseconds; TPS is the server tick rate.

Regenerate them with `./gradlew combatHarness` and then run
`python3 -I scripts/combat_trace_report.py`.

| Scenario | Delay | TPS | Run | Seed | Path |
| --- | ---: | ---: | ---: | ---: | --- |
| absorption_bundle | 0 ms | 20 | 0 | -5729300065746205162 | `src/test/resources/combat/traces/absorption_bundle/delay-0-tps-20/run-0` |
| ack_barrier | 0 ms | 20 | 0 | 6079061255829800470 | `src/test/resources/combat/traces/ack_barrier/delay-0-tps-20/run-0` |
| ack_zero_damage | 0 ms | 20 | 0 | -6248306671163849194 | `src/test/resources/combat/traces/ack_zero_damage/delay-0-tps-20/run-0` |
| c6_stronger_in_window | 0 ms | 20 | 0 | 4080040101673125398 | `src/test/resources/combat/traces/c6_stronger_in_window/delay-0-tps-20/run-0` |
| crystal_destroyed_by_explosion | 0 ms | 20 | 0 | 2488059281695524374 | `src/test/resources/combat/traces/crystal_destroyed_by_explosion/delay-0-tps-20/run-0` |
| death_respawn | 0 ms | 20 | 0 | 6483303697988404758 | `src/test/resources/combat/traces/death_respawn/delay-0-tps-20/run-0` |
| dimension_change | 0 ms | 20 | 0 | -2522327326753864170 | `src/test/resources/combat/traces/dimension_change/delay-0-tps-20/run-0` |
| disconnect_reconnect | 0 ms | 20 | 0 | 4211388417857242646 | `src/test/resources/combat/traces/disconnect_reconnect/delay-0-tps-20/run-0` |
| holed_target | 0 ms | 20 | 0 | 4482254309950703126 | `src/test/resources/combat/traces/holed_target/delay-0-tps-20/run-0` |
| holed_vertical | 0 ms | 20 | 0 | 2731377859142829590 | `src/test/resources/combat/traces/holed_vertical/delay-0-tps-20/run-0` |
| open_target | 0 ms | 20 | 0 | -145662037700950506 | `src/test/resources/combat/traces/open_target/delay-0-tps-20/run-0` |
| out_of_reach_attack | 0 ms | 20 | 0 | 5724520475868218902 | `src/test/resources/combat/traces/out_of_reach_attack/delay-0-tps-20/run-0` |
| piston_east_eye_redstone | 0 ms | 20 | 0 | -5134393305079790058 | `src/test/resources/combat/traces/piston_east_eye_redstone/delay-0-tps-20/run-0` |
| piston_east_eye_torch | 0 ms | 20 | 0 | 7529343691598808598 | `src/test/resources/combat/traces/piston_east_eye_torch/delay-0-tps-20/run-0` |
| piston_east_feet_redstone | 0 ms | 20 | 0 | 2190228987917321750 | `src/test/resources/combat/traces/piston_east_feet_redstone/delay-0-tps-20/run-0` |
| piston_east_feet_torch | 0 ms | 20 | 0 | 4141298344042645014 | `src/test/resources/combat/traces/piston_east_feet_torch/delay-0-tps-20/run-0` |
| piston_north_eye_redstone | 0 ms | 20 | 0 | 5477989314423113238 | `src/test/resources/combat/traces/piston_north_eye_redstone/delay-0-tps-20/run-0` |
| piston_north_eye_torch | 0 ms | 20 | 0 | 6912971474660967958 | `src/test/resources/combat/traces/piston_north_eye_torch/delay-0-tps-20/run-0` |
| piston_north_feet_redstone | 0 ms | 20 | 0 | -867303134264605162 | `src/test/resources/combat/traces/piston_north_feet_redstone/delay-0-tps-20/run-0` |
| piston_north_feet_torch | 0 ms | 20 | 0 | 3480503692679136790 | `src/test/resources/combat/traces/piston_north_feet_torch/delay-0-tps-20/run-0` |
| piston_south_eye_redstone | 0 ms | 20 | 0 | -4065415132386998762 | `src/test/resources/combat/traces/piston_south_eye_redstone/delay-0-tps-20/run-0` |
| piston_south_eye_torch | 0 ms | 20 | 0 | -1386557048194120170 | `src/test/resources/combat/traces/piston_south_eye_torch/delay-0-tps-20/run-0` |
| piston_south_feet_redstone | 0 ms | 20 | 0 | -1564935806025251306 | `src/test/resources/combat/traces/piston_south_feet_redstone/delay-0-tps-20/run-0` |
| piston_south_feet_torch | 0 ms | 20 | 0 | 4449536516105127446 | `src/test/resources/combat/traces/piston_south_feet_torch/delay-0-tps-20/run-0` |
| piston_west_eye_redstone | 0 ms | 20 | 0 | -1515448028904602090 | `src/test/resources/combat/traces/piston_west_eye_redstone/delay-0-tps-20/run-0` |
| piston_west_eye_torch | 0 ms | 20 | 0 | 297766870022571542 | `src/test/resources/combat/traces/piston_west_eye_torch/delay-0-tps-20/run-0` |
| piston_west_feet_redstone | 0 ms | 20 | 0 | 3697068107090839062 | `src/test/resources/combat/traces/piston_west_feet_redstone/delay-0-tps-20/run-0` |
| piston_west_feet_torch | 0 ms | 20 | 0 | 1323345759693915670 | `src/test/resources/combat/traces/piston_west_feet_torch/delay-0-tps-20/run-0` |
| rejected_placement | 0 ms | 20 | 0 | -1828148757886974442 | `src/test/resources/combat/traces/rejected_placement/delay-0-tps-20/run-0` |
| self_absorption | 0 ms | 20 | 0 | 2299234540630263318 | `src/test/resources/combat/traces/self_absorption/delay-0-tps-20/run-0` |
| self_damage_mid | 0 ms | 20 | 0 | 87908475812922902 | `src/test/resources/combat/traces/self_damage_mid/delay-0-tps-20/run-0` |
| self_damage_near | 0 ms | 20 | 0 | 2725274281118483990 | `src/test/resources/combat/traces/self_damage_near/delay-0-tps-20/run-0` |
| self_damage_zero | 0 ms | 20 | 0 | 2726811948129928726 | `src/test/resources/combat/traces/self_damage_zero/delay-0-tps-20/run-0` |
| server_stall | 0 ms | 20 | 0 | -8769749118717049322 | `src/test/resources/combat/traces/server_stall/delay-0-tps-20/run-0` |
| totem_friend | 0 ms | 20 | 0 | -3691367452780575210 | `src/test/resources/combat/traces/totem_friend/delay-0-tps-20/run-0` |
| two_crystals_stronger | 0 ms | 20 | 0 | 1182445947344602646 | `src/test/resources/combat/traces/two_crystals_stronger/delay-0-tps-20/run-0` |
| two_crystals_weaker | 0 ms | 20 | 0 | 8493437826620409366 | `src/test/resources/combat/traces/two_crystals_weaker/delay-0-tps-20/run-0` |
| window_boundary | 0 ms | 20 | 0 | -8411409530376468970 | `src/test/resources/combat/traces/window_boundary/delay-0-tps-20/run-0` |
| ack_barrier | 0 ms | 20 | 1 | 6079061255829800471 | `src/test/resources/combat/traces/ack_barrier/delay-0-tps-20/run-1` |
| ack_barrier | 0 ms | 20 | 2 | 6079061255829800468 | `src/test/resources/combat/traces/ack_barrier/delay-0-tps-20/run-2` |
| ack_barrier | 0 ms | 20 | 3 | 6079061255829800469 | `src/test/resources/combat/traces/ack_barrier/delay-0-tps-20/run-3` |
| ack_barrier | 0 ms | 20 | 4 | 6079061255829800466 | `src/test/resources/combat/traces/ack_barrier/delay-0-tps-20/run-4` |
| absorption_bundle | 0 ms | 20 | 1 | -5729300065746205161 | `src/test/resources/combat/traces/absorption_bundle/delay-0-tps-20/run-1` |
| absorption_bundle | 0 ms | 20 | 2 | -5729300065746205164 | `src/test/resources/combat/traces/absorption_bundle/delay-0-tps-20/run-2` |
| absorption_bundle | 0 ms | 20 | 3 | -5729300065746205163 | `src/test/resources/combat/traces/absorption_bundle/delay-0-tps-20/run-3` |
| absorption_bundle | 0 ms | 20 | 4 | -5729300065746205166 | `src/test/resources/combat/traces/absorption_bundle/delay-0-tps-20/run-4` |
| two_crystals_weaker | 0 ms | 20 | 1 | 8493437826620409367 | `src/test/resources/combat/traces/two_crystals_weaker/delay-0-tps-20/run-1` |
| two_crystals_weaker | 0 ms | 20 | 2 | 8493437826620409364 | `src/test/resources/combat/traces/two_crystals_weaker/delay-0-tps-20/run-2` |
| two_crystals_weaker | 0 ms | 20 | 3 | 8493437826620409365 | `src/test/resources/combat/traces/two_crystals_weaker/delay-0-tps-20/run-3` |
| two_crystals_weaker | 0 ms | 20 | 4 | 8493437826620409362 | `src/test/resources/combat/traces/two_crystals_weaker/delay-0-tps-20/run-4` |
| two_crystals_stronger | 0 ms | 20 | 1 | 1182445947344602647 | `src/test/resources/combat/traces/two_crystals_stronger/delay-0-tps-20/run-1` |
| two_crystals_stronger | 0 ms | 20 | 2 | 1182445947344602644 | `src/test/resources/combat/traces/two_crystals_stronger/delay-0-tps-20/run-2` |
| two_crystals_stronger | 0 ms | 20 | 3 | 1182445947344602645 | `src/test/resources/combat/traces/two_crystals_stronger/delay-0-tps-20/run-3` |
| two_crystals_stronger | 0 ms | 20 | 4 | 1182445947344602642 | `src/test/resources/combat/traces/two_crystals_stronger/delay-0-tps-20/run-4` |
| c6_stronger_in_window | 0 ms | 20 | 1 | 4080040101673125399 | `src/test/resources/combat/traces/c6_stronger_in_window/delay-0-tps-20/run-1` |
| c6_stronger_in_window | 0 ms | 20 | 2 | 4080040101673125396 | `src/test/resources/combat/traces/c6_stronger_in_window/delay-0-tps-20/run-2` |
| c6_stronger_in_window | 0 ms | 20 | 3 | 4080040101673125397 | `src/test/resources/combat/traces/c6_stronger_in_window/delay-0-tps-20/run-3` |
| c6_stronger_in_window | 0 ms | 20 | 4 | 4080040101673125394 | `src/test/resources/combat/traces/c6_stronger_in_window/delay-0-tps-20/run-4` |
| holed_target | 0 ms | 20 | 1 | 4482254309950703127 | `src/test/resources/combat/traces/holed_target/delay-0-tps-20/run-1` |
| holed_target | 0 ms | 20 | 2 | 4482254309950703124 | `src/test/resources/combat/traces/holed_target/delay-0-tps-20/run-2` |
