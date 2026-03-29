// swift-tools-version: 6.0

import PackageDescription

let package = Package(
    name: "RommioiOS",
    platforms: [
        .iOS(.v17),
        .macOS(.v14),
    ],
    products: [
        .library(name: "RommioContract", targets: ["RommioContract"]),
        .library(name: "RommioFoundation", targets: ["RommioFoundation"]),
        .library(name: "RommioPlayerBridge", targets: ["RommioPlayerBridge"]),
        .library(name: "RommioPlayerKit", targets: ["RommioPlayerKit"]),
        .library(name: "RommioUI", targets: ["RommioUI"]),
    ],
    dependencies: [
        .package(url: "https://github.com/groue/GRDB.swift.git", from: "7.0.0"),
    ],
    targets: [
        .target(
            name: "RommioContract"
        ),
        .target(
            name: "RommioFoundation",
            dependencies: [
                "RommioContract",
                .product(name: "GRDB", package: "GRDB.swift"),
            ]
        ),
        .target(
            name: "RommioPlayerBridge",
            dependencies: [],
            publicHeadersPath: "include",
            linkerSettings: [
                .linkedFramework("Foundation"),
            ]
        ),
        .target(
            name: "RommioPlayerKit",
            dependencies: ["RommioContract", "RommioFoundation", "RommioPlayerBridge"]
        ),
        .target(
            name: "RommioUI",
            dependencies: ["RommioFoundation", "RommioPlayerKit"]
        ),
        .testTarget(
            name: "RommioContractTests",
            dependencies: ["RommioContract"]
        ),
        .testTarget(
            name: "RommioFoundationTests",
            dependencies: ["RommioFoundation", "RommioPlayerKit", "RommioContract"]
        ),
        .testTarget(
            name: "RommioPlayerBridgeTests",
            dependencies: ["RommioPlayerBridge", "RommioFoundation", "RommioContract"],
            exclude: ["Fixtures"]
        ),
        .testTarget(
            name: "RommioUITests",
            dependencies: ["RommioUI", "RommioFoundation", "RommioPlayerKit", "RommioContract"]
        ),
        .testTarget(
            name: "RommioPlayerKitTests",
            dependencies: ["RommioPlayerKit", "RommioContract"]
        ),
    ]
)
