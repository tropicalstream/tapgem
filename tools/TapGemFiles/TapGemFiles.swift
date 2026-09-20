// TapGem Files — a file manager for the RayNeo X3 Pro.
//
// Why this exists instead of OpenMTP: the glasses' firmware ships with
// com.android.mtp disabled and persist.sys.usb.config pinned to "adb", so the
// device never advertises an MTP interface. No MTP-based Mac app can ever see
// it. adb is the only channel onto /sdcard that works on this hardware, so this
// app wraps adb in the file-manager UI that MTP would have given us: browse,
// drag files and folders in, select and delete.

import SwiftUI
import AppKit
import UniformTypeIdentifiers

// ─────────────────────────────────────────────────────────────────────
// adb plumbing
// ─────────────────────────────────────────────────────────────────────

enum Adb {
    /// Homebrew first, then the usual places, then $PATH — so the app keeps
    /// working on a machine where adb lives somewhere else.
    static let path: String = {
        let candidates = ["/opt/homebrew/bin/adb", "/usr/local/bin/adb",
                          NSHomeDirectory() + "/Library/Android/sdk/platform-tools/adb"]
        for c in candidates where FileManager.default.isExecutableFile(atPath: c) { return c }
        return "/usr/bin/env"   // last resort: resolve "adb" off PATH
    }()

    @discardableResult
    static func run(_ args: [String], timeout: TimeInterval = 60) -> (out: String, err: String, code: Int32) {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: path)
        p.arguments = path.hasSuffix("env") ? ["adb"] + args : args
        let outPipe = Pipe(), errPipe = Pipe()
        p.standardOutput = outPipe; p.standardError = errPipe
        do { try p.run() } catch { return ("", "could not launch adb: \(error.localizedDescription)", -1) }

        // Read both pipes concurrently; a full pipe buffer would otherwise deadlock a big push.
        var outData = Data(), errData = Data()
        let q = DispatchQueue(label: "adb.io", attributes: .concurrent)
        let group = DispatchGroup()
        q.async(group: group) { outData = outPipe.fileHandleForReading.readDataToEndOfFile() }
        q.async(group: group) { errData = errPipe.fileHandleForReading.readDataToEndOfFile() }
        p.waitUntilExit()
        group.wait()
        return (String(decoding: outData, as: UTF8.self),
                String(decoding: errData, as: UTF8.self),
                p.terminationStatus)
    }

    /// The first device adb reports, with its model name for the status bar.
    static func device() -> (serial: String, model: String)? {
        let r = run(["devices", "-l"])
        for line in r.out.split(separator: "\n").dropFirst() {
            let parts = line.split(separator: " ", omittingEmptySubsequences: true)
            guard parts.count >= 2, parts[1] == "device" else { continue }
            let model = line.range(of: "model:").map {
                String(line[$0.upperBound...]).split(separator: " ").first.map(String.init) ?? ""
            } ?? ""
            return (String(parts[0]), model.replacingOccurrences(of: "_", with: " "))
        }
        return nil
    }

    static func shell(_ serial: String, _ command: String) -> (out: String, err: String, code: Int32) {
        run(["-s", serial, "shell", command])
    }

    /// Single-quote a path for the device shell; the only character that needs
    /// care inside single quotes is the single quote itself.
    static func quote(_ s: String) -> String {
        "'" + s.replacingOccurrences(of: "'", with: "'\\''") + "'"
    }
}

// ─────────────────────────────────────────────────────────────────────
// model
// ─────────────────────────────────────────────────────────────────────

struct Entry: Identifiable, Hashable {
    let name: String
    let isDir: Bool
    let size: Int64
    let date: String
    var id: String { name }

    var icon: String {
        if isDir { return "folder.fill" }
        switch (name as NSString).pathExtension.lowercased() {
        case "mp3", "m4a", "flac", "wav", "ogg", "aac": return "music.note"
        case "jpg", "jpeg", "png", "gif", "webp", "bmp": return "photo"
        case "mp4", "mkv", "mov", "webm", "avi":         return "film"
        case "glb", "gltf", "obj", "fbx":                return "cube"
        case "pdf":                                       return "doc.richtext"
        case "txt", "log", "json", "xml", "md":          return "doc.plaintext"
        case "zip", "apk", "wsz", "tar", "gz":           return "shippingbox"
        default:                                          return "doc"
        }
    }

