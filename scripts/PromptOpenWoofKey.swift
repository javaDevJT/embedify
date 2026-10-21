import AppKit
import Foundation
import Security

private let openWoofKeychainService = "openwoof/openwoof.javadevjt.tech/truenas-192.168.0.2/embedify/production"
private let openWoofKeychainAccount = "embedify"
private let bunExecutable = "/opt/homebrew/bin/bun"

private func keychainIdentity() -> [String: Any] {
    [
        kSecClass as String: kSecClassGenericPassword,
        kSecAttrService as String: openWoofKeychainService,
        kSecAttrAccount as String: openWoofKeychainAccount,
    ]
}

private func keychainQuery(returnData: Bool) -> [String: Any] {
    var query = keychainIdentity()
    query[kSecMatchLimit as String] = kSecMatchLimitOne
    if returnData {
        query[kSecReturnData as String] = true
    } else {
        query[kSecReturnAttributes as String] = true
    }
    return query
}

private func existingKeychainItem() throws -> Bool {
    var result: CFTypeRef?
    let status = SecItemCopyMatching(keychainQuery(returnData: false) as CFDictionary, &result)
    if status == errSecItemNotFound { return false }
    guard status == errSecSuccess else { throw NSError(domain: NSOSStatusErrorDomain, code: Int(status)) }
    return true
}

private func loadKeychainSecret() throws -> Data {
    var result: CFTypeRef?
    let status = SecItemCopyMatching(keychainQuery(returnData: true) as CFDictionary, &result)
    guard status == errSecSuccess, let data = result as? Data else {
        throw NSError(domain: NSOSStatusErrorDomain, code: Int(status))
    }
    return data
}

