ObjC.import("AppKit");
ObjC.import("Foundation");
ObjC.import("Security");
ObjC.bindFunction("kill", ["int", ["int", "int"]]);

const OPENWOOF_KEYCHAIN_ACCOUNT = "embedify";
const OPERATOR_HOME = $.NSFileManager.defaultManager.homeDirectoryForCurrentUser.path.js;
// Pin the operator-installed executable: never resolve a secret consumer from PATH.
const BUN_EXECUTABLE = OPERATOR_HOME + "/.bun/bin/bun";

function utf8Data(value) {
  return $(value).dataUsingEncoding($.NSUTF8StringEncoding);
}

function dataString(data) {
  const value = $.NSString.alloc.initWithDataEncoding(data, $.NSUTF8StringEncoding);
  if (!value) throw new Error("UTF-8 conversion failed");
  return value.js;
}

function runTask(executable, argumentsList, inputData, currentDirectory) {
  const task = $.NSTask.alloc.init;
  task.setLaunchPath(executable);
  task.setArguments($(argumentsList));
  if (currentDirectory) task.setCurrentDirectoryPath(currentDirectory);
  // Do not forward caller-controlled Bun/Node/dynamic-loader options to a
  // process that will receive a credential through stdin.
  task.setEnvironment($({
    HOME: OPERATOR_HOME,
    PATH: "/usr/bin:/bin:/usr/sbin:/sbin",
    TMPDIR: $.NSTemporaryDirectory().js,
    LANG: "en_US.UTF-8",
  }));

  const input = $.NSPipe.pipe;
  const output = $.NSPipe.pipe;
  const errorOutput = $.NSPipe.pipe;
  task.setStandardInput(input);
  task.setStandardOutput(output);
  task.setStandardError(errorOutput);

  const launchError = $();
  if (!task.launchAndReturnError(launchError)) throw new Error("A required local process could not start");
  if (inputData) input.fileHandleForWriting.writeData(inputData);
  input.fileHandleForWriting.closeFile;
  const deadline = Date.now() + 120000;
  while (task.running && Date.now() < deadline) $.NSThread.sleepForTimeInterval(0.05);
  if (task.running) {
    task.terminate;
    const graceDeadline = Date.now() + 1000;
    while (task.running && Date.now() < graceDeadline) $.NSThread.sleepForTimeInterval(0.05);
    if (task.running) $.kill(Number(task.processIdentifier), 9);
    throw new Error("A protected local process timed out; inspect its outcome before retrying");
  }
  task.waitUntilExit;

  return {
    status: Number(task.terminationStatus),
    stdout: output.fileHandleForReading.readDataToEndOfFile,
    stderr: errorOutput.fileHandleForReading.readDataToEndOfFile,
  };
}

function runBun(root, argumentsList, inputData) {
  return runTask(BUN_EXECUTABLE, argumentsList, inputData, root);
}

function requestSecureInput(title, message, continueLabel) {
  const app = Application.currentApplication();
  app.includeStandardAdditions = true;
  $.NSApplication.sharedApplication.setActivationPolicy(1);
  $.NSApplication.sharedApplication.activateIgnoringOtherApps(true);
  try {
    const response = app.displayDialog(message, {
      withTitle: title,
      defaultAnswer: "",
      hiddenAnswer: true,
      buttons: ["Cancel", continueLabel],
      defaultButton: continueLabel,
      cancelButton: "Cancel",
    });
    return { accepted: true, value: response.textReturned };
  } catch (error) {
    if (error.errorNumber === -128 || error.message === "User canceled.") return { accepted: false, value: "" };
    throw new Error("The secure Mac dialog could not be opened");
  }
}

function projectRoot() {
  const current = $.NSFileManager.defaultManager.currentDirectoryPath.js;
  const root = $.NSString.stringWithString(current).stringByStandardizingPath.js;
  if (!$.NSFileManager.defaultManager.fileExistsAtPath(root + "/scripts/truenas.mjs")) {
    throw new Error("Run this helper from the Embedify repository root");
  }
  return root;
}

function parseSecretStatus(stdout) {
  const result = JSON.parse(dataString(stdout));
  const file = result.file;
  if (!file || typeof file.exists !== "boolean" || typeof file.ready !== "boolean") {
    throw new Error("TrueNAS returned an unexpected secret status");
  }
  return file;
}

