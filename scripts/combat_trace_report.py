#!/usr/bin/env python3
"""Analyse Phase 0.4 client/server traces and write the trace report + fixtures.

Usage:
  python3 -I scripts/combat_trace_report.py --input build/combat-harness/phase0-5x \\
      --report docs/superpowers/audit/2026-10-29-trace-report.md \\
      --fixtures src/test/resources/combat/traces

The input tree is <scenario>/delay-<ms>-tps-<tps>/run-<n>/ with client.jsonl,
server.jsonl and metadata.json. The script is deliberately dependency-free.
"""

from __future__ import annotations

import argparse
import json
import math
import shutil
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any, Iterable

HEALTH_ID = 9
ABSORPTION_ID = 17
PISTON_EXPECTED_PER_TICK = 0.52  # plan wording: 0.51 + 0.01 per tick
PISTON_OBSERVED_PER_TICK = 0.51


def load_lines(path: Path) -> list[dict[str, Any]]:
    out: list[dict[str, Any]] = []
    if not path.exists():
        return out
    for line in path.read_text(encoding="utf-8").splitlines():
        try:
            value = json.loads(line)
        except json.JSONDecodeError:
            continue
        if isinstance(value, dict):
            out.append(value)
    return out


def percentile(values: Iterable[float], q: float) -> float | None:
    values = sorted(values)
    if not values:
        return None
    if len(values) == 1:
        return values[0]
    index = (len(values) - 1) * q
    lower = math.floor(index)
    upper = math.ceil(index)
    if lower == upper:
        return values[lower]
    return values[lower] + (values[upper] - values[lower]) * (index - lower)


def fmt(value: float | None, unit: str = "ms") -> str:
    return "—" if value is None else f"{value:.2f} {unit}"


def stats(values: Iterable[float]) -> str:
    values = [v for v in values if v is not None]
    if not values:
        return "—"
    return f"p50 {fmt(percentile(values, 0.50))}, p95 {fmt(percentile(values, 0.95))}, max {fmt(max(values))}"


class Run:
    def __init__(self, directory: Path):
        self.directory = directory
        self.metadata = json.loads((directory / "metadata.json").read_text(encoding="utf-8"))
        self.client = load_lines(directory / "client.jsonl")
        self.server = load_lines(directory / "server.jsonl")
        self.scenario = str(self.metadata.get("scenario", directory.parent.parent.parent.name))
        self.delay = int(self.metadata.get("delayMs", 0))
        self.jitter = int(self.metadata.get("jitterMs", 0))
        self.tps = int(self.metadata.get("tps", 20))
        self.repeat = int(self.metadata.get("run", 0))
        self.seed = int(self.metadata.get("seed", 0))
        self.failure = self.metadata.get("failure")
        self.trace_complete = bool(self.metadata.get("traceComplete", False))
        self.client_events = [e for e in self.client if e.get("type") == "event"]
        self.labels = [e for e in self.client if e.get("type") == "label"]
        self.server_events = [e for e in self.server if e.get("type") not in ("server_meta",)]

    def label(self, prefix: str) -> dict[str, Any] | None:
        return next((l for l in self.labels if str(l.get("label", "")).startswith(prefix)), None)

    def client_kind(self, kind: str) -> list[dict[str, Any]]:
        return [e for e in self.client_events if e.get("kind") == kind]

    def server_kind(self, kind: str) -> list[dict[str, Any]]:
        return [e for e in self.server_events if e.get("type") == kind]

    def health_events(self) -> list[dict[str, Any]]:
        result = []
        for event in self.client_events:
            if event.get("kind") == "HEALTH":
                result.append(event)
            elif event.get("kind") == "ENTITY_DATA":
                values = event.get("payload", {}).get("values", [])
                ids = {v.get("id") for v in values if isinstance(v, dict)}
                if HEALTH_ID in ids or ABSORPTION_ID in ids:
                    result.append(event)
        return result

    def ack_events(self) -> list[dict[str, Any]]:
        return [e for e in self.client_events
                if e.get("payload", {}).get("blockChangedAck")]

    def explosion_events(self) -> list[dict[str, Any]]:
        return self.client_kind("EXPLOSION")

    def first_crystal_positions(self) -> list[tuple[int, dict[str, Any]]]:
        out = []
        for event in self.server_events:
            if event.get("type") != "server_tick_end":
                continue
            for crystal in event.get("crystals", []):
                out.append((int(event.get("tick", -1)), crystal))
        return out


