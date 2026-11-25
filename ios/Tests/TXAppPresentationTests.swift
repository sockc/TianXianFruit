import XCTest
@testable import TianXianFruit

final class TXAppPresentationTests: XCTestCase {
    func testVersionFromBundleValues() {
        XCTAssertEqual(TXAppPresentation.versionLabel(version: "1.0.0", build: "71"), "iOS V1.0.0 · Build 71")
        XCTAssertEqual(TXAppPresentation.versionLabel(version: "0.9.9", build: nil), "iOS V0.9.9")
        XCTAssertEqual(TXAppPresentation.versionLabel(version: " ", build: "71"), "iOS · 版本未知")
    }
}
