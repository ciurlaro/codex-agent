# iOS release budgets

The pinned measurement environment is Apple Silicon, Xcode `26.6` build
`17F113`, Swift `6.3.3`, Rust `1.95.0`, and an iOS `15.0` deployment target.
Machine-readable baselines and limits are in
`release/ios-budgets-0.2.0.json`.

Artifact gates allow at most 110% of the recorded compressed XCFramework,
device framework, and archived sample-app install sizes. Runtime measurements
use one unmeasured warmup followed by five fresh start/shutdown cycles and
record raw values, median, and maximum. Stable functional limits are 30 seconds
for startup and 5 seconds for shutdown.

The tighter 2-second median and 3-second maximum targets are report-only for
`0.2.0`; enabling them requires ten comparable hosted runs on the pinned image.
Idle and recursive-search memory report current resident size from
`mach_task_basic_info`; they are snapshots, not process-lifetime peaks, and
have no CI threshold for `0.2.0`. Peak memory during an authenticated model
turn must be captured manually with Instruments because credential-free
automation cannot execute a real turn.