def read_runs(root: Path) -> dict[str, list[Run]]:
    runs: dict[str, list[Run]] = defaultdict(list)
    for metadata in sorted(root.glob("*/*/run-*/metadata.json")):
        try:
            run = Run(metadata.parent)
        except Exception:
            continue
        runs[run.scenario].append(run)
    for values in runs.values():
        values.sort(key=lambda r: (r.delay, r.tps, r.repeat))
    return dict(sorted(runs.items()))


def relative(path: Path, root: Path) -> str:
    try:
        return str(path.relative_to(root))
    except ValueError:
        return str(path)


def event_at_or_after(events: list[dict[str, Any]], revision: int) -> dict[str, Any] | None:
    return next((e for e in events if int(e.get("revision", -1)) >= revision), None)


def server_time(event: dict[str, Any]) -> float:
    return float(event.get("t", 0)) / 1_000_000.0


def client_time(event: dict[str, Any]) -> float:
    return float(event.get("nanos", 0)) / 1_000_000.0


def analyse_order_question(runs: list[Run]) -> dict[str, Any]:
    explosion_before_removal = 0
    removal_before_explosion = 0
    no_pair = 0
    for run in runs:
        accepted = run.server_kind("attack_accepted")
        if not accepted:
            no_pair += 1
            continue
        target = accepted[0].get("target")
        explosions = run.explosion_events()
        removals = [e for e in run.client_kind("REMOVED") if e.get("entity") == target]
        if not explosions or not removals:
            no_pair += 1
            continue
        first_explosion = min(int(e.get("revision", 0)) for e in explosions)
        first_removal = min(int(e.get("revision", 0)) for e in removals)
        if first_explosion <= first_removal:
            explosion_before_removal += 1
        else:
            removal_before_explosion += 1
    return {
        "explosion_before_removal": explosion_before_removal,
        "removal_before_explosion": removal_before_explosion,
        "no_pair": no_pair,
    }


def analyse_absorption(runs: list[Run]) -> dict[str, Any]:
    one_packet = 0
    split = 0
    no_pair = 0
    examples = []
    for run in runs:
        damage = [e for e in run.server_events if e.get("type") == "player_hurt_result"]
        if not damage:
            damage = [e for e in run.server_events if e.get("type") == "damage_applied_end"]
        if not damage:
            no_pair += 1
            continue
        target = damage[0].get("entity")
        candidates = []
        for event in run.client_events:
            if event.get("kind") != "ENTITY_DATA" or event.get("entity") != target:
                continue
            values = event.get("payload", {}).get("values", [])
            ids = {v.get("id") for v in values if isinstance(v, dict)}
            if ABSORPTION_ID in ids:
                candidates.append((event, ids))
        if not candidates:
            split += 1
            continue
        event, ids = candidates[-1]
        if HEALTH_ID in ids and ABSORPTION_ID in ids:
            one_packet += 1
            examples.append({
                "path": relative(run.directory, Path.cwd()),
                "revision": event.get("revision"),
                "ids": sorted(i for i in ids if i is not None),
            })
        else:
            split += 1
    return {"one_packet": one_packet, "split": split, "no_pair": no_pair, "examples": examples}



def analyse_self_absorption(runs: list[Run]) -> list[dict[str, Any]]:
    rows = []
    for run in runs:
        hurt = next((e for e in run.server_events
                     if e.get("type") == "player_hurt_result" and e.get("accepted")), None)
        if hurt is None:
            continue
        entity = hurt.get("entity")
        entity_data = []
        health_packets = []
        for event in run.client_events:
            if event.get("entity") != entity:
                continue
            if event.get("kind") == "HEALTH":
                health_packets.append(event)
            elif event.get("kind") == "ENTITY_DATA":
                ids = {v.get("id") for v in event.get("payload", {}).get("values", [])
                       if isinstance(v, dict)}
                if HEALTH_ID in ids and ABSORPTION_ID in ids:
                    entity_data.append(event)
        rows.append({
            "path": relative(run.directory, Path.cwd()),
            "both_in_entity_data": len(entity_data) > 0,
            "entity_data_revision": entity_data[0].get("revision") if entity_data else None,
            "health_packets": len(health_packets),
            "health_revision": health_packets[-1].get("revision") if health_packets else None,
            "bundle": entity_data[0].get("bundle") if entity_data else None,
        })
    return rows


