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
        .binaryTarget(name: "CodexAgent", path: "CodexAgent.xcframework"),
        .target(
            name: "CodexAgentAuthentication",
            dependencies: ["CodexAgent"]
        ),
        .testTarget(
            name: "CodexAgentAuthenticationTests",
            dependencies: ["CodexAgentAuthentication"]
        ),
    ]
)
