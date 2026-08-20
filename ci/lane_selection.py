#!/usr/bin/env python3
"""Expose lane actions from one validated impact plan."""

from __future__ import annotations

import argparse
import json
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--plan", type=Path, required=True)
    parser.add_argument("--lane", action="append", required=True)
    parser.add_argument("--github-output", type=Path, required=True)
    arguments = parser.parse_args()
    plan = json.loads(arguments.plan.read_text(encoding="utf-8"))
    with arguments.github_output.open("a", encoding="utf-8") as output:
        for lane in arguments.lane:
            state = plan["lanes"][lane]
            name = lane.replace("-", "_")
            for action in ("build", "test", "metadata"):
                output.write(f"{name}_{action}={str(bool(state[action])).lower()}\n")
            output.write(f"{name}={str(any(state[action] for action in ('build', 'test', 'metadata'))).lower()}\n")
        if len(arguments.lane) == 1:
            lane = arguments.lane[0]
            state = plan["lanes"][lane]
            for action in ("build", "test", "metadata"):
                output.write(f"{action}={str(bool(state[action])).lower()}\n")
            desktop_test = lane.startswith("desktop-") and bool(state["test"])
            for node_lane, name in (("node-js", "node_js"), ("node-wasm", "node_wasm")):
                required = desktop_test or any(
                    plan["lanes"][node_lane][action] for action in ("build", "test", "metadata")
                )
                output.write(f"{name}={str(required).lower()}\n")


if __name__ == "__main__":
    main()
