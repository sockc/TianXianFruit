import AppKit
import CoreGraphics
import Foundation

// Deterministic, dependency-free 1024px iOS icon master.
// The vector geometry mirrors the approved fruit + ledger visual direction.
// Apple home screen applies its own corner mask. Do not draw rounded edges.
let canvas = 1024
guard CommandLine.arguments.count == 2,
      let ctx = CGContext(data: nil, width: canvas, height: canvas,
                          bitsPerComponent: 8, bytesPerRow: 0,
                          space: CGColorSpaceCreateDeviceRGB(),
                          bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)
else {
    fputs("Usage: swift draw_app_icon.swift output.png\n", stderr)
    exit(1)
}
func ink(_ r: CGFloat, _ g: CGFloat, _ b: CGFloat, _ a: CGFloat = 1) -> CGColor {
    CGColor(colorSpace: CGColorSpaceCreateDeviceRGB(),
            components: [r, g, b, a])!
}
func rect(_ x: CGFloat, _ y: CGFloat, _ w: CGFloat, _ h: CGFloat) -> CGRect {
    CGRect(x: x, y: y, width: w, height: h)
}
func fillEllipse(_ x: CGFloat, _ y: CGFloat, _ w: CGFloat, _ h: CGFloat,
                 _ color: CGColor) {
    ctx.setFillColor(color)
    ctx.fillEllipse(in: rect(x, y, w, h))
}
func fillPath(_ path: CGPath, _ color: CGColor) {
    ctx.setFillColor(color)
    ctx.addPath(path)
    ctx.fillPath()
}
func line(_ points: [CGPoint], color: CGColor, width: CGFloat,
          cap: CGLineCap = .round) {
    guard let first = points.first else { return }
    ctx.setLineCap(cap)
    ctx.setLineJoin(.round)
    ctx.setLineWidth(width)
    ctx.setStrokeColor(color)
    ctx.move(to: first)
    for point in points.dropFirst() { ctx.addLine(to: point) }
    ctx.strokePath()
}

let space = CGColorSpaceCreateDeviceRGB()
let greenGradient = CGGradient(colorsSpace: space, colors: [
    ink(0.01, 0.61, 0.39), ink(0.005, 0.33, 0.26)
] as CFArray, locations: [0, 1])!
ctx.drawLinearGradient(greenGradient, start: CGPoint(x: 30, y: 980),
                       end: CGPoint(x: 960, y: 20), options: [])

// Soft darker panel behind the white checklist.
ctx.setFillColor(ink(0.005, 0.33, 0.24, 0.30))
ctx.addPath(CGPath(roundedRect: rect(276, 245, 520, 595),
                   cornerWidth: 100, cornerHeight: 100, transform: nil))
ctx.fillPath()

let white = ink(1, 1, 1)
let paper = CGPath(roundedRect: rect(298, 254, 486, 582),
                   cornerWidth: 84, cornerHeight: 84, transform: nil)
ctx.setLineWidth(41)
ctx.setStrokeColor(white)
ctx.addPath(paper)
ctx.strokePath()

// Binder rings on the left.
for y in [700.0, 574.0, 448.0] {
    ctx.setFillColor(white)
    ctx.addPath(CGPath(roundedRect: rect(230, CGFloat(y), 116, 43),
                       cornerWidth: 20, cornerHeight: 20, transform: nil))
    ctx.fillPath()
}

// Checklist: three ticks and three short ledger lines.
for y in [728.0, 605.0, 482.0] {
    line([CGPoint(x: 448, y: y), CGPoint(x: 474, y: y - 24),
          CGPoint(x: 518, y: y + 32)], color: white, width: 29)
    line([CGPoint(x: 570, y: y + 5), CGPoint(x: 716, y: y + 5)],
         color: white, width: 33)
}

// Banana: smooth golden crescent behind red apple.
let banana = CGMutablePath()
banana.move(to: CGPoint(x: 690, y: 285))
banana.addCurve(to: CGPoint(x: 925, y: 412),
                control1: CGPoint(x: 870, y: 280), control2: CGPoint(x: 922, y: 312))
banana.addCurve(to: CGPoint(x: 930, y: 494),
                control1: CGPoint(x: 940, y: 450), control2: CGPoint(x: 933, y: 472))
banana.addCurve(to: CGPoint(x: 850, y: 475),
                control1: CGPoint(x: 912, y: 506), control2: CGPoint(x: 877, y: 504))
banana.addCurve(to: CGPoint(x: 690, y: 285),
                control1: CGPoint(x: 846, y: 360), control2: CGPoint(x: 793, y: 306))
