// swift-tools-version: 5.9

import PackageDescription

let package = Package(
    name: "CodexAgent",
    platforms: [.iOS(.v16)],
    products: [
        .library(name: "CodexAgent", targets: ["CodexAgent"]),
    ],
    targets: [
        .binaryTarget(name: "CodexAgent", path: "CodexAgent.xcframework"),
    ]
)
