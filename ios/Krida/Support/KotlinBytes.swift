import CoreBluetooth
import Foundation
import Shared

/// Data <-> Kotlin ByteArray. Messages are small (board-game moves), so a plain loop is fine.
extension Data {
    func toKotlin() -> KotlinByteArray {
        let array = KotlinByteArray(size: Int32(count))
        for (i, byte) in enumerated() {
            array.set(index: Int32(i), value: Int8(bitPattern: byte))
        }
        return array
    }
}

extension KotlinByteArray {
    func toData() -> Data {
        var data = Data(count: Int(size))
        for i in 0..<Int(size) {
            data[i] = UInt8(bitPattern: get(index: Int32(i)))
        }
        return data
    }
}

/// Values from shared/…/protocol/Krida.kt as CoreBluetooth types.
enum KridaBle {
    static let service = CBUUID(string: Krida.shared.BLE_SERVICE_UUID)
    static let toHost = CBUUID(string: Krida.shared.BLE_TO_HOST_UUID)
    static let toGuest = CBUUID(string: Krida.shared.BLE_TO_GUEST_UUID)
    /// Same as TransportListener.HOST_PEER in Kotlin.
    static let hostPeer = "host"
}
