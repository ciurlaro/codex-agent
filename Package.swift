// swift-tools-version: 5.9

import PackageDescription

let package = Package(
    name: "CodexAgent",
    platforms: [.iOS(.v15)],
    products: [
        .library(name: "CodexAgent", targets: ["CodexAgent"]),
        .library(
            name: "CodexAgentAuthentication",
            targets: ["CodexAgentAuthentication"]
        ),
        .library(
            name: "CodexAgentObservation",
            targets: ["CodexAgentObservation"]
        ),
        .library(
            name: "CodexAgentSwiftSupport",
            targets: ["CodexAgentSwiftSupport"]
        ),
    ],
    targets: [
        .binaryTarget(
            name: "CodexAgent",
            url: "https://github.com/codex-agent-labs/codex-agent/releases/download/v0.2.0/CodexAgent-0.2.0.xcframework.zip",
            checksum: "13b095de5357abd79563345a893f814b97ce9a91c78b3ee55381295329946800"
        ),
        .target(
            name: "CodexAgentAuthentication",
            dependencies: ["CodexAgent"],
            path: "codex-agent-runtime-ios/apple/Sources/CodexAgentAuthentication",
            resources: [.copy("PrivacyInfo.xcprivacy")]
        ),
        .target(
            name: "CodexAgentObservation",
            dependencies: ["CodexAgent"],
            path: "codex-agent-runtime-ios/apple/Sources/CodexAgentObservation"
        ),
        .target(
            name: "CodexAgentSwiftSupport",
            dependencies: ["CodexAgent"],
            path: "codex-agent-runtime-ios/apple/Sources/CodexAgentSwiftSupport"
        ),
    ]
)
