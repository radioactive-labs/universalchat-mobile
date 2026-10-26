import Foundation

/// The widget customizer's settings, resolved the way the website widget
/// resolves them, so a deployment looks the same in an app as on the web: the
/// same colour keys styling the same parts, the same defaults, the same theme
/// choice, text size and corner radius.
public struct WidgetStyle: Equatable, Sendable {
    public static let defaultTitle = "Chat with us"
    public static let defaultSubtitle = "We'll reply as soon as we can"

    public var theme: ThemeMode
    public var light: Palette
    public var dark: Palette
    /// The base text size in points. Message text is 6/7 of it, labels 5/7.
    public var fontSize: Double
    public var borderRadius: Double
    /// The first family the customizer named, or nil for the system font.
    public var fontFamily: String?
    /// Where Google serves that family, when it does.
    public var fontStylesheet: URL?
    public var title: String
    public var subtitle: String

    public var smallFontSize: Double { fontSize * 0.857 }
    public var tinyFontSize: Double { fontSize * 0.714 }

    public func palette(dark: Bool) -> Palette { dark ? self.dark : light }

    public static let `default` = WidgetStyle(theme: .light, light: .light, dark: .dark, fontSize: 16, borderRadius: 16, fontFamily: nil,
                                              fontStylesheet: nil, title: defaultTitle, subtitle: defaultSubtitle)

    private static let systemFamilies: Set<String> = [
        "ui-sans-serif", "system-ui", "-apple-system", "blinkmacsystemfont", "segoe ui", "roboto",
        "helvetica neue", "helvetica", "arial", "sans-serif", "serif", "monospace", "inherit",
    ]

    static func parse(_ widget: JSON, fontStylesheet: String?) -> WidgetStyle {
        WidgetStyle(
            theme: ThemeMode(widget["theme"].string),
            light: Palette.light.with(widget["lightColors"]),
            dark: Palette.dark.with(widget["darkColors"]),
            fontSize: cssLength(widget["fontSize"].string) ?? 16,
            borderRadius: cssLength(widget["borderRadius"].string) ?? 16,
            fontFamily: firstFamily(widget["fontFamily"].string),
            fontStylesheet: fontStylesheet.flatMap(URL.init(string:)),
            title: widget["title"].string ?? defaultTitle,
            subtitle: widget["subtitle"].string ?? defaultSubtitle
        )
    }

    /// `16px`, `1rem` or a bare number, in points.
    public static func cssLength(_ value: String?) -> Double? {
        guard let text = value?.trimmingCharacters(in: .whitespaces).lowercased() else { return nil }
        let number: Double?
        if text.hasSuffix("px") { number = Double(text.dropLast(2).trimmingCharacters(in: .whitespaces)) }
        else if text.hasSuffix("rem") { number = Double(text.dropLast(3).trimmingCharacters(in: .whitespaces)).map { $0 * 16 } }
        else if text.hasSuffix("em") { number = Double(text.dropLast(2).trimmingCharacters(in: .whitespaces)).map { $0 * 16 } }
        else { number = Double(text) }
        return number.flatMap { $0 >= 0 ? $0 : nil }
    }

    /// The first family a CSS stack names, unless it is one of the system stacks.
    public static func firstFamily(_ stack: String?) -> String? {
        guard let first = stack?.split(separator: ",").first?.trimmingCharacters(in: CharacterSet(charactersIn: " \"'")), !first.isEmpty else { return nil }
        return systemFamilies.contains(first.lowercased()) ? nil : first
    }
}

public enum ThemeMode: Equatable, Sendable {
    case light, dark, system

    // Following the page's own dark class has no meaning in an app, so it
    // follows the phone's setting.
    init(_ value: String?) {
        switch value {
        case "dark": self = .dark
        case "system", "tailwind_class": self = .system
        default: self = .light
        }
    }
}

/// One theme's colours, as 0xAARRGGBB, keyed as the widget customizer keys them.
public struct Palette: Equatable, Sendable {
    public var primary: UInt32
    public var primaryHover: UInt32
    public var accent: UInt32
    public var background: UInt32
    public var surface: UInt32
    public var surfaceText: UInt32
    public var border: UInt32
    public var header: UInt32
    public var botBackground: UInt32
    public var botText: UInt32
    public var botTextSecondary: UInt32
    public var userBackground: UInt32
    public var userText: UInt32
    public var userTextSecondary: UInt32

