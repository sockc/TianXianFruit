#!/usr/bin/env swift
import AppKit
import Foundation

// Reproducible AppIcon artwork for CI. Render at each native size so the
// asset catalog is available before XcodeGen builds the device IPA.
// Coordinates are authored in a 1024x1024 design canvas.
func rgba(_ r: CGFloat, _ g: CGFloat, _ b: CGFloat, _ a: CGFloat = 1) -> NSColor {
    NSColor(calibratedRed: r/255, green: g/255, blue: b/255, alpha: a)
}
func path(_ points: [(CGFloat, CGFloat)]) -> NSBezierPath {
    let p = NSBezierPath()
    if let first = points.first {
        p.move(to: NSPoint(x: first.0, y: first.1))
        for point in points.dropFirst() { p.line(to: NSPoint(x: point.0, y: point.1)) }
        p.close()
    }
    return p
}
func stroke(_ points: [(CGFloat, CGFloat)], color: NSColor, width: CGFloat) {
    let p = NSBezierPath()
    if let first = points.first {
        p.move(to: NSPoint(x: first.0, y: first.1))
        for point in points.dropFirst() { p.line(to: NSPoint(x: point.0, y: point.1)) }
        p.lineWidth = width
        p.lineCapStyle = .round
        p.lineJoinStyle = .round
        color.setStroke()
        p.stroke()
    }
}
func drawArtwork() {
    rgba(0, 91, 55).setFill()
    NSRect(x: 0, y: 0, width: 1024, height: 1024).fill()
    // Gentle background lighting.
    let glow = NSBezierPath(ovalIn: NSRect(x: 95, y: 105, width: 820, height: 820))
    NSGradient(starting: rgba(12, 131, 81, 0.65),
               ending: rgba(0, 75, 47, 0.05))!.draw(in: glow, angle: -35)

    // Stem and leaf.
    let stem = path([(484,665),(503,835),(542,823),(552,676)])
    rgba(132, 72, 23).setFill(); stem.fill()
    let leaf = NSBezierPath()
    leaf.move(to: NSPoint(x: 522, y: 695))
    leaf.curve(to: NSPoint(x: 823, y: 853),
               controlPoint1: NSPoint(x: 559, y: 855),
               controlPoint2: NSPoint(x: 719, y: 911))
    leaf.curve(to: NSPoint(x: 522, y: 695),
               controlPoint1: NSPoint(x: 785, y: 699),
               controlPoint2: NSPoint(x: 606, y: 735))
    NSGradient(starting: rgba(183,255,47), ending: rgba(32,200,49))!
        .draw(in: leaf, angle: -15)

    // Fruit silhouette and its soft highlight.
    let apple = NSBezierPath()
    apple.move(to: NSPoint(x: 510, y: 710))
    apple.curve(to: NSPoint(x: 250, y: 720),
                controlPoint1: NSPoint(x: 394, y: 815),
                controlPoint2: NSPoint(x: 293, y: 772))
    apple.curve(to: NSPoint(x: 171, y: 494),
                controlPoint1: NSPoint(x: 179, y: 682),
                controlPoint2: NSPoint(x: 141, y: 563))
    apple.curve(to: NSPoint(x: 458, y: 209),
                controlPoint1: NSPoint(x: 206, y: 363),
                controlPoint2: NSPoint(x: 331, y: 230))
    apple.curve(to: NSPoint(x: 688, y: 232),
                controlPoint1: NSPoint(x: 541, y: 179),
                controlPoint2: NSPoint(x: 622, y: 184))
    apple.curve(to: NSPoint(x: 862, y: 505),
                controlPoint1: NSPoint(x: 818, y: 325),
                controlPoint2: NSPoint(x: 906, y: 391))
    apple.curve(to: NSPoint(x: 764, y: 713),
                controlPoint1: NSPoint(x: 854, y: 627),
                controlPoint2: NSPoint(x: 811, y: 710))
    apple.curve(to: NSPoint(x: 510, y: 710),
                controlPoint1: NSPoint(x: 661, y: 776),
                controlPoint2: NSPoint(x: 585, y: 688))
    NSGradient(starting: rgba(255, 218, 28), ending: rgba(255, 111, 16))!
        .draw(in: apple, angle: 140)
    let highlight = NSBezierPath()
    highlight.move(to: NSPoint(x: 267, y: 620))
    highlight.curve(to: NSPoint(x: 447, y: 731),
                    controlPoint1: NSPoint(x: 300, y: 705),
                    controlPoint2: NSPoint(x: 371, y: 735))
    highlight.lineWidth = 24
    highlight.lineCapStyle = .round
    rgba(255, 245, 177, 0.82).setStroke(); highlight.stroke()

    // The foreground ledger is simple enough to read at home-screen size.
    NSGraphicsContext.saveGraphicsState()
    let shadow = NSShadow()
    shadow.shadowColor = rgba(0, 56, 34, 0.32)
    shadow.shadowBlurRadius = 25
    shadow.shadowOffset = NSSize(width: 0, height: -12)
    shadow.set()
    let book = NSBezierPath(roundedRect: NSRect(x: 339, y: 271,
        width: 470, height: 416), xRadius: 48, yRadius: 48)
    NSGradient(starting: rgba(255,255,255), ending: rgba(226,248,225))!
        .draw(in: book, angle: 75)
    NSGraphicsContext.restoreGraphicsState()

    for y in [367.0, 469.0, 571.0] as [CGFloat] {
        let binder = NSBezierPath(ovalIn: NSRect(x: 311, y: y, width: 66, height: 31))
        rgba(245,255,246).setFill(); binder.fill()
        stroke([(363,y+17),(394,y+17)], color: rgba(32,130,58), width: 14)
        stroke([(435,y+18),(457,y-1),(497,y+37)],
               color: rgba(27,174,45), width: 18)
        stroke([(535,y+18),(701,y+18)], color: rgba(115,208,68), width: 14)
    }

    // Small bottom flourish, keeping all information away from icon edges.
    let flourish = NSBezierPath()
    flourish.move(to: NSPoint(x: 179, y: 214))
    flourish.curve(to: NSPoint(x: 883, y: 218),
                   controlPoint1: NSPoint(x: 371, y: 103),
                   controlPoint2: NSPoint(x: 721, y: 134))
    flourish.lineWidth = 22
    flourish.lineCapStyle = .round
    rgba(183,249,41).setStroke()
    flourish.stroke()
}
func render(_ size: Int) throws -> Data {
    guard let bitmap = NSBitmapImageRep(bitmapDataPlanes: nil,
        pixelsWide: size, pixelsHigh: size, bitsPerSample: 8,
        samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
        colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0),
        let context = NSGraphicsContext(bitmapImageRep: bitmap) else {
        throw NSError(domain: "AppIcon", code: 1)
    }
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = context
    context.imageInterpolation = .high
    let transform = NSAffineTransform()
    transform.scaleX(by: CGFloat(size) / 1024, yBy: CGFloat(size) / 1024)
    transform.concat()
    drawArtwork()
    context.flushGraphics()
    NSGraphicsContext.restoreGraphicsState()
    guard let png = bitmap.representation(using: .png, properties: [:]) else {
        throw NSError(domain: "AppIcon", code: 2)
    }
    return png
}

let output = URL(fileURLWithPath: CommandLine.arguments.count > 1
    ? CommandLine.arguments[1] : "ios/Assets.xcassets/AppIcon.appiconset")
try FileManager.default.createDirectory(at: output,
    withIntermediateDirectories: true)
for size in [20,29,40,58,60,76,80,87,120,152,167,180,1024] {
    try render(size).write(to: output.appendingPathComponent("Icon-\(size).png"),
                         options: .atomic)
}
print("AppIcon: generated 13 reproducible PNG sizes, 1024 master included")
