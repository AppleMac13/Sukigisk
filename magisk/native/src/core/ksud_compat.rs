// ksud-compatible command surface for the SukiSU manager front-end.
//
// The manager speaks the KernelSU "ksud" CLI. This module maps the subset that
// Magisk can actually serve onto the Magisk module store (/data/adb/modules),
// and fails fast (non-zero) on anything Magisk has no equivalent for, so the
// front-end degrades instead of hanging.
//
// Contract: this is additive. Nothing here changes Magisk's own CLI; magisk.rs
// only hands control over when argv[1] is one of the ksud sub-commands below.

use crate::consts::MODULEROOT;
use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::process::Command;

const KSUD_SUB_COMMANDS: [&str; 16] = [
    "module",
    "feature",
    "install",
    "boot-info",
    "boot-patch",
    "profile",
    "manager",
    "extension",
    "kpm",
    "susfs",
    "umount",
    "kernel",
    "sepolicy",
    "debug",
    "soft-reboot",
    "temporary-root",
];

pub fn is_ksud_command(name: &str) -> bool {
    KSUD_SUB_COMMANDS.contains(&name)
}

fn fail(msg: &str) -> i32 {
    eprintln!("{msg}");
    1
}

fn module_root() -> PathBuf {
    PathBuf::from(MODULEROOT)
}

fn read_prop(path: &Path) -> Vec<(String, String)> {
    let mut out = Vec::new();
    let Ok(text) = fs::read_to_string(path) else {
        return out;
    };
    for line in text.lines() {
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        if let Some((k, v)) = line.split_once('=') {
            out.push((k.trim().to_string(), v.trim().to_string()));
        }
    }
    out
}

fn prop(map: &[(String, String)], key: &str) -> String {
    map.iter()
        .find(|(k, _)| k == key)
        .map(|(_, v)| v.clone())
        .unwrap_or_default()
}

fn json_escape(s: &str) -> String {
    let mut out = String::with_capacity(s.len() + 8);
    for c in s.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if (c as u32) < 0x20 => out.push(' '),
            c => out.push(c),
        }
    }
    out
}

fn valid_id(id: &str) -> bool {
    !id.is_empty()
        && id.len() <= 64
        && id
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '.' || c == '-')
}

/// Zygisk implementations the manager recognises (same id list as ksud).
fn is_zygisk_provider(id: &str) -> bool {
    matches!(
        id.to_ascii_lowercase().as_str(),
        "zygisksu" | "rezygisk" | "nyazygisk" | "neozygisk" | "yukizygisk"
    )
}

/// Snapshot of every process comm / cmdline basename currently alive.
///
/// The manager's "Zygisk implementation" row needs to know whether the provider
/// daemon is actually up, not just installed, so it can tell "running" apart
/// from "installed, needs reboot".
fn running_process_names() -> Vec<String> {
    let mut names: Vec<String> = Vec::new();
    let Ok(entries) = fs::read_dir("/proc") else {
        return names;
    };
    for entry in entries.flatten() {
        let pid = entry.file_name();
        let pid = pid.to_string_lossy();
        if !pid.chars().all(|c| c.is_ascii_digit()) {
            continue;
        }
        if let Ok(comm) = fs::read_to_string(entry.path().join("comm")) {
            let comm = comm.trim();
            if !comm.is_empty() {
                names.push(comm.to_string());
            }
        }
        if let Ok(cmdline) = fs::read(entry.path().join("cmdline")) {
            for arg in cmdline.split(|b| *b == 0) {
                if arg.is_empty() {
                    continue;
                }
                let arg = String::from_utf8_lossy(arg);
                let base = arg.rsplit('/').next().unwrap_or_default();
                if !base.is_empty() {
                    names.push(base.to_string());
                }
            }
        }
    }
    names
}