    // The website widget's defaults: the header as the embed script draws it,
    // the rest as the conversation frame does.
    public static let light = Palette(
        primary: 0xFF0F172A, primaryHover: 0xFF1E293B, accent: 0xFF475569,
        background: 0xFFF8FAFC, surface: 0xFFFFFFFF, surfaceText: 0xFF64748B, border: 0xFFE2E8F0,
        header: 0xFF64748B,
        botBackground: 0xFFFFFFFF, botText: 0xFF0F172A, botTextSecondary: 0xFF64748B,
        userBackground: 0xFF0F172A, userText: 0xFFFFFFFF, userTextSecondary: 0xFFE2E8F0
    )
    public static let dark = Palette(
        primary: 0xFF1E293B, primaryHover: 0xFF334155, accent: 0xFF475569,
        background: 0xFF0F172A, surface: 0xFF1E293B, surfaceText: 0xFF94A3B8, border: 0xFF334155,
        header: 0xFF1E293B,
        botBackground: 0xFF1E293B, botText: 0xFFF1F5F9, botTextSecondary: 0xFF94A3B8,
        userBackground: 0xFF334155, userText: 0xFFF1F5F9, userTextSecondary: 0xFFCBD5E1
    )

    func with(_ saved: JSON) -> Palette {
        func pick(_ key: String, _ fallback: UInt32) -> UInt32 { Palette.parseColor(saved[key].string) ?? fallback }
        return Palette(
            primary: pick("primaryColor", primary), primaryHover: pick("primaryHoverColor", primaryHover), accent: pick("accentColor", accent),
            background: pick("backgroundColor", background), surface: pick("surfaceColor", surface), surfaceText: pick("surfaceTextColor", surfaceText),
            border: pick("borderColor", border), header: pick("headerColor", header),
            botBackground: pick("botMessageBackgroundColor", botBackground), botText: pick("botMessageTextColor", botText),
            botTextSecondary: pick("botMessageTextSecondaryColor", botTextSecondary),
            userBackground: pick("userMessageBackgroundColor", userBackground), userText: pick("userMessageTextColor", userText),
            userTextSecondary: pick("userMessageTextSecondaryColor", userTextSecondary)
        )
    }

    /// `#rgb`, `#rrggbb` or `#rrggbbaa`, as the customizer saves them, to 0xAARRGGBB.
    public static func parseColor(_ value: String?) -> UInt32? {
        guard var hex = value?.trimmingCharacters(in: .whitespaces) else { return nil }
        if hex.hasPrefix("#") { hex.removeFirst() }
        if hex.count == 3 { hex = hex.map { "\($0)\($0)" }.joined() }
        if hex.count == 6 { hex += "FF" }
        guard hex.count == 8, let rgba = UInt32(hex, radix: 16) else { return nil }
        return (rgba << 24) | (rgba >> 8)
    }
}

/// Reads the font files out of a Google Fonts stylesheet. Asked for without a
/// browser's user agent, Google answers with TrueType files, one per weight.
public enum GoogleFontCss {
    /// Weight to file URL, upright faces only.
    public static func files(_ css: String) -> [Int: URL] {
        var files: [Int: URL] = [:]
        for block in css.components(separatedBy: "@font-face").dropFirst() {
            guard !block.contains("font-style: italic"),
                  let weight = value(after: "font-weight:", in: block, until: ";").flatMap({ Int($0) }),
                  let url = value(after: "url(", in: block, until: ")").flatMap({ URL(string: $0.trimmingCharacters(in: CharacterSet(charactersIn: "\"'"))) })
            else { continue }
            files[weight] = url
        }
        return files
    }

    private static func value(after marker: String, in text: String, until end: Character) -> String? {
        guard let start = text.range(of: marker)?.upperBound else { return nil }
        let rest = text[start...]
        guard let stop = rest.firstIndex(of: end) else { return nil }
        return rest[..<stop].trimmingCharacters(in: .whitespaces)
    }
}
