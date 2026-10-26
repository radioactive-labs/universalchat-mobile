# For apps without Flutter's Swift Package Manager support turned on. With it
# on, Flutter uses universal_chat/Package.swift instead.
Pod::Spec.new do |s|
  s.name = "universal_chat"
  s.version = "0.9.0"
  s.summary = "The UniversalChatbot chat as a native screen in Flutter apps."
  s.homepage = "https://universalchatbot.com"
  s.license = {type: "MIT", file: "../LICENSE"}
  s.authors = "Fayaworks Technologies"
  s.source = {path: "."}
  s.source_files = "universal_chat/Sources/universal_chat/**/*.swift"
  s.dependency "Flutter"
  s.dependency "UniversalChat", "~> 0.9.0"
  s.platform = :ios, "16.0"
  s.swift_version = "5.9"
  s.pod_target_xcconfig = {"DEFINES_MODULE" => "YES"}
end