private func storeKeychainSecret(_ secret: Data, replacing: Bool) throws {
    if replacing {
        let status = SecItemUpdate(
            keychainIdentity() as CFDictionary,
            [
                kSecValueData as String: secret,
                kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
            ] as CFDictionary
        )
        guard status == errSecSuccess else { throw NSError(domain: NSOSStatusErrorDomain, code: Int(status)) }
    } else {
        var item = keychainIdentity()
        item[kSecValueData as String] = secret
        item[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        let status = SecItemAdd(item as CFDictionary, nil)
        guard status == errSecSuccess else { throw NSError(domain: NSOSStatusErrorDomain, code: Int(status)) }
    }
}

private func secureInput(replacingExisting: Bool) -> Data? {
    let alert = NSAlert()
    alert.alertStyle = .informational
    alert.messageText = replacingExisting ? "Replace the Embedify OpenWoof key?" : "Enter the Embedify OpenWoof key"
    alert.informativeText = "Target: https://openwoof.javadevjt.tech\nTrueNAS: 192.168.0.2 / Embedify / production\nRequired grants: collector:write, traces:write, metrics:write, logs:write. Prefer a finite expiry; choose Never only intentionally, and keep the key revocable. This is a collector-wide ingestion key; its name does not restrict other services.\nThe value is entered into a secure field, stored in this Mac's Keychain, and sent directly to the protected TrueNAS path. It is never printed or placed in arguments or environment variables."
    alert.addButton(withTitle: replacingExisting ? "Replace and Install" : "Save and Install")
    alert.addButton(withTitle: "Cancel")

    let field = NSSecureTextField(frame: NSRect(x: 0, y: 0, width: 410, height: 24))
    field.placeholderString = "OpenWoof ingest key"
    alert.accessoryView = field
    alert.window.initialFirstResponder = field
    guard alert.runModal() == .alertFirstButtonReturn else { return nil }

    let value = field.stringValue
    field.stringValue = ""
    let bytes = Array(value.utf8)
    guard (16...1024).contains(bytes.count), bytes.allSatisfy({ $0 >= 0x21 && $0 <= 0x7e }) else {
        let invalid = NSAlert()
        invalid.alertStyle = .warning
        invalid.messageText = "The key was not installed"
        invalid.informativeText = "The secure field was empty or contained whitespace. No value was sent."
        invalid.runModal()
        return nil
    }
    return Data(bytes)
}

private func runBun(_ root: URL, arguments: [String], send secret: Data? = nil, capture: Bool = false) throws -> (Int32, Data) {
    let process = Process()
    process.executableURL = URL(fileURLWithPath: bunExecutable)
    process.arguments = arguments
    process.currentDirectoryURL = root

    let stdin = Pipe()
    process.standardInput = stdin

    let stdout = Pipe()
    process.standardOutput = capture ? stdout : FileHandle.standardOutput
    process.standardError = FileHandle.standardError
    try process.run()

    if let data = secret {
        try stdin.fileHandleForWriting.write(contentsOf: data)
        stdin.fileHandleForWriting.closeFile()
    } else {
        stdin.fileHandleForWriting.closeFile()
    }

    let output = capture ? stdout.fileHandleForReading.readDataToEndOfFile() : Data()
    process.waitUntilExit()
    return (process.terminationStatus, output)
}

private func installKey(_ key: inout Data, from root: URL) throws -> Int32 {
    defer { key.resetBytes(in: 0..<key.count) }
    let (status, _) = try runBun(root, arguments: [
        "scripts/truenas.mjs", "install-openwoof-key", "--confirm", "embedify",
    ], send: key)
    return status
}

private func alert(_ title: String, _ message: String, style: NSAlert.Style = .informational) {
    let dialog = NSAlert()
    dialog.alertStyle = style
    dialog.messageText = title
    dialog.informativeText = message
    dialog.runModal()
}

private func fail(_ message: String, status: Int32 = 1) -> Never {
    alert("Embedify key setup stopped", message, style: .warning)
    exit(status)
}

let sourceURL = URL(fileURLWithPath: #filePath).standardizedFileURL
let projectRoot = sourceURL.deletingLastPathComponent().deletingLastPathComponent()
guard FileManager.default.fileExists(atPath: URL(fileURLWithPath: bunExecutable).path),
      FileManager.default.fileExists(atPath: projectRoot.appendingPathComponent("scripts/truenas.mjs").path) else {
    fail("This Mac is missing the expected Bun executable or Embedify helper. No key was requested.")
}

do {
    let (prepareStatus, _) = try runBun(projectRoot, arguments: [
        "scripts/truenas.mjs", "prepare-openwoof-secret", "--confirm", "embedify",
    ], capture: true)
    guard prepareStatus == 0 else { fail("The protected TrueNAS directory could not be prepared. No key was requested.") }

    let (statusCode, statusData) = try runBun(projectRoot, arguments: [
        "scripts/truenas.mjs", "secret-status",
    ], capture: true)
    guard statusCode == 0,
          let status = try JSONSerialization.jsonObject(with: statusData) as? [String: Any],
          let file = status["file"] as? [String: Any],
          let exists = file["exists"] as? Bool,
          let ready = file["ready"] as? Bool else {
        fail("TrueNAS secret-path verification failed. No key was requested.")
    }
    if exists {
        if ready {
            alert("OpenWoof key already installed", "The existing file has the expected protected owner and permissions. Its contents were not read or changed.")
            exit(0)
        }
        fail("A file already exists at the protected path with unexpected metadata. It was preserved and not overwritten.")
    }

    let (authStatus, _) = try runBun(projectRoot, arguments: [
        "scripts/truenas.mjs", "verify-upload-auth",
    ], capture: true)
    guard authStatus == 0 else { fail("TrueNAS rejected the protected upload preflight. No key was requested.") }

    let existing = try existingKeychainItem()
    var replacing = false
    if existing {
        let choice = NSAlert()
        choice.alertStyle = .informational
        choice.messageText = "A matching OpenWoof Keychain item exists"
        choice.informativeText = "Service: \(openWoofKeychainService)\nAccount: \(openWoofKeychainAccount)\nChoose the existing key, explicitly replace this exact item, or cancel. Its value will not be displayed."
        choice.addButton(withTitle: "Use Existing")
        choice.addButton(withTitle: "Replace Key")
        choice.addButton(withTitle: "Cancel")
        switch choice.runModal() {
        case .alertFirstButtonReturn:
            break
        case .alertSecondButtonReturn:
            replacing = true
        default:
            exit(0)
        }
    }

    var secret: Data?
    do {
        if existing && !replacing {
            secret = try loadKeychainSecret()
        } else {
            secret = secureInput(replacingExisting: replacing)
        }
    } catch {
        fail("The exact Keychain item could not be read securely. No value was sent.")
    }
    guard var key = secret else { exit(0) }
    secret?.resetBytes(in: 0..<(secret?.count ?? 0))
    secret = nil

    if replacing || !existing {
        do {
            try storeKeychainSecret(key, replacing: replacing)
        } catch {
            key.resetBytes(in: 0..<key.count)
            fail("The key could not be stored in the exact Keychain item. No value was sent.")
        }
    }

    let installStatus: Int32
    do {
        installStatus = try installKey(&key, from: projectRoot)
    } catch {
        fail("The secure TrueNAS transfer could not start. The Keychain item was retained for a safe retry; no key value was displayed.")
    }
    if installStatus != 0 {
        fail("TrueNAS did not install the key. The Keychain item was retained for a safe retry; no key value was displayed.")
    }
    alert("OpenWoof key installed", "The key was transferred to the protected TrueNAS file and is mounted read-only by the Collector.")
} catch {
    fail("Secure key setup failed before the value could be disclosed. Check the nonsecret error reported by the helper.")
}