    var sizeText: String {
        if isDir { return "—" }
        let units = ["B", "KB", "MB", "GB"]
        var v = Double(size), i = 0
        while v >= 1024, i < units.count - 1 { v /= 1024; i += 1 }
        return i == 0 ? "\(size) B" : String(format: "%.1f %@", v, units[i])
    }
}

@MainActor
final class Store: ObservableObject {
    @Published var serial: String?
    @Published var model = ""
    @Published var path = "/sdcard"
    @Published var entries: [Entry] = []
    @Published var selection = Set<String>()
    @Published var busy = false
    @Published var status = ""
    @Published var history: [String] = []

    // `ls -la` on toybox: perms links owner group size date time name.
    // The name is captured with .* so spaces in filenames survive.
    nonisolated private static let row = try! NSRegularExpression(
        pattern: #"^([dlrwxsStT\-]{10})\s+\d+\s+\S+\s+\S+\s+(\d+)\s+(\d{4}-\d{2}-\d{2})\s+(\d{2}:\d{2})\s+(.*)$"#)

    func connect() {
        guard let d = Adb.device() else {
            serial = nil; model = ""; entries = []
            status = "No device — plug the glasses in and unlock them."
            return
        }
        serial = d.serial; model = d.model.isEmpty ? "Android device" : d.model
        refresh()
    }

    func refresh() {
        guard let s = serial else { return }
        busy = true
        let target = path
        Task.detached {
            // Trailing slash matters: /sdcard is a symlink, and `ls -la` on a symlink
            // lists the link itself rather than what's inside it.
            let listPath = target.hasSuffix("/") ? target : target + "/"
            let r = Adb.shell(s, "ls -la \(Adb.quote(listPath)) 2>/dev/null")
            let parsed = Self.parse(r.out)
            await MainActor.run {
                self.entries = parsed
                self.selection.removeAll()
                self.busy = false
                if r.code != 0 && parsed.isEmpty { self.status = "Couldn't read \(target)" }
                else { self.status = "" }
            }
        }
    }

    nonisolated private static func parse(_ text: String) -> [Entry] {
        var out: [Entry] = []
        for line in text.split(separator: "\n", omittingEmptySubsequences: true) {
            let l = String(line).trimmingCharacters(in: .whitespaces)
            let ns = l as NSString
            guard let m = row.firstMatch(in: l, range: NSRange(location: 0, length: ns.length)),
                  m.numberOfRanges == 6 else { continue }
            let perms = ns.substring(with: m.range(at: 1))
            let size = Int64(ns.substring(with: m.range(at: 2))) ?? 0
            let date = ns.substring(with: m.range(at: 3))
            var name = ns.substring(with: m.range(at: 5))
            if name == "." || name == ".." { continue }
            // Symlinks list as "name -> target"; show just the name.
            if perms.hasPrefix("l"), let arrow = name.range(of: " -> ") { name = String(name[..<arrow.lowerBound]) }
            out.append(Entry(name: name, isDir: perms.hasPrefix("d") || perms.hasPrefix("l"),
                             size: size, date: date))
        }
        return out.sorted {
            $0.isDir == $1.isDir ? $0.name.localizedStandardCompare($1.name) == .orderedAscending : $0.isDir
        }
    }

    func go(to newPath: String) {
        history.append(path)
        path = newPath
        refresh()
    }

    func goBack() {
        guard let prev = history.popLast() else { return }
        path = prev
        refresh()
    }

    func goUp() {
        guard path != "/" else { return }
        go(to: (path as NSString).deletingLastPathComponent)
    }

    func enter(_ e: Entry) {
        guard e.isDir else { return }
        go(to: (path as NSString).appendingPathComponent(e.name))
    }

    /// Push dropped files/folders into the directory on screen. adb push copies
    /// a directory recursively on its own, so folders arrive whole.
    func push(_ urls: [URL]) {
        guard let s = serial, !urls.isEmpty else { return }
        busy = true
        let dest = path
        Task.detached {
            var okCount = 0, failCount = 0
            var lastErrorText = ""
            for u in urls {
                let remote = dest.hasSuffix("/") ? dest : dest + "/"
                let r = Adb.run(["-s", s, "push", u.path, remote], timeout: 600)
                if r.code == 0 { okCount += 1 } else { failCount += 1; lastErrorText = r.err.trimmingCharacters(in: .whitespacesAndNewlines) }
            }
            let ok = okCount, failed = failCount, lastError = lastErrorText
            // Let the media scanner see new audio/images right away.
            _ = Adb.shell(s, "am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d \(Adb.quote("file://" + dest)) >/dev/null 2>&1")
            await MainActor.run {
                self.busy = false
                self.status = failed == 0
                    ? "Copied \(ok) item\(ok == 1 ? "" : "s") to \(dest)"
                    : "Copied \(ok), failed \(failed) — \(lastError.isEmpty ? "see adb output" : lastError)"
                self.refresh()
            }
        }
    }

    func delete(_ names: [String]) {
        guard let s = serial, !names.isEmpty else { return }
        busy = true
        let base = path
        Task.detached {
            var okCount = 0, failCount = 0
            for n in names {
                let full = (base as NSString).appendingPathComponent(n)
                let r = Adb.shell(s, "rm -rf \(Adb.quote(full))")
                if r.code == 0 { okCount += 1 } else { failCount += 1 }
            }
            let ok = okCount, failed = failCount
            _ = Adb.shell(s, "am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d \(Adb.quote("file://" + base)) >/dev/null 2>&1")
            await MainActor.run {
                self.busy = false
                self.status = failed == 0 ? "Deleted \(ok) item\(ok == 1 ? "" : "s")"
                                          : "Deleted \(ok), failed \(failed)"
                self.refresh()
            }
        }
    }

    var totalText: String {
        let files = entries.filter { !$0.isDir }
        let bytes = files.reduce(Int64(0)) { $0 + $1.size }
        let units = ["B", "KB", "MB", "GB"]
        var v = Double(bytes), i = 0
        while v >= 1024, i < units.count - 1 { v /= 1024; i += 1 }
        let size = i == 0 ? "\(bytes) B" : String(format: "%.1f %@", v, units[i])
        let dirs = entries.count - files.count
        return "\(entries.count) item\(entries.count == 1 ? "" : "s") · \(dirs) folder\(dirs == 1 ? "" : "s") · \(size)"
    }
}

// ─────────────────────────────────────────────────────────────────────
// UI
// ─────────────────────────────────────────────────────────────────────

let places: [(String, String, String)] = [
    ("Storage",   "internaldrive", "/sdcard"),
    ("Music",     "music.note",    "/sdcard/Music"),
    ("Download",  "arrow.down.circle", "/sdcard/Download"),
    ("Pictures",  "photo",         "/sdcard/Pictures"),
    ("Movies",    "film",          "/sdcard/Movies"),
    ("TapGem",    "sparkles",      "/sdcard/Android/data/com.tapgem.app/files"),
]

struct ContentView: View {
    @StateObject var store = Store()
    @State private var dropping = false
    @State private var confirmDelete = false

    var body: some View {
        VStack(spacing: 0) {
            toolbar
            Divider()
            HStack(spacing: 0) {
                sidebar
                Divider()
                listing
            }
            Divider()
            statusBar
        }
        .frame(minWidth: 720, minHeight: 440)
        .onAppear { store.connect() }
        .onDrop(of: [UTType.fileURL], isTargeted: $dropping) { providers in
            load(providers); return true
        }
        .alert("Delete \(store.selection.count) item\(store.selection.count == 1 ? "" : "s")?",
               isPresented: $confirmDelete) {
            Button("Cancel", role: .cancel) {}
            Button("Delete", role: .destructive) { store.delete(Array(store.selection)) }
        } message: {
            Text("This removes them from the glasses for good. There is no trash on the device to recover from.")
        }
    }

    private var toolbar: some View {
        HStack(spacing: 10) {
            Button { store.goBack() } label: { Image(systemName: "chevron.left") }
                .disabled(store.history.isEmpty).help("Back")
            Button { store.goUp() } label: { Image(systemName: "chevron.up") }
                .disabled(store.path == "/").help("Enclosing folder")
            Button { store.refresh() } label: { Image(systemName: "arrow.clockwise") }
                .help("Refresh")

            Text(store.path)
                .font(.system(.body, design: .monospaced))
                .lineLimit(1).truncationMode(.head)
                .frame(maxWidth: .infinity, alignment: .leading)

            if store.busy { ProgressView().scaleEffect(0.5).frame(width: 16, height: 16) }

            Button(role: .destructive) { confirmDelete = true } label: {
                Image(systemName: "trash")
            }
            .disabled(store.selection.isEmpty)
            .help("Delete selected")
        }
        .buttonStyle(.borderless)
        .padding(.horizontal, 12).padding(.vertical, 8)
    }

    private var sidebar: some View {
        VStack(alignment: .leading, spacing: 2) {
            ForEach(places, id: \.2) { (label, icon, p) in
                Button { store.go(to: p) } label: {
                    Label(label, systemImage: icon)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.vertical, 4).padding(.horizontal, 8)
                        .background(store.path == p ? Color.accentColor.opacity(0.18) : .clear)
                        .clipShape(RoundedRectangle(cornerRadius: 5))
                }
                .buttonStyle(.plain)
            }
            Spacer()
        }
        .padding(8)
        .frame(width: 150)
    }

    private var listing: some View {
        ZStack {
            Table(store.entries, selection: $store.selection) {
                TableColumn("Name") { e in
                    Label { Text(e.name) } icon: {
                        Image(systemName: e.icon).foregroundStyle(e.isDir ? Color.accentColor : .secondary)
                    }
                    .onTapGesture(count: 2) { store.enter(e) }
                }
                TableColumn("Size") { e in Text(e.sizeText).foregroundStyle(.secondary) }
                    .width(min: 70, ideal: 90)
                TableColumn("Date") { e in Text(e.date).foregroundStyle(.secondary) }
                    .width(min: 90, ideal: 110)
            }
            .onDeleteCommand { if !store.selection.isEmpty { confirmDelete = true } }

            if store.entries.isEmpty && !store.busy {
                VStack(spacing: 6) {
                    Image(systemName: "tray").font(.system(size: 26)).foregroundStyle(.tertiary)
                    Text(store.serial == nil ? "No device connected" : "Empty folder")
                        .foregroundStyle(.secondary)
                    Text("Drag files or folders here to copy them over")
                        .font(.caption).foregroundStyle(.tertiary)
                }
            }

            if dropping {
                RoundedRectangle(cornerRadius: 8)
                    .strokeBorder(Color.accentColor, style: StrokeStyle(lineWidth: 2, dash: [6]))
                    .background(Color.accentColor.opacity(0.08))
                    .overlay(
                        Text("Drop to copy into \(store.path)")
                            .font(.headline).foregroundStyle(Color.accentColor))
                    .padding(6)
                    .allowsHitTesting(false)
            }
        }
    }

    private var statusBar: some View {
        HStack(spacing: 8) {
            Circle().fill(store.serial == nil ? Color.red : Color.green).frame(width: 7, height: 7)
            Text(store.serial == nil ? "Not connected" : store.model)
            if store.serial != nil { Text("·").foregroundStyle(.tertiary); Text(store.totalText) }
            Spacer()
            Text(store.status).foregroundStyle(.secondary).lineLimit(1)
            Button { store.connect() } label: { Image(systemName: "cable.connector") }
                .buttonStyle(.borderless).help("Reconnect")
        }
        .font(.caption)
        .padding(.horizontal, 12).padding(.vertical, 6)
    }

    private func load(_ providers: [NSItemProvider]) {
        let group = DispatchGroup()
        var urls: [URL] = []
        let lock = NSLock()
        for p in providers where p.hasItemConformingToTypeIdentifier(UTType.fileURL.identifier) {
            group.enter()
            _ = p.loadObject(ofClass: URL.self) { url, _ in
                if let url { lock.lock(); urls.append(url); lock.unlock() }
                group.leave()
            }
        }
        group.notify(queue: .main) { store.push(urls) }
    }
}

@main
struct TapGemFilesApp: App {
    var body: some Scene {
        WindowGroup("TapGem Files") { ContentView() }
            .windowToolbarStyle(.unified)
            .commands { CommandGroup(replacing: .newItem) {} }
    }
}
