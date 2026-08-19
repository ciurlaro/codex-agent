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
        .binaryTarget(name: "CodexAgent", path: "CodexAgent.xcframework"),
        .target(
            name: "CodexAgentAuthentication",
            dependencies: ["CodexAgent"],
            resources: [.copy("PrivacyInfo.xcprivacy")]
        ),
        .target(
            name: "CodexAgentObservation",
            dependencies: ["CodexAgent"]
        ),
        .target(
            name: "CodexAgentSwiftSupport",
            dependencies: ["CodexAgent"]
        ),
        .testTarget(
            name: "CodexAgentAuthenticationTests",
            dependencies: [
                "CodexAgent",
                "CodexAgentAuthentication",
            ]
        ),
        .testTarget(
            name: "CodexAgentObservationTests",
            dependencies: [
                "CodexAgent",
                "CodexAgentObservation",
                "CodexAgentSwiftSupport",
            ]
        ),
    ]
)