fn zygisk_daemon_running(id: &str, names: &[String]) -> bool {
    let id = id.to_ascii_lowercase();
    let matches_impl = |name: &str| -> bool {
        match id.as_str() {
            // Zygisk Next >= 1.5.0 renames its daemon to zn-daemon/zn-daemon64
            // for stealth, so the legacy zygisk* patterns no longer catch it.
            "zygisksu" => {
                name.starts_with("zygiskd")
                    || name.starts_with("zygisk-ptrace")
                    || name.starts_with("zygisk-comp")
                    || name.starts_with("zygisk_comp")
                    || name.starts_with("zn-daemon")
                    || name.contains("zygiskd")
                    || name.contains("zygisk")
            }
            "rezygisk" => name.starts_with("rezygisk") || name.contains("rezygisk"),
            "nyazygisk" => {
                name.starts_with("nyazygisk")
                    || name.starts_with("zygiskd")
                    || name.contains("nyazygisk")
                    || name.contains("zygisk")
            }
            "neozygisk" => {
                name.starts_with("neozygisk")
                    || name.starts_with("zygiskd")
                    || name.contains("neozygisk")
                    || name.contains("zygisk")
            }
            "yukizygisk" => {
                name.starts_with("yukizygisk")
                    || name.starts_with("zygiskd")
                    || name.contains("yukizygisk")
                    || name.contains("zygisk")
            }
            _ => false,
        }
    };
    names.iter().any(|name| matches_impl(name))
}

fn module_list() -> i32 {
    let root = module_root();
    let process_names = running_process_names();
    let mut items: Vec<String> = Vec::new();
    let Ok(dir) = fs::read_dir(&root) else {
        println!("[]");
        return 0;
    };
    for entry in dir.flatten() {
        let path = entry.path();
        if !path.is_dir() {
            continue;
        }
        let id = match path.file_name().and_then(|n| n.to_str()) {
            Some(n) => n.to_string(),
            None => continue,
        };
        if id == ".core" || id.starts_with('.') || !valid_id(&id) {
            continue;
        }
        let props = read_prop(&path.join("module.prop"));
        let name = prop(&props, "name");
        let author = prop(&props, "author");
        let version = prop(&props, "version");
        let version_code: i64 = prop(&props, "versionCode").parse().unwrap_or(0);
        let description = prop(&props, "description");
        let update_json = prop(&props, "updateJson");
        let enabled = !path.join("disable").exists();
        let remove = path.join("remove").exists();
        let update = path.join("update").exists();
        let has_action = path.join("action.sh").exists();
        let has_web = path.join("webroot").join("index.html").exists();
        let zygisk_provider = is_zygisk_provider(&id);
        let zygisk_running = zygisk_provider && zygisk_daemon_running(&id, &process_names);
        let metamodule = path.join("metamodule").exists() || path.join("metamodule.prop").exists();
        let action_icon = if path.join("action.sh").exists() {
            for candidate in ["icon.png", "action_icon.png", "webroot/icon.png"] {
                let p = path.join(candidate);
                if p.exists() {
                    // The manager only needs a readable path; it copies/decodes it itself.
                    break;
                }
            }
            String::new()
        } else {
            String::new()
        };
        let item = format!(
            concat!(
                "{{\"id\":\"{id}\",\"name\":\"{name}\",\"author\":\"{author}\",",
                "\"version\":\"{version}\",\"versionCode\":{vc},\"description\":\"{desc}\",",
                "\"enabled\":{enabled},\"update\":{update},\"remove\":{remove},",
                "\"updateJson\":\"{uj}\",\"web\":{web},\"action\":{action},",
                "\"metamodule\":{meta},\"zygisk\":{zyg},\"zygisk_provider\":{zyg},\"zygisk_running\":{zrun},\"actionIcon\":\"{aicon}\",",
                "\"webuiIcon\":\"\",\"preinit\":\"\",\"banner\":\"\"}}"
            ),
            id = json_escape(&id),
            name = json_escape(&name),
            author = json_escape(&author),
            version = json_escape(&version),
            vc = version_code,
            desc = json_escape(&description),
            enabled = enabled,
            update = update,
            remove = remove,
            uj = json_escape(&update_json),
            web = has_web,
            action = has_action,
            meta = metamodule,
            zyg = zygisk_provider,
            zrun = zygisk_running,
            aicon = json_escape(&action_icon),
        );
        items.push(item);
    }
    println!("[{}]", items.join(","));
    0
}

