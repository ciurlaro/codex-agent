import CodexAgent
import Foundation
import XCTest
@testable import CodexAgentAuthentication

@MainActor
final class CodexHostCoordinatorTests: XCTestCase {
    func testAsyncStartPublishesSharedWorkspaceRequiredState() async throws {
        let sandbox = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: sandbox, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: sandbox) }

        let host = CodexHostCoordinator(
            sandboxRootPath: sandbox.path,
            clientVersion: "test",
            browser: CodexWebAuthenticationBrowser()
        )
        var states = host.states.makeAsyncIterator()
        let initial = await states.next()
        XCTAssertEqual(initial?.host.status, .theNew)

        try await host.start()
        XCTAssertEqual(host.state.host.status, .workspaceRequired)
        XCTAssertNotNil(host.state.host.workspaceRequirement)
        host.close()
    }
}
