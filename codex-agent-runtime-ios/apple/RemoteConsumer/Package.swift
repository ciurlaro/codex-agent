// swift-tools-version: 5.9

import PackageDescription

let package = Package(
    name: "CodexAgentRemoteConsumer",
    platforms: [.iOS(.v14)],
    dependencies: [
        .package(
            url: "https://github.com/ciurlaro/codex-agent.git",
            exact: "0.2.0"
        ),
    ],
    targets: [
        .target(
            name: "CodexAgentRemoteConsumer",
            dependencies: [
                .product(name: "CodexAgentAuthentication", package: "codex-agent"),
            ]
        ),
    ]
)
