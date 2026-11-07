// swift-tools-version: 5.9
import PackageDescription

// Pure, offline candidate protocol decoder. No Bluetooth side effects or vendor account access.
// Mirrors the layout of Packages/OuraProtocol: a pure value-type library target plus a
// headless viatom-decode executable that replays captured frames through the same decoder.
let package = Package(
    name: "ViatomProtocol",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [
        .library(name: "ViatomProtocol", targets: ["ViatomProtocol"]),
        .executable(name: "viatom-decode", targets: ["viatom-decode"]),
        .executable(name: "viatom-a5-replay", targets: ["viatom-a5-replay"]),
        .executable(name: "viatom-a5-decrypt", targets: ["viatom-a5-decrypt"]),
    ],
    targets: [
        .target(name: "ViatomProtocol"),
        .executableTarget(name: "viatom-decode", dependencies: ["ViatomProtocol"]),
        .executableTarget(name: "viatom-a5-replay", dependencies: ["ViatomProtocol"]),
        .executableTarget(name: "viatom-a5-decrypt", dependencies: ["ViatomProtocol"]),
    ]
)
