# iOS release budgets

The release build pins its Apple and Rust toolchains and its iOS deployment
target in the build configuration. Stable machine-readable limits are in
`gradle/release/ios-resource-policy.json`; host-specific measurements are generated
under the protected candidate's ignored build directory.

Artifact gates allow at most 110% of the recorded compressed XCFramework,
device framework, and archived sample-app install sizes. Runtime measurements
use one unmeasured warmup followed by five fresh start/shutdown cycles and
record raw values, median, and maximum. Stable functional limits are 30 seconds
for startup and 5 seconds for shutdown.

The tighter 2-second median and 3-second maximum targets are report-only;
enabling them requires ten comparable hosted runs on the pinned image. Idle
and recursive-search memory report current resident size from
`mach_task_basic_info`; they are snapshots, not process-lifetime peaks, and
have no CI threshold for `0.2.0`. Peak memory during an authenticated model
turn must be captured manually with Instruments because credential-free
automation cannot execute a real turn.