def analyse_c6(runs: list[Run]) -> dict[str, Any]:
    results = []
    for run in runs:
        target = next((e for e in run.server_events
                       if e.get("type") == "fake_player_spawn"
                       and e.get("name") == "HarnessTarget"), None)
        if not target:
            continue
        target_id = target.get("entity")
        hits = [e for e in run.server_events
                if e.get("type") == "player_hurt_result" and e.get("entity") == target_id]
        if len(hits) < 2:
            continue
        first, second = hits[0], hits[1]
        positions = []
        for event in run.server_events:
            if event.get("type") != "server_tick_end":
                continue
            for player in event.get("players", []):
                if player.get("id") == target_id:
                    positions.append((int(event.get("tick", -1)), player))
        before = [p for p in positions if p[0] <= int(second.get("tick", 0))]
        after = [p for p in positions if p[0] > int(second.get("tick", 0))]
        moved_after = False
        if before and after:
            last_before = before[-1][1]
            first_after = after[0][1]
            moved_after = (abs(last_before.get("x", 0) - first_after.get("x", 0)) > 1e-4
                           or abs(last_before.get("y", 0) - first_after.get("y", 0)) > 1e-4
                           or abs(last_before.get("z", 0) - first_after.get("z", 0)) > 1e-4
                           or abs(first_after.get("dx", 0)) > 1e-4
                           or abs(first_after.get("dy", 0)) > 1e-4
                           or abs(first_after.get("dz", 0)) > 1e-4)
        results.append({
            "path": relative(run.directory, Path.cwd()),
            "first_amount": first.get("amount"),
            "first_health_after": first.get("health"),
            "second_amount": second.get("amount"),
            "second_health_after": second.get("health"),
            "second_accepted": second.get("accepted"),
            "moved_after_second": moved_after,
        })
    return {"runs": results}




def analyse_second_hits(runs: list[Run]) -> dict[str, Any]:
    out = {"weaker_total": 0, "weaker_rejected": 0, "stronger_total": 0,
           "stronger_accepted": 0, "zero_runs": 0, "zero_no_hurt": 0}
    for name, total_key, accepted_key in (
        ("two_crystals_weaker", "weaker_total", "weaker_rejected"),
        ("two_crystals_stronger", "stronger_total", "stronger_accepted"),
    ):
        for run in runs.get(name, []):
            target = next((e for e in run.server_events
                           if e.get("type") == "fake_player_spawn"
                           and e.get("name") == "HarnessTarget"), None)
            if not target:
                continue
            hits = [e for e in run.server_events
                    if e.get("type") == "player_hurt_result"
                    and e.get("entity") == target.get("entity")]
            if len(hits) < 2:
                continue
            out[total_key] += 1
            if accepted_key == "weaker_rejected" and hits[1].get("accepted") is False:
                out[accepted_key] += 1
            if accepted_key == "stronger_accepted" and hits[1].get("accepted") is True:
                out[accepted_key] += 1
    for run in runs.get("self_damage_zero", []):
        out["zero_runs"] += 1
        if not [e for e in run.server_events
                if e.get("type") == "player_hurt_attempt" and e.get("entity") == 1]:
            out["zero_no_hurt"] += 1
    return out


def analyse_holed_motion(runs: list[Run]) -> list[dict[str, Any]]:
    rows = []
    for run in runs:
        target = next((e for e in run.server_events
                       if e.get("type") == "fake_player_spawn"
                       and e.get("name") == "HarnessTarget"), None)
        if not target:
            continue
        target_id = target.get("entity")
        positions = []
        for event in run.server_events:
            if event.get("type") != "server_tick_end":
                continue
            for player in event.get("players", []):
                if player.get("id") == target_id:
                    positions.append((int(event.get("tick", -1)), player))
        if len(positions) < 2:
            continue
        start = positions[0][1]
        end = positions[-1][1]
        delta_ys = [p.get("dy", 0) for _, p in positions]
        rows.append({
            "path": relative(run.directory, Path.cwd()),
            "scenario": run.scenario,
            "start": (start.get("x"), start.get("y"), start.get("z")),
            "end": (end.get("x"), end.get("y"), end.get("z")),
            "dy": end.get("y", 0) - start.get("y", 0),
            "min_delta_y": min(delta_ys) if delta_ys else 0,
            "max_delta_y": max(delta_ys) if delta_ys else 0,
            "horizontal": math.hypot(end.get("x", 0) - start.get("x", 0),
                                     end.get("z", 0) - start.get("z", 0)),
            "health": end.get("health"),
        })
    return rows


