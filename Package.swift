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
            checksum: "a37f200f07703a978e103b44b454f38f20a4955c31d4fb6db1011a3758aaea56"
        ),
        .target(
            name: "CodexAgentAuthentication",
            dependencies: ["CodexAgent"],
            path: "codex-agent-runtime-ios/apple/Sources/CodexAgentAuthentication",
            resources: [.copy("PrivacyInfo.xcprivacy")]
        ),
    ]
)