fn module_dir(id: &str) -> Option<PathBuf> {
    if !valid_id(id) {
        return None;
    }
    Some(module_root().join(id))
}

fn touch(dir: &Path, name: &str) -> bool {
    if let Ok(mut f) = fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(dir.join(name))
    {
        let _ = f.write_all(b"");
        return true;
    }
    false
}

fn module_cmd(args: &[String]) -> i32 {
    let Some(sub) = args.first() else {
        return fail("module: missing subcommand");
    };
    match sub.as_str() {
        "list" => module_list(),
        "install" => {
            let Some(zip) = args.get(1) else {
                return fail("module install: missing zip");
            };
            // Reuse Magisk's own installer path instead of duplicating it.
            let Ok(exe) = std::env::current_exe() else {
                return fail("module install: cannot resolve magisk binary");
            };
            match Command::new(exe).arg("--install-module").arg(zip).status() {
                Ok(st) if st.success() => 0,
                Ok(st) => st.code().unwrap_or(1),
                Err(e) => fail(&format!("module install: {e}")),
            }
        }
        "enable" | "disable" => {
            let Some(id) = args.get(1) else {
                return fail("module: missing module id");
            };
            let Some(dir) = module_dir(id) else {
                return fail("module: invalid module id");
            };
            if !dir.is_dir() {
                return fail("module: not installed");
            }
            let flag = dir.join("disable");
            if sub == "enable" {
                if flag.exists() && fs::remove_file(&flag).is_err() {
                    return fail("module enable: cannot remove disable flag");
                }
                0
            } else {
                if touch(&dir, "disable") {
                    0
                } else {
                    fail("module disable: cannot create disable flag")
                }
            }
        }
        "uninstall" => {
            let Some(id) = args.get(1) else {
                return fail("module uninstall: missing module id");
            };
            let Some(dir) = module_dir(id) else {
                return fail("module uninstall: invalid module id");
            };
            if !dir.is_dir() {
                return fail("module uninstall: not installed");
            }
            if touch(&dir, "remove") {
                0
            } else {
                fail("module uninstall: cannot create remove flag")
            }
        }
        "undo-uninstall" => {
            let Some(id) = args.get(1) else {
                return fail("module undo-uninstall: missing module id");
            };
            let Some(dir) = module_dir(id) else {
                return fail("module undo-uninstall: invalid module id");
            };
            let flag = dir.join("remove");
            if flag.exists() && fs::remove_file(&flag).is_err() {
                return fail("module undo-uninstall: cannot remove remove flag");
            }
            0
        }
        "action" => {
            let Some(id) = args.get(1) else {
                return fail("module action: missing module id");
            };
            let Some(dir) = module_dir(id) else {
                return fail("module action: invalid module id");
            };
            let script = dir.join("action.sh");
            if !script.is_file() {
                return fail("module action: no action.sh");
            }
            match Command::new("/system/bin/sh").arg(&script).status() {
                Ok(st) if st.success() => 0,
                Ok(st) => st.code().unwrap_or(1),
                Err(e) => fail(&format!("module action: {e}")),
            }
        }
        _ => fail("module: unsupported subcommand"),
    }
}

fn feature_cmd(args: &[String]) -> i32 {
    let Some(sub) = args.first() else {
        return fail("feature: missing subcommand");
    };
    match sub.as_str() {
        // Magisk has no kernel feature registry: nothing is kernel-backed here.
        "check" => {
            println!("unsupported");
            0
        }
        "get" => {
            println!("Value: 0");
            0
        }
        _ => fail("feature: unsupported subcommand"),
    }
}

