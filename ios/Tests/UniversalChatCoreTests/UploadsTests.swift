import XCTest
@testable import UniversalChatCore

final class UploadsTests: XCTestCase {
    private func file(bytes: Int) throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("uploads-test-\(UUID().uuidString)")
        FileManager.default.createFile(atPath: url.path, contents: nil)
        let handle = try FileHandle(forWritingTo: url)
        // Sparse, so the test does not write the bytes it measures.
        try handle.truncate(atOffset: UInt64(bytes))
        try handle.close()
        addTeardownBlock { try? FileManager.default.removeItem(at: url) }
        return url
    }

    func testAFileOverTheLimitIsRefusedBeforeItIsRead() throws {
        let url = try file(bytes: Uploads.maxBytes + 1)

        XCTAssertThrowsError(try Uploads.read(fileAt: url)) { error in
            XCTAssertEqual(error as? Uploads.ReadError, .tooLarge)
        }
    }

    func testAFileWithinTheLimitIsReadWhole() throws {
        let url = try file(bytes: 3)

        XCTAssertEqual(try Uploads.read(fileAt: url).count, 3)
    }

    func testAMissingFileIsUnreadable() {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("missing-\(UUID().uuidString)")

        XCTAssertThrowsError(try Uploads.read(fileAt: url)) { error in
            XCTAssertEqual(error as? Uploads.ReadError, .unreadable)
        }
    }
}