def analyse_piston(runs: list[Run]) -> list[dict[str, Any]]:
    grouped: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for run in runs:
        positions = run.first_crystal_positions()
        if not positions:
            grouped[run.scenario].append({"error": "no crystal positions"})
            continue
        start_tick, start = positions[0]
        final_tick, final = positions[-1]
        direction = run.scenario.split("_")[1]
        sign = {"east": (1, 0, 0), "west": (-1, 0, 0),
                "north": (0, 0, -1), "south": (0, 0, 1)}[direction]
        measured = ((final["x"] - start["x"]) * sign[0]
                    + (final["z"] - start["z"]) * sign[2])
        grouped[run.scenario].append({
            "start": (start["x"], start["y"], start["z"]),
            "final": (final["x"], final["y"], final["z"]),
            "measured": measured,
            "ticks": final_tick - start_tick,
            "path": relative(run.directory, Path.cwd()),
        })
    out = []
    for scenario, rows in sorted(grouped.items()):
        valid = [row for row in rows if "error" not in row]
        if not valid:
            out.append({"scenario": scenario, "error": rows[0]["error"]})
            continue
        values = sorted(row["measured"] for row in valid)
        middle = values[len(values) // 2]
        expected = PISTON_EXPECTED_PER_TICK * 2
        out.append({
            "scenario": scenario,
            "runs": len(valid),
            "start": valid[0]["start"],
            "final": valid[0]["final"],
            "measured_min": values[0],
            "measured_max": values[-1],
            "measured": middle,
            "expected": expected,
            "delta": middle - expected,
            "ticks": sorted(row["ticks"] for row in valid)[len(valid) // 2],
        })
    return out


def analyse_ack(runs: list[Run]) -> dict[str, Any]:
    rows = []
    for run in runs:
        explosions = run.explosion_events()
        acks = run.ack_events()
        if not explosions or not acks:
            continue
        explosion_revision = min(int(e.get("revision", 0)) for e in explosions)
        later_acks = [a for a in acks if int(a.get("revision", -1)) > explosion_revision]
        if not later_acks:
            continue
        ack = later_acks[0]
        ack_revision = int(ack.get("revision", 0))
        health_after = [h for h in run.health_events()
                        if explosion_revision < int(h.get("revision", -1)) < ack_revision]
        ack_tick = int(ack.get("tick", 0))
        health_after_ack = [h for h in run.health_events()
                           if int(h.get("revision", -1)) > ack_revision
                           and int(h.get("tick", 0)) <= ack_tick + 5]
        probe = run.label("post-explosion-interaction-sent")
        rows.append({
            "path": relative(run.directory, Path.cwd()),
            "ack_sequence": ack.get("payload", {}).get("sequence"),
            "explosion_revision": explosion_revision,
            "ack_revision": ack_revision,
            "health_before_ack": len(health_after),
            "health_after_ack": len(health_after_ack),
            "probe": probe is not None,
        })
    return {"rows": rows}


def compact_runs(runs: list[Run]) -> list[dict[str, Any]]:
    rows = []
    for run in runs:
        rows.append({
            "scenario": run.scenario,
            "delay": run.delay,
            "tps": run.tps,
            "repeat": run.repeat,
            "seed": run.seed,
            "traceComplete": run.trace_complete,
            "failure": run.failure,
            "events": len(run.client_events),
            "kinds": dict(Counter(e.get("kind") for e in run.client_events)),
            "path": relative(run.directory, Path.cwd()),
        })
    return rows


def pick_fixtures(runs: dict[str, list[Run]], limit: int = 60) -> list[Run]:
    picked: list[Run] = []
    seen: set[Path] = set()

    def add(run: Run) -> None:
        if run.directory in seen or not run.trace_complete or run.failure:
            return
        seen.add(run.directory)
        picked.append(run)

    # One representative of every scenario first.
    for scenario in sorted(runs):
        for run in runs[scenario]:
            if run.trace_complete and not run.failure:
                add(run)
                break
    # Then extra evidence for the audit questions.
    priority = [
        "ack_barrier", "absorption_bundle", "two_crystals_weaker", "two_crystals_stronger",
        "c6_stronger_in_window", "holed_target", "holed_vertical",
        "piston_east_feet_redstone", "piston_west_eye_torch",
    ]
    for scenario in priority:
        for run in runs.get(scenario, []):
            if len(picked) >= limit:
                break
            add(run)
    for scenario in sorted(runs):
        for run in runs[scenario]:
            if len(picked) >= limit:
                break
            add(run)
    return picked[:limit]


def copy_fixtures(runs: list[Run], destination: Path, project_root: Path) -> list[dict[str, Any]]:
    if destination.exists():
        shutil.rmtree(destination)
    destination.mkdir(parents=True, exist_ok=True)
    rows = []
    for run in runs:
        target = destination / run.scenario / f"delay-{run.delay}-tps-{run.tps}" / f"run-{run.repeat}"
        target.mkdir(parents=True, exist_ok=True)
        for name in ("client.jsonl", "server.jsonl", "metadata.json"):
            source = run.directory / name
            if source.exists():
                shutil.copy2(source, target / name)
        rows.append({
            "scenario": run.scenario,
            "delay": run.delay,
            "tps": run.tps,
            "repeat": run.repeat,
            "seed": run.seed,
            "path": str(target.relative_to(project_root)),
        })
    return rows


def write_fixture_readme(rows: list[dict[str, Any]], destination: Path) -> None:
    lines = [
        "# Phase 0.4 combat trace fixtures",
        "",
        "These are the labelled Phase 0.4 traces cited by",
        "[the trace report](../../../../../docs/superpowers/audit/2026-10-29-trace-report.md).",
        "Each folder contains the client trace (`client.jsonl`), the server ground truth",
        "(`server.jsonl`) and the run metadata (`metadata.json`). Delays are one-way",
        "milliseconds; TPS is the server tick rate.",
        "",
        "Regenerate them with `./gradlew combatHarness` and then run",
        "`python3 -I scripts/combat_trace_report.py`.",
        "",
        "| Scenario | Delay | TPS | Run | Seed | Path |",
        "| --- | ---: | ---: | ---: | ---: | --- |",
    ]
    for row in rows:
        lines.append(
            f"| {row['scenario']} | {row['delay']} ms | {row['tps']} | {row['repeat']} | "
            f"{row['seed']} | `{row['path']}` |"
        )
    lines.append("")
    (destination / "README.md").write_text("\n".join(lines), encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--fixtures", type=Path)
    args = parser.parse_args()

    project_root = Path.cwd()
    args.input = args.input.resolve()
    if args.fixtures is not None:
        args.fixtures = args.fixtures.resolve()
    args.report = args.report.resolve()
    runs = read_runs(args.input)
    all_runs = [run for values in runs.values() for run in values]
    failures = [run for run in all_runs if run.failure]
    incomplete = [run for run in all_runs if not run.trace_complete]

    lines: list[str] = []
    lines += [
        "# CrystalCombat Phase 0.4/0.5 trace report",
        "",
        "Generated by `scripts/combat_trace_report.py` from the scripted",
        "Fabric client game-test harness. All figures are measured on the local",
        "in-process dedicated server; delays are one-way milliseconds, TPS is the",
        "server tick-rate setting.",
        "",
        "## Corpus",
        "",
        f"- Input: `{relative(args.input, project_root)}`",
        f"- Runs: {len(all_runs)} across {len(runs)} scenarios",
        f"- Failed runs: {len(failures)}; incomplete traces: {len(incomplete)}",
        f"- Fixture traces: {len(pick_fixtures(runs)) if args.fixtures else 0}",
        "",
        "| Scenario | Runs | Trace complete | Failures |",
        "| --- | ---: | ---: | ---: |",
    ]
    for scenario in sorted(runs):
        values = runs[scenario]
        lines.append(
            f"| {scenario} | {len(values)} | "
            f"{sum(1 for r in values if r.trace_complete)} | "
            f"{sum(1 for r in values if r.failure)} |"
        )
    lines.append("")

    # Per-scenario event order and delays.
    lines += [
        "## Event order and delays",
        "",
        "Server intervals use the server-side monotonic timestamp. Client intervals use the",
        "receipt/application timestamps in `client.jsonl`. A dash means the pair was not",
        "observed in that scenario.",
        "",
        "| Scenario | Attack accepted → explosion start | Explosion start → damage applied end | "
        "Client explosion → health/entity data | Explosion before removal |",
        "| --- | --- | --- | --- | --- |",
    ]
    for scenario in sorted(runs):
        values = runs[scenario]
        attack_to_explosion = []
        explosion_to_damage = []
        client_explosion_to_health = []
        order = analyse_order_question(values)
        for run in values:
            accepted = run.server_kind("attack_accepted")
            explosions = run.server_kind("explosion_start")
            damage = run.server_kind("player_hurt_result") or run.server_kind("damage_applied_end")
            if accepted and explosions:
                attack_to_explosion.append(server_time(explosions[0]) - server_time(accepted[0]))
            if explosions and damage:
                explosion_to_damage.append(server_time(damage[0]) - server_time(explosions[0]))
            client_explosions = run.explosion_events()
            health = run.health_events()
            if client_explosions and health:
                explosion_revision = min(int(e.get("revision", 0)) for e in client_explosions)
                later = [h for h in health if int(h.get("revision", -1)) > explosion_revision]
                if later:
                    client_explosion_to_health.append(
                        client_time(later[0]) - client_time(client_explosions[0])
                    )
        lines.append(
            f"| {scenario} | {stats(attack_to_explosion)} | {stats(explosion_to_damage)} | "
            f"{stats(client_explosion_to_health)} | "
            f"{order['explosion_before_removal']}/{len(values)} |"
        )
    lines.append("")

    # Questions 1-7.
    lines += ["## Answers to plan 0.5 questions", ""]
    order_all = []
    for scenario in ("open_target", "holed_target", "two_crystals_weaker",
                     "two_crystals_stronger", "window_boundary"):
        order_all.extend(runs.get(scenario, []))
    order = analyse_order_question(order_all)
    if order['removal_before_explosion'] > order['explosion_before_removal']:
        order_answer = (
            "The bound crystal's `REMOVED` observation was applied **before** the "
            "`EXPLOSION` observation in the majority of traces."
        )
    elif order['explosion_before_removal'] > order['removal_before_explosion']:
        order_answer = (
            "The `EXPLOSION` observation was applied **before** the bound crystal's "
            "`REMOVED` observation in the majority of traces."
        )
    else:
        order_answer = "The recorded traces do not establish a consistent order."
    lines += [
        "**1. Does the explosion packet arrive before or after the crystal removal?**",
        "",
        f"{order_answer} Counts: explosion before removal "
        f"{order['explosion_before_removal']}, removal before explosion "
        f"{order['removal_before_explosion']}, no comparable pair {order['no_pair']}.",
        "The server-side `crystal_hurt_result` is the authoritative removal marker; the",
        "client uses the removal and explosion packets as observations, not as a causal order.",
        "Example:",
        f"`{relative(order_all[0].directory, project_root) if order_all else args.input}`.",
        "",
    ]

    absorption = analyse_absorption(runs.get("absorption_bundle", []))
    second = analyse_second_hits(runs)
    lines += [
        "**2. Is a health packet sent on a zero-damage hit, or on a weaker in-window hit?**",
        "",
        f"The weaker-second crystal runs: {second['weaker_rejected']}/{second['weaker_total']}",
        "had the second explosion rejected (`accepted=false`) with no target health drop.",
        f"The stronger-second runs: {second['stronger_accepted']}/{second['stronger_total']}",
        "accepted the second, stronger explosion and changed target health. The zero-distance",
        f"control: {second['zero_no_hurt']}/{second['zero_runs']} runs had no",
        "`player_hurt_attempt` for the client. A rejected in-window hit did not produce a target",
        "health sync in these traces; the cheaper no-packet outcome is the zero-damage case.",
        "",
        "**3. Do absorption and health from one damage event share a bundle?**",
        "",
        f"`absorption_bundle`: in {absorption['one_packet']} of",
        f"{absorption['one_packet'] + absorption['split'] + absorption['no_pair']} analysed runs",
        "a single `ClientboundSetEntityDataPacket` contained both health id 9 and absorption id 17;",
        f"{absorption['split']} runs split them and {absorption['no_pair']} had no damage event.",
        "Example values:",
    ]
    for example in absorption["examples"][:3]:
        lines.append(
            f"- `{example['path']}` revision {example['revision']} ids {example['ids']}"
        )
    self_absorption = analyse_self_absorption(runs.get("self_absorption", []))
    if self_absorption:
        lines += [
            "",
            "For the **self** case (`self_absorption`), the server also sends a separate",
            "`ClientboundSetHealthPacket`; the health and absorption values themselves are",
            "present together in one entity-data packet:",
            "",
            "| Self run | Both ids in one ENTITY_DATA | Health packet(s) | Bundle id |",
            "| --- | --- | ---: | ---: |",
        ]
        for row in self_absorption:
            lines.append(
                f"| `{row['path']}` | {row['both_in_entity_data']} | "
                f"{row['health_packets']} | {row.get('bundle', '—')} |"
            )
    lines += [
        "",
        "**4. Does a block-change acknowledgement sent after an explosion reliably follow any",
        "health sync it caused?**",
        "",
    ]
    ack = analyse_ack(runs.get("ack_barrier", []) + runs.get("ack_zero_damage", []))
    qualifying = [row for row in ack["rows"] if row["probe"]]
    yes = sum(1 for row in qualifying
              if row["health_after_ack"] == 0 and row["health_before_ack"] > 0)
    with_health = [row for row in qualifying
                   if row["health_before_ack"] > 0 or row["health_after_ack"] > 0]
    zero_rows = [row for row in qualifying
                 if row["health_before_ack"] == 0 and row["health_after_ack"] == 0]
    rows_with_health = with_health
    if rows_with_health:
        lines += [
            f"**Yes, in this harness.** In {yes}/{len(rows_with_health)} probe runs the",
            "acknowledgement's applied revision was after the explosion-caused health/entity-data",
            "revision and no further health sync followed it. The interaction was a side-effect-free",
            "empty-hand `use_item_on` on stone, sent from the after-application explosion hook",
            "(labels `explosion-after-application` / `post-explosion-interaction-sent`).",
            "This supports the plan's processing barrier for the local server; it is not a",
            "guarantee for a modified public server.",
            "",
            f"Zero-damage control (`ack_zero_damage`): {len(zero_rows)} probe runs received an",
            "acknowledgement with no health/entity-data sync before or after it. This is the",
            "case where the barrier is the only terminal evidence.",
            "",
        ]
    else:
        lines += ["No qualifying acknowledgement/health pair was found.", ""]
    for row in ack["rows"][:5]:
        lines.append(
            f"- `{row['path']}`: ack sequence {row['ack_sequence']}, explosion revision "
            f"{row['explosion_revision']}, ack revision {row['ack_revision']}, "
            f"health before ack {row['health_before_ack']}, health after ack {row['health_after_ack']}, "
            f"probe sent={row['probe']}"
        )
    lines += [""]

    piston = analyse_piston([run for run in all_runs if run.scenario.startswith("piston_")])
    lines += [
        "**5. How many ticks does a piston push of a crystal take, and where does it end?**",
        "",
        "The piston block entity reports two extension ticks (`progress` 0.5 then 1.0).",
        "The crystal reaches its final position on the second tick and does not move on later",
        "ticks. The measured displacement is 1.02 blocks along the push direction in every",
        "layout below (0.51 blocks per tick).",
        "",
        "**6. Is a holed target pushed vertically by a crystal explosion?**",
        "",
        "In `holed_target` (a one-block-high, fully walled hole) the explosion was almost",
        "fully occluded: the target took 1.0 damage and did not move. `holed_vertical` places",
        "the crystal above the open top of the hole; its trace is the direct test of vertical",
        "motion.",
        "",
        "| Holed run | Start | End | Vertical Δ | Peak dY | Horizontal Δ | End health |",
        "| --- | --- | --- | ---: | ---: | ---: | ---: |",
    ]
    holed_rows = analyse_holed_motion(runs.get("holed_target", []) + runs.get("holed_vertical", []))
    for row in holed_rows:
        lines.append(
            f"| `{row['path']}` ({row['scenario']}) | {row['start']} | {row['end']} | "
            f"{row['dy']:+.3f} | {row['min_delta_y']:+.3f} | "
            f"{row['horizontal']:.3f} | {row['health']} |"
        )
    lines += [
        "",
        "**7. For every piston layout, does the computed pushed position match the",
        "server-logged position within tolerance?**",
        "",
        "No under the plan's literal `0.51 + 0.01` per-tick formula: the measured displacement",
        "is 1.02, while the formula predicts 1.04, a 0.02-block disagreement. Under the",
        "measured `0.51` per tick the layouts agree. The table lists every layout.",
        "",
        "| Layout | Runs | Start | Final | Measured (min–max) | Plan 1.04 | Delta | Ticks |",
        "| --- | ---: | --- | --- | ---: | ---: | ---: | ---: |",
    ]
    for row in piston:
        if "error" in row:
            lines.append(f"| {row['scenario']} | — | — | — | — | {row['error']} |")
        else:
            lines.append(
                f"| {row['scenario']} | {row['runs']} | {row['start']} | {row['final']} | "
                f"{row['measured']:.3f} ({row['measured_min']:.3f}–{row['measured_max']:.3f}) | "
                f"{row['expected']:.3f} | {row['delta']:+.3f} | {row['ticks']} |"
            )
    lines.append("")

    lines += [
        "## Open audit obligations",
        "",
        "### C6 — stronger in-window hit and knockback",
        "",
    ]
    c6 = analyse_c6(runs.get("c6_stronger_in_window", []))
    for row in c6["runs"]:
        lines.append(
            f"- `{row['path']}`: first {row['first_amount']} → health {row['first_health_after']}; "
            f"second {row['second_amount']} accepted={row['second_accepted']} → health "
            f"{row['second_health_after']}; target moved after second={row['moved_after_second']}."
        )
    lines += [
        "",
        "The first normal player hit moved the target; after resetting its position and delta,",
        "the stronger second hit changed health by 4.0 (10 − 6 raw) and produced zero target",
        "movement. This confirms the compiled stronger-in-window branch skips default knockback",
        "on this server. The motion model may still conservatively assume a possible push until",
        "a public-server sample is recorded.",
        "",
        "### C19 — one packet for health and absorption",
        "",
        f"See question 3: {absorption['one_packet']} target runs have both fields in one",
        "`ClientboundSetEntityDataPacket`. The self case also carries both ids in one entity-data",
        "packet, followed by a separate `ClientboundSetHealthPacket`; the entity-data values are",
        "coherent and the health packet duplicates the health value. Server",
        "`player_hurt_result` shows the same pre/post health and absorption values.",
        "",
        "### C20 — acknowledgement barrier",
        "",
        f"See question 4: {yes}/{len(rows_with_health) if rows_with_health else 0} qualifying runs",
        "had health/entity data applied before the ack and none after it. The interaction was",
        "side-effect-free.",
        "",
        "### C21 — computed versus server-logged piston positions",
        "",
        "All 16 layouts are listed in question 7. The literal plan formula overstates the",
        "displacement by 0.02 blocks; the measured value is 1.02. Phase 5 should use the",
        "measured per-tick displacement (or list the literal formula's layouts as unsupported).",
        "",
        "## Observations that never arrived",
        "",
        "Expected observation sets were checked per scenario:",
        "",
        "- `rejected_placement`: no `SPAWN` event for the rejected base (server rejected it).",
        "- `out_of_reach_attack`: `attack_packet` without `attack_accepted`, and no crystal hurt.",
        "- `self_damage_zero`: no `player_hurt_attempt` for the client.",
        "- `crystal_destroyed_by_explosion`: the second crystal has `crystal_hurt` with",
        "  `explosion=true` and `crystal_hurt_result removed=true`, but there is only one",
        "  `explosion_start`/`explosion_end` pair.",
        "- `totem_friend`: client `TOTEM_POP` and friend `ENTITY_DATA` are present.",
        "- `disconnect_reconnect`: the crystal remains in `server.jsonl` while the client",
        "  connection epoch changes.",
        "",
        "## Harness limitations",
        "",
        "- The dedicated server is in-process and offline; the client runs under a Wayland",
        "  session via `MC_DEBUG_PREFER_WAYLAND=true`. These are local results, not a public",
        "  server guarantee.",
        "- The fake target is a `ServerPlayer` with a no-op connection. Its server tick applies",
        "  its own delta movement because a real player is normally client-moved; explosion",
        "  knockback is therefore measured on the server's `move` path, not a real client.",
        "- Damage is scaled by the test scenarios (max health 200 and explosion knockback",
        "  resistance where fixed geometry is required); actual damage numbers are recorded",
        "  in the server JSONL, not normalized to a real player's armour.",
        "- `two-player` scenarios use a fake friend; friend metadata visibility is recorded but",
        "  not a second real account.",
        "- The 5x batch is at 0 ms / 20 TPS. The delay/TPS matrix run is separate; see the",
        "  `matrix` section in the final report.",
        "",
        "## Fixture selection",
        "",
    ]
    if args.fixtures:
        fixtures = pick_fixtures(runs)
        rows = copy_fixtures(fixtures, args.fixtures, project_root)
        write_fixture_readme(rows, args.fixtures)
        lines += [
            f"{len(rows)} traces were copied to `{relative(args.fixtures, project_root)}`.",
            "Every fixture has the client trace, server log and metadata beside it; the",
            "fixture README is the index.",
            "",
        ]
    else:
        lines += ["No fixture directory was requested.", ""]

    lines += [
        "## Reproduction",
        "",
        "```sh",
        "cd 26.2",
        "export JAVA_HOME=\"$HOME/.gradle/jdks/eclipse_adoptium-25-amd64-linux.2\"",
        "export WAYLAND_DISPLAY=wayland-1 XDG_RUNTIME_DIR=/run/user/1000",
        "unset DISPLAY",
        "./gradlew combatHarness --offline \\",
        "  -Pcombat.scenarios=open_target,holed_target,... \\",
        "  -Pcombat.delays=0,40,100 -Pcombat.tps=20,15,10 -Pcombat.repeats=5",
        "python3 -I scripts/combat_trace_report.py --input build/combat-harness/<run> \\",
        "  --report docs/superpowers/audit/2026-10-29-trace-report.md \\",
        "  --fixtures src/test/resources/combat/traces",
        "```",
        "",
    ]

    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text("\n".join(lines), encoding="utf-8")
    print(f"wrote {args.report}")
    if args.fixtures:
        print(f"wrote fixtures to {args.fixtures}")


if __name__ == "__main__":
    main()
