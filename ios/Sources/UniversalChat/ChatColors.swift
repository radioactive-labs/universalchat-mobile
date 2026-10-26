#if canImport(SwiftUI) && canImport(UIKit)
import SwiftUI
import UIKit
import CoreText
import UniversalChatCore

/// Everything the screen draws with: the deployment's colours for the theme in
/// use, the text sizes and corner radius it chose, and its font.
struct ChatLook {
    let primary: Color
    let accent: Color
    let background: Color
    let surface: Color
    let surfaceText: Color
    let border: Color
    let header: Color
    let botBackground: Color
    let botText: Color
    let botTextSecondary: Color
    let userBackground: Color
    let userText: Color
    let userTextSecondary: Color
    /// Links keep the website widget's fixed blue, lighter in dark mode.
    let link: Color
    let dark: Bool
    let base: CGFloat
    let small: CGFloat
    let tiny: CGFloat
    let radius: CGFloat
    let title: String
    let subtitle: String
    let fonts: WebFont?

    init(style: WidgetStyle, dark: Bool, fonts: WebFont?) {
        let p = style.palette(dark: dark)
        primary = Self.color(p.primary)
        accent = Self.color(p.accent)
        background = Self.color(p.background)
        surface = Self.color(p.surface)
        surfaceText = Self.color(p.surfaceText)
        border = Self.color(p.border)
        header = Self.color(p.header)
        botBackground = Self.color(p.botBackground)
        botText = Self.color(p.botText)
        botTextSecondary = Self.color(p.botTextSecondary)
        userBackground = Self.color(p.userBackground)
        userText = Self.color(p.userText)
        userTextSecondary = Self.color(p.userTextSecondary)
        link = dark ? Self.color(0xFF60A5FA) : Self.color(0xFF3B82F6)
        self.dark = dark
        base = style.fontSize
        small = style.smallFontSize
        tiny = style.tinyFontSize
        radius = style.borderRadius
        title = style.title
        subtitle = style.subtitle
        self.fonts = fonts
    }

    /// The deployment's font at a size and weight, or the system font.
    func font(_ size: CGFloat, _ weight: Font.Weight = .regular) -> Font {
        if let name = fonts?.postScriptName(for: weight) { return .custom(name, fixedSize: size) }
        return .system(size: size, weight: weight)
    }

    static func color(_ argb: UInt32) -> Color {
        Color(
            .sRGB,
            red: Double((argb >> 16) & 0xFF) / 255,
            green: Double((argb >> 8) & 0xFF) / 255,
            blue: Double(argb & 0xFF) / 255,
            opacity: Double((argb >> 24) & 0xFF) / 255
        )
    }
}

/// The deployment's Google font, downloaded once into the app's caches and
/// registered for this process, so the chat reads in the same face as on the
/// website. The system font shows until it lands, and for good when it cannot
/// be fetched.
final class WebFont {
    private let names: [Int: String]

    private init(names: [Int: String]) { self.names = names }

    func postScriptName(for weight: Font.Weight) -> String? {
        let wanted: Int
        switch weight {
        case .semibold: wanted = 600
        case .bold, .heavy, .black: wanted = 700
        case .medium: wanted = 500
        default: wanted = 400
        }
        return names[wanted] ?? names.min { abs($0.key - wanted) < abs($1.key - wanted) }?.value
    }

    static func load(family: String, stylesheet: URL) async -> WebFont? {
        guard let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first else { return nil }
        let dir = caches.appendingPathComponent("universalchat-fonts", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let slug = family.lowercased().replacingOccurrences(of: "[^a-z0-9]+", with: "-", options: .regularExpression)

        do {
            var files: [Int: URL] = [:]
            let cached = (try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)) ?? []
            for file in cached where file.lastPathComponent.hasPrefix("\(slug)-") && file.pathExtension == "ttf" {
                if let weight = Int(file.deletingPathExtension().lastPathComponent.dropFirst(slug.count + 1)) { files[weight] = file }
            }
            if files.isEmpty {
                let (css, _) = try await URLSession.shared.data(from: stylesheet)
                for (weight, url) in GoogleFontCss.files(String(decoding: css, as: UTF8.self)) {
                    let (data, _) = try await URLSession.shared.data(from: url)
                    let file = dir.appendingPathComponent("\(slug)-\(weight).ttf")
                    try data.write(to: file)
                    files[weight] = file
                }
            }

            var names: [Int: String] = [:]
            for (weight, file) in files {
                guard let provider = CGDataProvider(url: file as CFURL), let font = CGFont(provider), let name = font.postScriptName as String? else { continue }
                CTFontManagerRegisterGraphicsFont(font, nil)
                names[weight] = name
            }
            return names.isEmpty ? nil : WebFont(names: names)
        } catch {
            return nil
        }
    }
}
#endif