fn boot_info_cmd(_args: &[String]) -> i32 {
    // Boot image patching is owned by Magisk's own app flow (magiskboot), which
    // this front-end does not drive. Answer with inert defaults so callers that
    // merely probe do not crash, and let the UI keep these screens hidden.
    println!("unknown");
    0
}

fn debug_cmd(args: &[String]) -> i32 {
    // `ksud debug su` is the manager's root-shell creator. Magisk grants root
    // through its own su, so refuse immediately and let libsu fall back to it.
    let _ = args;
    fail("debug: not supported on this backend")
}

/// Magisk's own installer directory (boot_patch.sh + magiskboot + magiskinit).
fn magisk_install_dir() -> PathBuf {
    PathBuf::from("/data/adb/magisk")
}

/// `ksud boot-patch`: patch a boot image with Magisk's own installer.
///
/// The manager's install screen sends `boot-patch -b <img> -o <dir>
/// [--out-name <name>]` plus KernelSU-only flags (--kmi, --spoof-*, -m,
/// --partition, -u). Magisk has no LKM/config side, so those are ignored; only
/// the image patch is performed, and the result is written where the caller
/// asked. Direct flashing (`-f`, no image) is refused on purpose: writing the
/// boot partition belongs to Magisk's app flow, not to a grafted front-end.
fn boot_patch_cmd(args: &[String]) -> i32 {
    let mut boot: Option<PathBuf> = None;
    let mut out_dir: Option<PathBuf> = None;
    let mut out_name: Option<String> = None;
    let mut want_flash = false;

    let mut i = 0;
    while i < args.len() {
        let a = args[i].as_str();
        let takes_value = matches!(
            a,
            "-b" | "--boot"
                | "-o" | "--out"
                | "--out-name"
                | "--partition"
                | "--spoof-release"
                | "--spoof-version"
                | "--block-module"
                | "-m"
                | "--module"
                | "--kmi"
                | "--init"
                | "--kernel"
                | "--cmdline"
                | "--adb-debug-prop"
        );
        if takes_value {
            let v = args.get(i + 1).cloned().unwrap_or_default();
            match a {
                "-b" | "--boot" => boot = Some(PathBuf::from(&v)),
                "-o" | "--out" => out_dir = Some(PathBuf::from(&v)),
                "--out-name" => out_name = Some(v),
                _ => {}
            }
            i += 2;
            continue;
        }
        if matches!(a, "-f" | "--flash") {
            want_flash = true;
        }
        i += 1;
    }

    let Some(boot_img) = boot else {
        return if want_flash {
            fail("boot-patch: direct install is handled by the Magisk app, not this front-end")
        } else {
            fail("boot-patch: missing boot image (-b <path>)")
        };
    };
    if !boot_img.is_file() {
        return fail(&format!("boot-patch: {} not found", boot_img.display()));
    }

    let dir = magisk_install_dir();
    let patcher = dir.join("boot_patch.sh");
    if !patcher.is_file() {
        return fail("boot-patch: /data/adb/magisk/boot_patch.sh is missing");
    }

    println!("- Patching boot image with Magisk installer");
    println!("- Input: {}", boot_img.display());
    let status = Command::new("/system/bin/sh")
        .arg(&patcher)
        .arg(&boot_img)
        .current_dir(&dir)
        .status();
    match status {
        Ok(s) if s.success() => {}
        Ok(s) => {
            return fail(&format!(
                "boot-patch: boot_patch.sh failed with {}",
                s.code().unwrap_or(-1)
            ))
        }
        Err(e) => return fail(&format!("boot-patch: cannot run boot_patch.sh: {e}")),
    }

    let produced = dir.join("new-boot.img");
    if !produced.is_file() {
        return fail("boot-patch: boot_patch.sh produced no image");
    }

    let name = out_name
        .filter(|n| !n.is_empty())
        .unwrap_or_else(|| "magisk_patched.img".to_string());
    let mut target_dir = out_dir.unwrap_or_else(|| PathBuf::from("/sdcard/Download"));
    if !target_dir.is_dir() {
        if fs::create_dir_all(&target_dir).is_err() {
            target_dir = PathBuf::from("/data/local/tmp");
        }
    }
    let target = target_dir.join(&name);
    let copied = fs::copy(&produced, &target).is_ok();
    if !copied {
        // /sdcard is FUSE-backed; if the copy is refused, keep the image where
        // root can always reach it and report the real path.
        let fallback = PathBuf::from("/data/local/tmp").join(&name);
        if fs::copy(&produced, &fallback).is_err() {
            let _ = fs::remove_file(&produced);
            return fail("boot-patch: cannot write patched image");
        }
        println!("- Output: {}", fallback.display());
        let _ = fs::remove_file(&produced);
        let _ = fs::remove_file(dir.join("stock_boot.img"));
        let _ = fs::remove_file(dir.join("ramdisk.cpio"));
        let _ = fs::remove_file(dir.join("dtb"));
        return 0;
    }

    println!("- Output: {}", target.display());
    // boot_patch.sh leaves its scratch files in the installer directory.
    for scratch in ["new-boot.img", "stock_boot.img", "ramdisk.cpio", "dtb"] {
        let _ = fs::remove_file(dir.join(scratch));
    }
    0
}

