import XCTest
import Darwin
@testable import RommioFoundation

/// Hashing and copying a game file must not hold the whole file in memory. FileHandle's reads are
/// autoreleased, so a loop without a pool per chunk keeps every chunk until it ends; with a 2.9 GB
/// 3DS zip installed, iOS killed Rommio at launch for passing its memory limit. In this test bundle
/// only the copy loop reproduces the leak; the hash loop leaked on the phone and in an optimized
/// command-line build (514 MB kept for a 512 MB file), so the hash tests guard the shared helper.
final class FileStreamingMemoryTests: XCTestCase {
    private let size: UInt64 = 256 << 20
    private let allowedGrowth: UInt64 = 64 << 20

    func testHashingAGameFileKeepsMemoryFlat() throws {
        let file = try gameFile(size)
        defer { try? FileManager.default.removeItem(at: file) }
        let before = footprint()
        let digest = try ProgressFileHash.sha256(file)
        XCTAssertLessThan(footprintGrowth(since: before), allowedGrowth, "Hashing kept the file in memory")
        XCTAssertEqual(try ManagedDownloadQueue.sha256(file), digest)
        XCTAssertLessThan(footprintGrowth(since: before), allowedGrowth, "The download queue's hash kept the file in memory")
    }

    func testCopyingAGameFileKeepsMemoryFlat() throws {
        let file = try gameFile(size)
        let copy = file.appendingPathExtension("copy")
        defer { try? FileManager.default.removeItem(at: file); try? FileManager.default.removeItem(at: copy) }
        let input = try FileHandle(forReadingFrom: file); defer { try? input.close() }
        let output = try ProgressArchive.output(copy); defer { try? output.close() }
        let before = footprint()
        try ProgressArchive.copy(input, output, length: Int64(size))
        XCTAssertLessThan(footprintGrowth(since: before), allowedGrowth, "Copying kept the file in memory")
        XCTAssertEqual(try ProgressFileHash.sha256(copy), try ProgressFileHash.sha256(file))
    }

    /// The launch path: an actor hashing inside a database write, on a Swift concurrency thread.
    func testHashingInsideADatabaseWriteOnAnActorKeepsMemoryFlat() async throws {
        let file = try gameFile(size)
        let root = FileManager.default.temporaryDirectory.appending(path: "rommio-db-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: file); try? FileManager.default.removeItem(at: root) }
        let database = try AppDatabase(rootDirectory: root)
        let probe = LaunchHashProbe()
        let growth = try await probe.growth(hashing: file, in: database)
        XCTAssertLessThan(growth, allowedGrowth, "Hashing at launch kept the file in memory")
    }

    func testHashesMatchCryptoKit() throws {
        let file = FileManager.default.temporaryDirectory.appending(path: "rommio-hash-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: file) }
        let bytes = Data((0..<3_000_000).map { UInt8(truncatingIfNeeded: $0 &* 31) })
        try bytes.write(to: file)
        XCTAssertEqual(try ProgressFileHash.sha256(file), ProgressProtocol.sha256(bytes))
    }

    /// A file with real contents (a sparse file reads back without touching memory on macOS).
    private func gameFile(_ size: UInt64) throws -> URL {
        let url = FileManager.default.temporaryDirectory.appending(path: "rommio-stream-\(UUID().uuidString)")
        XCTAssertTrue(FileManager.default.createFile(atPath: url.path, contents: nil))
        let handle = try FileHandle(forWritingTo: url)
        let block = Data((0..<(1 << 20)).map { UInt8(truncatingIfNeeded: $0 &* 7 &+ 3) })
        for _ in 0..<(size >> 20) { try handle.write(contentsOf: block) }
        try handle.close()
        return url
    }
}

/// The process's physical footprint, the number iOS holds against an app's memory limit.
private func footprint() -> UInt64 {
    var info = task_vm_info_data_t()
    var count = mach_msg_type_number_t(MemoryLayout<task_vm_info_data_t>.size / MemoryLayout<natural_t>.size)
    let result = withUnsafeMutablePointer(to: &info) {
        $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
            task_info(mach_task_self_, task_flavor_t(TASK_VM_INFO), $0, &count)
        }
    }
    return result == KERN_SUCCESS ? info.phys_footprint : 0
}

/// How much the footprint grew. It can shrink while a test runs, which counts as no growth.
private func footprintGrowth(since before: UInt64) -> UInt64 {
    let now = footprint()
    return now > before ? now - before : 0
}

private actor LaunchHashProbe {
    func growth(hashing file: URL, in database: AppDatabase) throws -> UInt64 {
        let before = footprint()
        _ = try database.write { _ in try ManagedDownloadQueue.sha256(file) }
        return footprintGrowth(since: before)
    }
}
