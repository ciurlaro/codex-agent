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
    ],
    targets: [
        .binaryTarget(
            name: "CodexAgent",
            url: "https://github.com/ciurlaro/codex-agent/releases/download/v0.2.0/CodexAgent-0.2.0.xcframework.zip",
            checksum: "850054725af3c20c24c126ea54eb5d9a36d251f1d6ee657ec11288f28635ab4b"
        ),
        .target(
            name: "CodexAgentAuthentication",
            dependencies: ["CodexAgent"],
            path: "codex-agent-runtime-ios/apple/Sources/CodexAgentAuthentication",
            resources: [.copy("PrivacyInfo.xcprivacy")]
        ),
    ]
)
