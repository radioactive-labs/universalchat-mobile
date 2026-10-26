# The Swift package is the main distribution. These pods exist for apps and
# plugins still on CocoaPods, such as Flutter apps without Swift Package
# Manager turned on. They mirror the package's two targets so the sources
# compile unchanged.
Pod::Spec.new do |s|
  s.name = "UniversalChat"
  s.version = "0.9.0"
  s.summary = "The UniversalChatbot chat as a native SwiftUI screen."
  s.description = "The UniversalChatbot chat as a native SwiftUI screen, with signed-in users, visitor attributes, files, past conversations and push."
  s.homepage = "https://universalchatbot.com"
  s.license = {type: "MIT", file: "LICENSE"}
  s.authors = "Fayaworks Technologies"
  s.source = {git: "https://github.com/radioactive-labs/universalchat-mobile.git", tag: s.version.to_s}
  s.ios.deployment_target = "16.0"
  s.swift_version = "5.9"
  s.source_files = "ios/Sources/UniversalChat/**/*.swift"
  s.dependency "UniversalChatCore", s.version.to_s
end
