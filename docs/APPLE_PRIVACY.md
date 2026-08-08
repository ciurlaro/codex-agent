# Apple privacy inventory

This inventory covers the `0.2.0` iOS runtime and its final static framework.

## Required-reason APIs

The static framework uses file metadata APIs while confining and protecting the
sandbox-local workspace and Codex home. `PrivacyInfo.xcprivacy` therefore
declares `NSPrivacyAccessedAPICategoryFileTimestamp` reason `C617.1`, for files
inside the application container. The iOS SQLite build disables its filesystem
type probes, so the final binary must not import `statfs` or `fstatfs`; no disk
space reason is declared.

`verifyIosPrivacyManifest` enumerates every archive member in both static
XCFramework slices and inspects each object independently. It scans all five
Apple required-reason API categories, fails for missing or unnecessary
categories and mismatched reasons, verifies both framework manifests and the
archived sample-app placement, and writes the complete member/dependency
inventory to `build/reports/ios-release/privacy/audit.json`. A sample app link
is not used as a proxy for the SDK audit.

## Data flow requiring product approval

The embedded App Server sends the user's prompt, selected local file content,
model output, and the Codex account identifier needed to serve the request to
OpenAI over HTTPS. The library does not track users, does not use advertising,
and does not expose OAuth tokens to Swift. Authentication tokens remain in the
protected, backup-excluded Codex home.

The machine-readable inventory is `release/ios-data-flow-0.2.0.json`. Before
publication, the product owner must decide and approve the exact Apple
collected-data declarations for User ID and Other User Content. Those entries
are deliberately not guessed in the manifest. `verifyPublicationReadiness`
checks that the approval record is bound to the SHA-256 hashes of both the
manifest and data-flow inventory, then blocks while
`release/0.2.0-approvals.json` records that review as incomplete.

Apple provides the aggregate privacy report in Xcode Organizer rather than a
supported command-line export. Automation verifies manifest placement in the
XCFramework and archived sample app; the Organizer report remains a manual
release artifact.
