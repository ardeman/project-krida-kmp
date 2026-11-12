import Foundation

/// 4-byte big-endian length + payload (`docs/protocol.md`). Same as Android `LanFraming`.
enum LanFraming {
    static let maxMessage = 64 * 1024

    static func encode(_ data: Data) throws -> Data {
        guard data.count <= maxMessage else { throw LanError.tooLarge }
        var out = Data(count: 4)
        var size = UInt32(data.count).bigEndian
        withUnsafeBytes(of: &size) { out.replaceSubrange(0..<4, with: $0) }
        out.append(data)
        return out
    }

    /// Appends `chunk` to `buffer` and returns every complete framed message.
    static func takeMessages(from buffer: inout Data) throws -> [Data] {
        var out: [Data] = []
        while buffer.count >= 4 {
            let size = Int(
                UInt32(buffer[buffer.startIndex]) << 24 |
                UInt32(buffer[buffer.startIndex + 1]) << 16 |
                UInt32(buffer[buffer.startIndex + 2]) << 8 |
                UInt32(buffer[buffer.startIndex + 3])
            )
            guard size >= 0, size <= maxMessage else { throw LanError.badLength }
            if buffer.count < 4 + size { break }
            let start = buffer.startIndex + 4
            let end = start + size
            out.append(buffer.subdata(in: start..<end))
            buffer.removeSubrange(buffer.startIndex..<end)
        }
        return out
    }
}

enum LanError: Error {
    case tooLarge, badLength
}
