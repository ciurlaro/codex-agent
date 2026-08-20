// swift-tools-version: 5.9

import PackageDescription

let package = Package(
    name: "CodexAgentRemoteConsumer",
    platforms: [.iOS(.v15)],
    products: [
        .library(
            name: "CodexAgentRemoteConsumer",
            targets: ["CodexAgentRemoteConsumer"]
        ),
    ],
    dependencies: [
        .package(
            url: "https://github.com/codex-agent-labs/codex-agent.git",
            exact: "0.2.0"
        ),
    ],
    targets: [
        .target(
            name: "CodexAgentRemoteConsumer",
            dependencies: [
                .product(name: "CodexAgent", package: "codex-agent"),
                .product(name: "CodexAgentAuthentication", package: "codex-agent"),
                .product(name: "CodexAgentObservation", package: "codex-agent"),
                .product(name: "CodexAgentSwiftSupport", package: "codex-agent"),
            ]
        ),
    ]
)
