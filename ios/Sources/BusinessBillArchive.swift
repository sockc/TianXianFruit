import Foundation
import Compression

/// In-memory ZIP/XLSX reader: no extraction paths, no network, bounded input,
/// entry count, expanded bytes, cell count. Refuse ambiguous archives rather
/// than silently choosing a different merchant statement.
enum TXBillArchive {
    static let expandedLimit = 40 * 1024 * 1024
    struct Table {
        let name: String
        let rows: [[String]]
    }

    static func read(fileName: String, data: Data, depth: Int = 0) throws -> Table {
        guard data.count <= TXBillCSV.maximumBytes, depth <= 2 else { throw TXBillCSVError.tooLarge }
        if !data.starts(with: [0x50,0x4b]) {
            let source = try TXBillCSV.decode(data)
            // Choose delimiter from a structurally recognized header, never
            // a free-text memo. A valid CSV wins over tab separated input.
            let csv = try? TXBillCSV.table(source)
            if let csv, csv.contains(where: { $0.count >= 3 }) { return Table(name: fileName, rows: csv) }
            return Table(name: fileName, rows: try TXBillCSV.table(source, separator: "\t"))
        }
        let files = try unzip(data)
        if files["xl/workbook.xml"] != nil {
            let names = files.keys.filter { $0.range(of: "^xl/worksheets/sheet[0-9]+\\.xml$", options: .regularExpression) != nil }.sorted()
            guard names.count == 1, let sheet = files[names[0]] else { throw TXBillCSVError.ambiguous }
            let shared = try files["xl/sharedStrings.xml"].map { try TXBillSheet.shared($0) } ?? []
            return Table(name: fileName, rows: try TXBillSheet.rows(sheet, shared: shared))
        }
        let names = files.keys.filter {
            let lower = $0.lowercased()
            return !lower.hasPrefix("__macosx/") && [".csv",".txt",".xlsx"].contains { lower.hasSuffix($0) }
        }
        guard names.count == 1, let content = files[names[0]] else { throw TXBillCSVError.ambiguous }
        return try read(fileName: names[0], data: content, depth: depth + 1)
    }

    private static func u16(_ d: Data, _ p: Int) -> Int {
        Int(d[p]) | (Int(d[p+1]) << 8)
    }
    private static func u32(_ d: Data, _ p: Int) -> Int {
        u16(d,p) | (u16(d,p+2) << 16)
    }
    private static let crcTable: [UInt32] = (0..<256).map { n in
        var c = UInt32(n)
        for _ in 0..<8 { c = (c & 1 != 0) ? (0xedb88320 ^ (c >> 1)) : (c >> 1) }
        return c
    }
    static func crc(_ data: Data) -> UInt32 {
        var value: UInt32 = 0xffffffff
        for b in data { value = crcTable[Int((value ^ UInt32(b)) & 255)] ^ (value >> 8) }
        return value ^ 0xffffffff
    }

    static func unzip(_ bytes: Data) throws -> [String: Data] {
        let d = Data(bytes)
        guard d.count >= 22, d.count <= TXBillCSV.maximumBytes else { throw TXBillCSVError.malformed }
        var end: Int?
        for i in stride(from: d.count-22, through: max(0,d.count-65557), by: -1) {
            if u32(d,i) == 0x06054b50 && i+22+u16(d,i+20) == d.count { end = i; break }
        }
        guard let end, u16(d,end+4) == 0, u16(d,end+6) == 0 else { throw TXBillCSVError.unsupported }
        let count = u16(d,end+10), size = u32(d,end+12), offset = u32(d,end+16)
        guard count == u16(d,end+8), count > 0, count <= 128,
              offset <= end, size == end-offset else { throw TXBillCSVError.malformed }
        var cursor = offset, total = 0, files: [String: Data] = [:], names = Set<String>()
        for _ in 0..<count {
            guard cursor+46 <= end, u32(d,cursor) == 0x02014b50 else { throw TXBillCSVError.malformed }
            let flag = u16(d,cursor+8), method = u16(d,cursor+10)
            let expectedCRC = UInt32(u32(d,cursor+16))
            let compressed = u32(d,cursor+20), expanded = u32(d,cursor+24)
            let nameLen = u16(d,cursor+28), extra = u16(d,cursor+30), comment = u16(d,cursor+32)
            let local = u32(d,cursor+42)
            let after = cursor+46+nameLen+extra+comment
            guard flag & 1 == 0, [0,8].contains(method), u16(d,cursor+34) == 0,
                  nameLen > 0, after <= end, expanded <= expandedLimit-total,
                  local+30 <= offset, u32(d,local) == 0x04034b50,
                  u16(d,local+6) == flag, u16(d,local+8) == method else { throw TXBillCSVError.unsupported }
            let nameData = d.subdata(in: cursor+46..<cursor+46+nameLen)
            guard let name = String(data: nameData, encoding: .utf8),
                  !name.hasPrefix("/"), !name.contains("\\"), !name.split(separator: "/").contains(".."),
                  !name.contains("\u{0}"), names.insert(name).inserted else { throw TXBillCSVError.malformed }
            let localName = u16(d,local+26), localExtra = u16(d,local+28)
            let start = local+30+localName+localExtra
            guard local+30+localName <= offset,
                  d.subdata(in: local+30..<local+30+localName) == nameData,
                  start <= offset, compressed <= offset-start else { throw TXBillCSVError.malformed }
            cursor = after
            if name.hasSuffix("/") { guard expanded == 0 else { throw TXBillCSVError.malformed }; continue }
            let input = d.subdata(in: start..<start+compressed)
            let output: Data
            if method == 0 {
                guard compressed == expanded else { throw TXBillCSVError.malformed }
                output = input
            } else {
                // Apple's COMPRESSION_ZLIB operates on raw DEFLATE streams.
                // One extra destination byte prevents accepting truncation.
                var buffer = Data(count: expanded+1)
                let written = buffer.withUnsafeMutableBytes { dst in
                    input.withUnsafeBytes { src -> Int in
                        guard let a = dst.bindMemory(to: UInt8.self).baseAddress,
                              let b = src.bindMemory(to: UInt8.self).baseAddress else { return -1 }
                        return compression_decode_buffer(a, expanded+1, b, compressed, nil, COMPRESSION_ZLIB)
                    }
                }
                guard written == expanded else { throw TXBillCSVError.malformed }
                output = buffer.prefix(expanded)
            }
            guard crc(output) == expectedCRC else { throw TXBillCSVError.malformed }
            total += expanded; files[name] = output
        }
        guard cursor == end else { throw TXBillCSVError.malformed }
        return files
    }
}