function runBunStep(root, args, inputData) {
  const result = runBun(root, args, inputData || null);
  if (result.status !== 0) throw new Error("A protected TrueNAS preflight or transfer step failed");
  return result;
}

function readKeychainIdentity(root) {
  const result = runBunStep(root, ["scripts/truenas.mjs", "keychain-identity"]);
  try {
    const identity = JSON.parse(dataString(result.stdout));
    if (identity.account !== OPENWOOF_KEYCHAIN_ACCOUNT
        || typeof identity.service !== "string"
        || !/^openwoof\/openwoof\.javadevjt\.tech\/truenas-[a-z0-9.:-]+\/embedify\/production$/.test(identity.service)
        || typeof identity.target !== "string" || !identity.target) {
      throw new Error("The local target returned an unexpected Keychain identity");
    }
    return identity;
  } finally {
    result.stdout = null;
    result.stderr = null;
  }
}

function securityObject(value) {
  return ObjC.castRefToObject(value);
}

function keychainQuery(identity) {
  const query = $.NSMutableDictionary.alloc.init;
  query.setObjectForKey(securityObject($.kSecClassGenericPassword), securityObject($.kSecClass));
  query.setObjectForKey($(OPENWOOF_KEYCHAIN_ACCOUNT), securityObject($.kSecAttrAccount));
  query.setObjectForKey($(identity.service), securityObject($.kSecAttrService));
  return query;
}

function keychainItemExists(identity) {
  const status = Number($.SecItemCopyMatching(keychainQuery(identity), null));
  if (status === 0) return true;
  if (status === -25300) return false; // errSecItemNotFound
  throw new Error("The exact OpenWoof Keychain item could not be checked");
}

function retrieveKeychainSecret(identity) {
  const query = keychainQuery(identity);
  query.setObjectForKey($(true), securityObject($.kSecReturnData));
  query.setObjectForKey(securityObject($.kSecMatchLimitOne), securityObject($.kSecMatchLimit));
  $.NSApplication.sharedApplication.setActivationPolicy(1);
  $.NSApplication.sharedApplication.activateIgnoringOtherApps(true);
  const result = Ref();
  const status = Number($.SecItemCopyMatching(query, result));
  if (status !== 0) throw new Error("The exact OpenWoof Keychain item could not be read");
  const data = securityObject(result[0]);
  // No subprocess stdout channel is used to retrieve the credential.
  validateSecret(dataString(data));
  return data;
}

function validateSecret(value) {
  const length = value.length;
  if (length < 16 || length > 1024 || !/^[\x21-\x7e]+$/.test(value)) {
    throw new Error("The key was empty, too long, or contained whitespace or non-ASCII characters");
  }
}

function storeNewSecret(value, identity) {
  validateSecret(value);
  const item = keychainQuery(identity);
  item.setObjectForKey(utf8Data(value), securityObject($.kSecValueData));
  try {
    if (Number($.SecItemAdd(item, null)) !== 0) throw new Error("macOS Keychain did not store the new key; existing items are preserved");
  } finally {
    item.removeObjectForKey(securityObject($.kSecValueData));
    value = "";
  }
}

function requestNewSecret(identity) {
  const response = requestSecureInput(
    "Enter the Embedify OpenWoof key",
    "For Embedify / production on TrueNAS " + identity.target + ".\nOpenWoof: https://openwoof.javadevjt.tech\nRequired grants: collector:write, traces:write, metrics:write, logs:write. Prefer finite expiry; Never is your explicit choice. Key names do not restrict collector-wide grants.\n\nThis step saves the key only in this Mac's Keychain. The separate installation step transfers it directly to TrueNAS over verified TLS. No key is returned or printed.",
    "Save to Keychain"
  );
  if (!response.accepted) return false;
  try {
    validateSecret(response.value);
    storeNewSecret(response.value, identity);
    return true;
  } finally {
    response.value = "";
  }
}

