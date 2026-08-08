#!/usr/bin/env python3
"""Measure actual disk and memory use while one release phase runs."""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path


def tree_bytes(path: Path) -> int:
    if not path.exists():
        return 0
    if path.is_file():
        return path.stat().st_size
    total = 0
    for root, _, files in os.walk(path):
        for name in files:
            try:
                total += Path(root, name).stat().st_size
            except FileNotFoundError:
                pass
    return total


def memory() -> tuple[int, int]:
    meminfo = Path("/proc/meminfo")
    if meminfo.exists():
        values = {}
        for line in meminfo.read_text().splitlines():
            name, value = line.split(":", 1)
            values[name] = int(value.strip().split()[0]) * 1024
        return values["MemTotal"], values.get("MemAvailable", values["MemFree"])

    total = int(subprocess.check_output(["sysctl", "-n", "hw.memsize"], text=True).strip())
    output = subprocess.check_output(["vm_stat"], text=True)
    page_match = __import__("re").search(r"page size of (\d+) bytes", output)
    page_size = int(page_match.group(1)) if page_match else 4096
    pages = {}
    for line in output.splitlines():
        if ":" not in line:
            continue
        name, value = line.split(":", 1)
        pages[name] = int(value.strip().rstrip("."))
    available_pages = sum(
        pages.get(name, 0)
        for name in ("Pages free", "Pages inactive", "Pages speculative")
    )
    return total, available_pages * page_size


def process_tree_rss(root_pid: int) -> int:
    rows = []
    proc = Path("/proc")
    if proc.is_dir():
        page_size = os.sysconf("SC_PAGE_SIZE")
        for process in proc.iterdir():
            if not process.name.isdigit():
                continue
            try:
                stat = (process / "stat").read_text()
                fields = stat[stat.rfind(")") + 2 :].split()
                rows.append((int(process.name), int(fields[1]), int(fields[21]) * page_size))
            except (FileNotFoundError, IndexError, PermissionError, ValueError):
                pass
    else:
        output = subprocess.check_output(["ps", "-axo", "pid=,ppid=,rss="], text=True)
        for line in output.splitlines():
            values = line.split()
            if len(values) == 3:
                pid, parent, rss_kib = map(int, values)
                rows.append((pid, parent, rss_kib * 1024))
    descendants = {root_pid}
    changed = True
    while changed:
        changed = False
        for pid, parent, _ in rows:
            if parent in descendants and pid not in descendants:
                descendants.add(pid)
                changed = True
    return sum(rss_bytes for pid, _, rss_bytes in rows if pid in descendants)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--phase", required=True)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--workspace", required=True, type=Path)
    parser.add_argument("--track", action="append", default=[], type=Path)
    parser.add_argument("command", nargs=argparse.REMAINDER)
    arguments = parser.parse_args()
    command = arguments.command
    if command and command[0] == "--":
        command = command[1:]
    if not command:
        parser.error("a command is required after --")

    interval = 1.0
    started = time.monotonic()
    child = subprocess.Popen(command)
    first = True
    samples = 0
    start_disk = None
    start_disk_available = None
    peak_disk_used = 0
    minimum_disk_available = None
    total_disk = 0
    total_memory = 0
    start_memory_available = None
    minimum_memory_available = None
    peak_process_rss = 0
    tracked_peaks = {str(path): 0 for path in arguments.track}
    try:
        while first or child.poll() is None:
            first = False
            disk = shutil.disk_usage(arguments.workspace)
            memory_total, memory_available = memory()
            process_rss = process_tree_rss(child.pid) if child.poll() is None else 0
            start_disk = disk.used if start_disk is None else start_disk
            start_disk_available = disk.free if start_disk_available is None else start_disk_available
            total_disk = disk.total
            total_memory = memory_total
            start_memory_available = (
                memory_available if start_memory_available is None else start_memory_available
            )
            peak_disk_used = max(peak_disk_used, disk.used)
            minimum_disk_available = (
                disk.free if minimum_disk_available is None else min(minimum_disk_available, disk.free)
            )
            minimum_memory_available = (
                memory_available
                if minimum_memory_available is None
                else min(minimum_memory_available, memory_available)
            )
            peak_process_rss = max(peak_process_rss, process_rss)
            for path in arguments.track:
                tracked_peaks[str(path)] = max(tracked_peaks[str(path)], tree_bytes(path))
            samples += 1
            if child.poll() is None:
                time.sleep(interval)
    except BaseException:
        child.terminate()
        child.wait()
        raise

    result = {
        "schemaVersion": 1,
        "phase": arguments.phase,
        "command": command,
        "exitCode": child.returncode,
        "durationSeconds": round(time.monotonic() - started, 3),
        "samplingIntervalSeconds": interval,
        "sampleCount": samples,
        "disk": {
            "filesystemTotalBytes": total_disk,
            "startUsedBytes": start_disk,
            "startAvailableBytes": start_disk_available,
            "peakUsedBytes": peak_disk_used,
            "peakIncreaseBytes": peak_disk_used - (start_disk or 0),
            "minimumAvailableBytes": minimum_disk_available,
        },
        "memory": {
            "systemTotalBytes": total_memory,
            "startSystemAvailableBytes": start_memory_available,
            "minimumSystemAvailableBytes": minimum_memory_available,
            "peakCommandProcessTreeResidentBytes": peak_process_rss,
        },
        "trackedPathLogicalPeakBytes": tracked_peaks,
    }
    arguments.output.parent.mkdir(parents=True, exist_ok=True)
    arguments.output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n")
    return child.returncode


if __name__ == "__main__":
    sys.exit(main())