/// Limited OOXML table reader. External entities, DTDs and formula-derived
/// values are rejected; preserve text identifiers and never invent rounded IDs.
private final class TXBillSheet: NSObject, XMLParserDelegate {
    enum Mode { case shared, sheet }
    let mode: Mode
    let strings: [String]
    var result: [[String]] = [], sharedResult: [String] = []
    var row: [Int: String] = [:], column = -1, kind = "", value = ""
    var inCell = false, inItem = false, capture = false, failed = false
    init(mode: Mode, strings: [String] = []) { self.mode = mode; self.strings = strings }
    private static func run(_ data: Data, reader: TXBillSheet) throws {
        // XMLParser must not parse document type declarations or external entities.
        guard let raw = String(data: data, encoding: .utf8),
              !raw.uppercased().contains("<!DOCTYPE"), !raw.uppercased().contains("<!ENTITY") else { throw TXBillCSVError.unsupported }
        let parser = XMLParser(data: data)
        parser.shouldResolveExternalEntities = false
        parser.delegate = reader
        guard parser.parse(), !reader.failed else { throw TXBillCSVError.malformed }
    }
    static func shared(_ data: Data) throws -> [String] {
        let reader = TXBillSheet(mode: .shared); try run(data, reader: reader); return reader.sharedResult
    }
    static func rows(_ data: Data, shared: [String]) throws -> [[String]] {
        let reader = TXBillSheet(mode: .sheet, strings: shared); try run(data, reader: reader); return reader.result
    }
    func parser(_ parser: XMLParser, didStartElement element: String, namespaceURI: String?,
                qualifiedName: String?, attributes: [String: String]) {
        if mode == .shared {
            if element == "si" { inItem = true; value = "" }
            if inItem && element == "t" { capture = true }
            return
        }
        if element == "row" { row = [:]; column = -1 }
        if element == "c" {
            inCell = true; value = ""; kind = attributes["t"] ?? "n"
            if let reference = attributes["r"] {
                var position = 0, digits = false
                for u in reference.utf8 {
                    if u >= 65 && u <= 90 && !digits {
                        position = position*26 + Int(u-64)
                        if position > 256 { failed = true; parser.abortParsing(); return }
                    } else if u >= 48 && u <= 57 { digits = true }
                    else { failed = true; parser.abortParsing(); return }
                }
                column = position-1
            } else { column += 1 }
            if !(0..<256).contains(column) || row[column] != nil { failed = true; parser.abortParsing() }
        }
        if inCell && element == "f" { failed = true; parser.abortParsing() }
        if inCell && (element == "v" || (kind == "inlineStr" && element == "t")) { capture = true }
    }
    func parser(_ parser: XMLParser, foundCharacters string: String) {
        if capture {
            value += string
            if value.utf8.count > 131072 { failed = true; parser.abortParsing() }
        }
    }
    func parser(_ parser: XMLParser, didEndElement element: String, namespaceURI: String?, qualifiedName: String?) {
        if element == "t" || element == "v" { capture = false }
        if mode == .shared {
            if element == "si" {
                if sharedResult.count >= 100000 { failed = true; parser.abortParsing(); return }
                sharedResult.append(value); inItem = false
            }
            return
        }
        if element == "c" {
            var output = value
            if kind == "s" {
                guard let index = Int(value), strings.indices.contains(index) else { failed = true; parser.abortParsing(); return }
                output = strings[index]
            } else if kind == "n" {
                let digitCount = value.filter { $0.isNumber }.count
                if digitCount > 15 || value.lowercased().contains("e+") || value.lowercased().contains("e-") {
                    output = "UNSAFE_NUMERIC_ID:" + value
                }
            } else if !["inlineStr","str","b","d"].contains(kind) {
                failed = true; parser.abortParsing(); return
            }
            row[column] = output; inCell = false
        }
        if element == "row" {
            guard result.count < 100000 else { failed = true; parser.abortParsing(); return }
            if let last = row.keys.max() { result.append((0...last).map { row[$0] ?? "" }) }
        }
    }
}