// Per-app root state, served from Magisk's `policies` table.
//
// The manager's own permanent "superuser" switch for an app goes through
// `temporary-root apply` too, so this sub-command is the single write path for
// app root state. SuPolicy in Magisk core is 0=Query 1=Deny 2=Allow
// 3=Restrict, so allow is written as 2 and revoke as 1.

fn magisk_sqlite(sql: &str) -> Option<String> {
    let exe = std::env::current_exe().ok()?;
    let out = Command::new(exe).arg("--sqlite").arg(sql).output().ok()?;
    Some(String::from_utf8_lossy(&out.stdout).into_owned())
}

fn row_int(row: &str, key: &str) -> Option<i64> {
    for part in row.split('|') {
        let Some((k, v)) = part.split_once('=') else {
            continue;
        };
        if k.trim() == key {
            return v.trim().parse::<i64>().ok();
        }
    }
    None
}

fn query_policy(uid: i32) -> Option<(i64, i64)> {
    let sql = format!("SELECT uid, policy, until FROM policies WHERE uid={uid}");
    let out = magisk_sqlite(&sql)?;
    for line in out.lines() {
        let line = line.trim();
        if !line.starts_with("uid=") {
            continue;
        }
        let Some(u) = row_int(line, "uid") else {
            continue;
        };
        if u != uid as i64 {
            continue;
        }
        let Some(p) = row_int(line, "policy") else {
            continue;
        };
        let until = row_int(line, "until").unwrap_or(0);
        return Some((p, until));
    }
    None
}

/// Bumps the stamp the in-process bridge watches so it re-reads the policy
/// table after this (separate) process writes to it.
fn touch_pol_stamp() {
    let dir = "/data/user/0/com.sukisu.ultra/files";
    let _ = std::fs::create_dir_all(dir);
    let ms = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0);
    let _ = std::fs::write(format!("{dir}/.mb_pol_stamp"), ms.to_string());
}

fn write_policy(uid: i32, policy: i64, until: i64) -> bool {
    let upd = format!("UPDATE policies SET policy={policy}, until={until} WHERE uid={uid}");
    let _ = magisk_sqlite(&upd);
    if matches!(query_policy(uid), Some((p, _)) if p == policy) {
        touch_pol_stamp();
        return true;
    }
    // policies has five columns: uid, policy, until, logging, notification.
    let ins = format!(
        "INSERT OR REPLACE INTO policies (uid,policy,until,logging,notification) VALUES({uid},{policy},{until},1,1)"
    );
    let _ = magisk_sqlite(&ins);
    let ok = matches!(query_policy(uid), Some((p, _)) if p == policy);
    if ok {
        touch_pol_stamp();
    }
    ok
}

