// swift-tools-version:5.9
import PackageDescription

// At the repository root because Swift Package Manager reads a package from
// there. The other SDKs in this repository are published to their own
// registries and ignore it.

let package = Package(
    name: "UniversalChat",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [
        .library(name: "UniversalChat", targets: ["UniversalChat"]),
    ],
    targets: [
        // The protocol, the cable and the conversation's state. Foundation only,
        // so it builds and tests anywhere Swift does.
        .target(name: "UniversalChatCore", path: "ios/Sources/UniversalChatCore"),
        // The SwiftUI chat screen and the public entry point.
        .target(name: "UniversalChat", dependencies: ["UniversalChatCore"], path: "ios/Sources/UniversalChat"),
        .testTarget(name: "UniversalChatCoreTests", dependencies: ["UniversalChatCore"], path: "ios/Tests/UniversalChatCoreTests"),
    ]
)
