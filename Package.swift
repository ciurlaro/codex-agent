// swift-tools-version: 5.9

import PackageDescription

let package = Package(
    name: "CodexAgent",
    platforms: [.iOS(.v14)],
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
            checksum: "5ab4641970ac1dd691e3be0b9e2f89bb9b41a538e7afe6fb8550d6e323ffe401"
        ),
        .target(
            name: "CodexAgentAuthentication",
            dependencies: ["CodexAgent"],
            path: "codex-agent-runtime-ios/apple/Sources/CodexAgentAuthentication"
        ),
    ]
)