fn json_int(json: &str, key: &str) -> Option<i64> {
    let needle = format!("\"{key}\":");
    let start = json.find(&needle)? + needle.len();
    let rest = json[start..].trim_start();
    let num: String = rest
        .chars()
        .take_while(|c| c.is_ascii_digit() || *c == '-')
        .collect();
    num.parse().ok()
}

fn json_bool(json: &str, key: &str) -> Option<bool> {
    let needle = format!("\"{key}\":");
    let start = json.find(&needle)? + needle.len();
    let rest = json[start..].trim_start();
    if rest.starts_with("true") {
        Some(true)
    } else if rest.starts_with("false") {
        Some(false)
    } else {
        None
    }
}

fn now_secs() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}

fn temporary_root_cmd(args: &[String]) -> i32 {
    let Some(sub) = args.first().map(|s| s.as_str()) else {
        return fail("temporary-root: missing subcommand");
    };
    match sub {
        "status" => {
            let Some(uid) = args.get(1).and_then(|s| s.parse::<i32>().ok()) else {
                return fail("temporary-root status: missing uid");
            };
            match query_policy(uid) {
                Some((2, 0)) => println!("permanent"),
                Some((2, 1)) => println!("boot"),
                Some((2, _)) => println!("timed"),
                _ => println!("none"),
            }
            0
        }
        "apply" => {
            // apply <callerUid> <mode> <expectedRoot> <profile-json>
            let Some(mode) = args.get(2).map(|s| s.as_str()) else {
                return fail("temporary-root apply: missing mode");
            };
            let Some(json) = args.get(4) else {
                return fail("temporary-root apply: missing profile");
            };
            let Some(uid) = json_int(json, "currentUid") else {
                return fail("temporary-root apply: profile has no currentUid");
            };
            let allow = json_bool(json, "allowSu").unwrap_or(false);
            let policy: i64 = if allow { 2 } else { 1 };
            let until: i64 = if !allow {
                0
            } else {
                match mode {
                    "keep" => query_policy(uid as i32).map(|(_, u)| u).unwrap_or(0),
                    "permanent" => 0,
                    "0" => 1,
                    n => match n.parse::<i64>() {
                        Ok(secs) => now_secs() + secs,
                        Err(_) => 0,
                    },
                }
            };
            if write_policy(uid as i32, policy, until) {
                println!("OK");
                0
            } else {
                fail(&format!(
                    "temporary-root apply: cannot write policy for uid {uid}"
                ))
            }
        }
        "revoke" => {
            let Some(uid) = args.get(1).and_then(|s| s.parse::<i32>().ok()) else {
                return fail("temporary-root revoke: missing uid");
            };
            if write_policy(uid, 1, 0) {
                println!("OK");
                0
            } else {
                fail(&format!(
                    "temporary-root revoke: cannot write policy for uid {uid}"
                ))
            }
        }
        _ => fail(&format!(
            "temporary-root {sub}: not supported on this backend"
        )),
    }
}

pub fn dispatch(argv: &[String]) -> Option<i32> {
    let cmd = argv.first()?;
    if !is_ksud_command(cmd) {
        return None;
    }
    let rest: Vec<String> = argv[1..].to_vec();
    let code = match cmd.as_str() {
        "module" => module_cmd(&rest),
        "feature" => feature_cmd(&rest),
        "boot-info" => boot_info_cmd(&rest),
        "debug" => debug_cmd(&rest),
        "temporary-root" => temporary_root_cmd(&rest),
        "install" | "soft-reboot" => 0,
        "boot-patch" => boot_patch_cmd(&rest),
        _ => fail(&format!("{cmd}: not supported on this backend")),
    };
    Some(code)
}