banana.closeSubpath()
fillPath(banana, ink(1.0, 0.86, 0.12))
let bananaLight = CGMutablePath()
bananaLight.move(to: CGPoint(x: 742, y: 340))
bananaLight.addCurve(to: CGPoint(x: 895, y: 470),
                     control1: CGPoint(x: 868, y: 373), control2: CGPoint(x: 888, y: 423))
ctx.setLineWidth(18)
ctx.setStrokeColor(ink(1.0, 0.96, 0.48))
ctx.addPath(bananaLight)
ctx.strokePath()

// Red apple with stem and high contrast leaf.
fillEllipse(395, 261, 278, 282, ink(0.98, 0.17, 0.15))
fillEllipse(428, 285, 245, 238, ink(0.91, 0.09, 0.11))
let stem = CGMutablePath()
stem.move(to: CGPoint(x: 546, y: 514))
stem.addCurve(to: CGPoint(x: 577, y: 572),
              control1: CGPoint(x: 542, y: 548), control2: CGPoint(x: 563, y: 553))
ctx.setStrokeColor(ink(0.51, 0.25, 0.04))
ctx.setLineWidth(22)
ctx.setLineCap(.round)
ctx.addPath(stem)
ctx.strokePath()
let leaf = CGMutablePath()
leaf.move(to: CGPoint(x: 544, y: 544))
leaf.addQuadCurve(to: CGPoint(x: 646, y: 590), control: CGPoint(x: 589, y: 631))
leaf.addQuadCurve(to: CGPoint(x: 544, y: 544), control: CGPoint(x: 592, y: 540))
fillPath(leaf, ink(0.26, 0.81, 0.18))
fillEllipse(441, 449, 76, 27, ink(1.0, 0.60, 0.51, 0.78))

// Bright grape cluster on the left.
let grapes: [(CGFloat, CGFloat, CGFloat)] = [
    (183, 334, 90), (247, 374, 86), (307, 340, 88),
    (225, 284, 90), (306, 268, 90), (377, 302, 85),
    (254, 218, 85), (330, 206, 81)
]
for (x, y, d) in grapes {
    fillEllipse(x, y, d, d, ink(0.60, 0.88, 0.04))
    fillEllipse(x + d*0.16, y + d*0.62, d*0.26, d*0.13,
                ink(0.92, 0.99, 0.48))
}
let leftLeaf = CGMutablePath()
leftLeaf.move(to: CGPoint(x: 275, y: 419))
leftLeaf.addQuadCurve(to: CGPoint(x: 121, y: 478), control: CGPoint(x: 144, y: 488))
leftLeaf.addQuadCurve(to: CGPoint(x: 275, y: 419), control: CGPoint(x: 146, y: 389))
fillPath(leftLeaf, ink(0.24, 0.79, 0.16))

// Whole orange and citrus slice across the foreground.
fillEllipse(528, 179, 241, 227, ink(1.0, 0.50, 0.025))
fillEllipse(557, 211, 181, 170, ink(1.0, 0.65, 0.04))
fillEllipse(469, 158, 181, 166, ink(1.0, 0.82, 0.15))
fillEllipse(485, 175, 149, 135, ink(1.0, 0.95, 0.73))
fillEllipse(496, 183, 129, 118, ink(1.0, 0.60, 0.04))
for (dx,dy) in [(0,50),(47,18),(44,-33),(0,-46),(-45,-16),(-43,23)] {
    line([CGPoint(x: 559, y: 241),
          CGPoint(x: CGFloat(559+dx), y: CGFloat(241+dy))],
         color: white, width: 6)
}
let rightLeaf = CGMutablePath()
rightLeaf.move(to: CGPoint(x: 700, y: 326))
rightLeaf.addQuadCurve(to: CGPoint(x: 790, y: 404), control: CGPoint(x: 741, y: 422))
rightLeaf.addQuadCurve(to: CGPoint(x: 700, y: 326), control: CGPoint(x: 781, y: 335))
fillPath(rightLeaf, ink(0.18, 0.69, 0.17))

// The white sweep makes the logo coherent at 60px and 180px.
let sweep = CGMutablePath()
sweep.move(to: CGPoint(x: 140, y: 199))
sweep.addCurve(to: CGPoint(x: 871, y: 214),
               control1: CGPoint(x: 299, y: 60), control2: CGPoint(x: 749, y: 102))
ctx.setStrokeColor(white)
ctx.setLineWidth(28)
ctx.setLineCap(.round)
ctx.addPath(sweep)
ctx.strokePath()

guard let image = ctx.makeImage(),
      let encoded = NSBitmapImageRep(cgImage: image)
          .representation(using: .png, properties: [:]) else {
    fputs("Cannot encode app icon PNG\n", stderr)
    exit(2)
}
try encoded.write(to: URL(fileURLWithPath: CommandLine.arguments[1]))
print("Generated 1024x1024 opaque AppIcon master")
