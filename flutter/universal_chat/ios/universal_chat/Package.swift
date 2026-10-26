// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "universal_chat",
    platforms: [.iOS("16.0")],
    products: [
        .library(name: "universal-chat", targets: ["universal_chat"]),
    ],
    dependencies: [
        .package(url: "https://github.com/radioactive-labs/universalchat-mobile", from: "0.9.0"),
    ],
    targets: [
        .target(
            name: "universal_chat",
            dependencies: [.product(name: "UniversalChat", package: "universalchat-mobile")]
        ),
    ]
)
