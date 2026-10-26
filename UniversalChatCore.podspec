Pod::Spec.new do |s|
  s.name = "UniversalChatCore"
  s.version = "0.9.0"
  s.summary = "The UniversalChatbot chat protocol, live updates and conversation state."
  s.description = "The networking and state under the UniversalChat pod. Apps depend on UniversalChat, which brings this in."
  s.homepage = "https://universalchatbot.com"
  s.license = {type: "MIT", file: "LICENSE"}
  s.authors = "Fayaworks Technologies"
  s.source = {git: "https://github.com/radioactive-labs/universalchat-mobile.git", tag: s.version.to_s}
  s.ios.deployment_target = "16.0"
  s.osx.deployment_target = "13.0"
  s.swift_version = "5.9"
  s.source_files = "ios/Sources/UniversalChatCore/**/*.swift"
end