function runSelfTest() {
  const root = projectRoot();
  const secureField = $.NSSecureTextField.alloc.initWithFrame($.NSMakeRect(0, 0, 320, 24));
  const alert = $.NSAlert.alloc.init;
  alert.setMessageText("Secure input check");
  alert.setInformativeText("No key is requested by this test.");
  alert.setAccessoryView(secureField);
  alert.addButtonWithTitle("Continue");
  alert.addButtonWithTitle("Cancel");
  alert.buttons.objectAtIndex(1).setKeyEquivalent("\u001b");
  alert.window.setInitialFirstResponder(secureField);
  const queryProbe = keychainQuery({ service: "openwoof/nonsecret-query-fixture" });
  if (!secureField || Number(queryProbe.count) !== 3
      || typeof $.SecItemCopyMatching !== "function" || typeof $.SecItemAdd !== "function"
      || !$.NSFileManager.defaultManager.isExecutableFileAtPath(BUN_EXECUTABLE)) {
    throw new Error("Required macOS secure-input or local tooling is unavailable");
  }
  const probe = runTask("/usr/bin/wc", ["-c"], utf8Data("nonsecret-fixture"));
  if (probe.status !== 0 || Number(dataString(probe.stdout).trim()) !== 17) {
    throw new Error("The private process-stdin handoff self-test failed");
  }
  const bunProbe = runBun(root, ["scripts/truenas.mjs", "self-test"], null);
  if (bunProbe.status !== 0) throw new Error("The TrueNAS helper self-test failed");
  bunProbe.stdout = null;
  bunProbe.stderr = null;

  readKeychainIdentity(root);
  return "OpenWoof secure-field and private-stdin self-test passed; no Keychain entry was accessed or changed.";
}

function run(argv) {
  try {
    if (argv && argv.indexOf("--self-test") !== -1) return runSelfTest();
    const root = projectRoot();
    const mode = argv && argv.length === 1 ? argv[0] : "";
    if (mode !== "--store-keychain" && mode !== "--install-keychain" && mode !== "--keychain-status") {
      return "Usage: --store-keychain (local secure prompt) | --install-keychain (direct TrueNAS delivery) | --keychain-status (metadata only) | --self-test (nonsecret fixture)";
    }
    const identity = readKeychainIdentity(root);
    if (mode === "--keychain-status") {
      return keychainItemExists(identity) ? "OpenWoof Keychain status: exact item exists; its value was not read." : "OpenWoof Keychain status: exact item is missing; no entry was changed.";
    }
    if (mode === "--store-keychain") {
      if (keychainItemExists(identity)) {
        return "OpenWoof Keychain status: exact existing item preserved; its value was not read and no network request was made.";
      }
      if (!requestNewSecret(identity)) return "OpenWoof Keychain setup cancelled; no value was sent.";
      return "OpenWoof Keychain status: key stored locally; no network request was made.";
    }

    runBunStep(root, ["scripts/truenas.mjs", "prepare-openwoof-secret", "--confirm", "embedify"]);
    const statusResult = runBunStep(root, ["scripts/truenas.mjs", "secret-status"]);
    const file = parseSecretStatus(statusResult.stdout);
    if (file.exists) {
      if (file.ready) {
        return "OpenWoof key setup complete; the existing protected file was preserved.";
      }
      throw new Error("A file exists at the protected path with unexpected metadata; it was preserved");
    }

    runBunStep(root, ["scripts/truenas.mjs", "verify-upload-auth"]);

    if (!keychainItemExists(identity)) throw new Error("Store the key locally before the separate installation step");

    const keyBytes = retrieveKeychainSecret(identity);
    const installed = runBunStep(root, [
      "scripts/truenas.mjs", "install-openwoof-key", "--confirm", "embedify",
    ], keyBytes);
    installed.stdout = null;
    installed.stderr = null;
    return "OpenWoof key installed on the protected TrueNAS path; Collector deployment is a separate step.";
  } catch (error) {
    if (argv && argv.indexOf("--self-test") !== -1) {
      throw new Error("OpenWoof osascript helper self-test failed: " + String(error.message || "unknown local check"));
    }
    // Replace process/framework errors with a fixed message: no child output or
    // credential-bearing data can enter the osascript exception or result.
    throw new Error("OpenWoof key setup stopped; inspect Keychain and TrueNAS status before retrying. No secret was returned or printed.");
  }
}
