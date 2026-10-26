require "json"

package = JSON.parse(File.read(File.join(__dir__, "package.json")))

Pod::Spec.new do |s|
  s.name = "universalchatbot-react-native"
  s.version = package["version"]
  s.summary = package["description"]
  s.homepage = "https://universalchatbot.com"
  s.license = {type: "MIT", file: "LICENSE"}
  s.authors = "Fayaworks Technologies"
  s.platforms = {ios: "16.0"}
  s.source = {git: "https://github.com/radioactive-labs/universalchat-mobile.git", tag: s.version.to_s}
  s.source_files = "ios/**/*.{h,m,swift}"
  s.swift_version = "5.9"

  # The iOS SDK is a Swift package, which React Native 0.75 and later can
  # pull into a pod.
  spm_dependency(s,
    url: "https://github.com/radioactive-labs/universalchat-mobile",
    requirement: {kind: "upToNextMajorVersion", minimumVersion: "0.9.0"},
    products: ["UniversalChat"])

  install_modules_dependencies(s)
end
