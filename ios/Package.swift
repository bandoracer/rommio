// swift-tools-version: 6.0

import PackageDescription

let package = Package(
    name: "RommioiOS",
    platforms: [
        .iOS(.v18),
        .macOS(.v15),
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
        .package(url: "https://github.com/weichsel/ZIPFoundation.git", from: "0.9.20"),
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
            dependencies: ["MoltenVK"],
            publicHeadersPath: "include",
            cSettings: [.headerSearchPath("vendor")],
            linkerSettings: [
                .linkedFramework("Foundation"),
                .linkedFramework("Metal"),
                .linkedFramework("QuartzCore"),
                .linkedFramework("IOSurface"),
            ]
        ),
        .binaryTarget(
            name: "MoltenVK",
            url: "https://github.com/mpvkit/moltenvk-build/releases/download/1.4.2/MoltenVK.xcframework.zip",
            checksum: "aee189c54ad7c62bf734a3dc51eb4cfad5685d1d63b0ec519ecd1b437c332418"
        ),
        .target(
            name: "RommioPlayerKit",
            dependencies: ["RommioContract", "RommioFoundation", "RommioPlayerBridge", "ZIPFoundation"]
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
            dependencies: ["RommioPlayerKit", "RommioContract", "ZIPFoundation"]
        ),
    ]
)
