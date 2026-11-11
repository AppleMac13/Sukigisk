#include "magisk_bridge.h"

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cctype>
#include <cstdint>

#include <dlfcn.h>
#include <fcntl.h>
#include <limits.h>
#include <poll.h>
#include <signal.h>
#include <time.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

#include "ksu.h"
#include "logging.h"

// ---------------------------------------------------------------------------
// Backend state, collected once through a single root shell.
// ---------------------------------------------------------------------------
static bool g_probed = false;
static bool g_usable = false;
static int g_version = 0;          // Magisk version code, e.g. 27000
static int g_su_count = 0;         // rows in policies with policy=1
static char g_version_str[64] = {};

// ---------------------------------------------------------------------------
// Locate the Magisk binary shipped next to libkernelsu.so in the APK.
// ---------------------------------------------------------------------------
static bool mb_binary(char *out, size_t n) {
    static char cached[PATH_MAX + 1];
    if (cached[0] != '\0') {
        snprintf(out, n, "%s", cached);
        return true;
    }

    Dl_info info;
    if (dladdr((void *) &mb_binary, &info) == 0 || info.dli_fname == nullptr ||
        info.dli_fname[0] == '\0') {
        return false;
    }

    const char *slash = strrchr(info.dli_fname, '/');
    if (slash == nullptr) {
        return false;
    }

    snprintf(cached, sizeof(cached), "%.*s/libmagisk.so",
             static_cast<int>(slash - info.dli_fname), info.dli_fname);
    if (access(cached, X_OK) != 0) {
        cached[0] = '\0';
        return false;
    }

    snprintf(out, n, "%s", cached);
    return true;
}

// ---------------------------------------------------------------------------
// Run a script as root and capture its stdout. The manager process has no
// privilege of its own; /data/adb is 0700 root, so su is the only way in.
// Returns the child exit status, or -1 on spawn/timeout/failure.
// ---------------------------------------------------------------------------
static int su_exec(const char *script, char *out, size_t n) {
    int fds[2];
    if (pipe(fds) != 0) {
        return -1;
    }

    pid_t pid = fork();
    if (pid < 0) {
        close(fds[0]);
        close(fds[1]);
        return -1;
    }

    if (pid == 0) {
        dup2(fds[1], STDOUT_FILENO);
        close(fds[0]);
        close(fds[1]);
        int devnull = open("/dev/null", O_WRONLY);
        if (devnull >= 0) {
            dup2(devnull, STDERR_FILENO);
            close(devnull);
        }
        execlp("su", "su", "-c", script, (char *) nullptr);
        _exit(127);
    }

    close(fds[1]);

    size_t got = 0;
    const int timeout_ms = 8000;
    while (got + 1 < n) {
        struct pollfd pfd = {fds[0], POLLIN, 0};
        int pr = poll(&pfd, 1, timeout_ms);
        if (pr <= 0) {
            kill(pid, SIGKILL);
            break;
        }
        ssize_t r = read(fds[0], out + got, n - 1 - got);
        if (r <= 0) {
            break;
        }
        got += static_cast<size_t>(r);
    }
    out[got] = '\0';
    close(fds[0]);

    int status = 0;
    waitpid(pid, &status, 0);
    if (!WIFEXITED(status)) {
        return -1;
    }
    return WEXITSTATUS(status);
}

// Magisk's own versionCode is major * 1000 + minor * 100 + patch.
// Real device prints "30.7:MAGISK:R", so the tag suffix must be ignored.
static int first_version_code(const char *s) {
    while (*s != '\0' && !isdigit(static_cast<unsigned char>(*s))) {
        s++;
    }
    long major = 0;
    while (isdigit(static_cast<unsigned char>(*s))) {
        major = major * 10 + (*s - '0');
        s++;
    }
    long minor = 0;
    long patch = 0;
    if (*s == '.') {
        s++;
        while (isdigit(static_cast<unsigned char>(*s))) {
            minor = minor * 10 + (*s - '0');
            s++;
        }
        if (*s == '.') {
            s++;
            while (isdigit(static_cast<unsigned char>(*s))) {
                patch = patch * 10 + (*s - '0');
                s++;
            }
        }
    }
    if (major <= 0) {
        return 0;
    }
    if (major < 1000) {
        return static_cast<int>(major * 1000 + minor * 100 + patch);
    }
    return static_cast<int>(major);
}

// Last run of decimal digits in the string (sqlite prints N or "count(*)=N").
static int last_int_in(const char *s) {
    const char *end = s + strlen(s);
    while (end > s && !isdigit(static_cast<unsigned char>(*(end - 1)))) {
        end--;
    }
    const char *start = end;
    while (start > s && isdigit(static_cast<unsigned char>(*(start - 1)))) {
        start--;
    }
    if (start == end) {
        return 0;
    }
    return static_cast<int>(strtol(start, nullptr, 10));
}

// Magisk is a multicall binary: it dispatches on argv[0], and a file named
// "libmagisk.so" is rejected with "applet not found". So the packaged backend
// has to be materialized once under a root-owned dir with the name "magisk"
// before it can be executed. cmp keeps that copy in sync across app updates.
static bool magisk_var(char *out, size_t n) {
    char bin[PATH_MAX + 1] = {};
    const bool has_bin = mb_binary(bin, sizeof(bin));
    if (has_bin) {
        snprintf(out, n,
                 "MBR=/data/adb/.mb_bin/magisk; "
                 "if [ ! -x \"$MBR\" ] || ! cmp -s '%s' \"$MBR\"; then "
                 "mkdir -p /data/adb/.mb_bin && cp -f '%s' \"$MBR\" && chmod 755 \"$MBR\"; fi; "
                 "M=\"$MBR\"; [ -x \"$M\" ] || M='%s'",
                 bin, bin, bin);
    } else {
        snprintf(out, n, "M=magisk");
    }
    return has_bin;
}

static void mb_probe() {
    if (g_probed) {
        return;
    }
    g_probed = true;

    char prelude[PATH_MAX + 64] = {};
    magisk_var(prelude, sizeof(prelude));

    char script[2600];
    snprintf(script, sizeof(script),
             "%s; command -v \"$M\" >/dev/null 2>&1 || exit 127; "
             "\"$M\" -v; echo '--SU--'; "
             "\"$M\" --sqlite 'SELECT count(*) FROM policies WHERE policy=2'",
             prelude);

    char out[2048] = {};
    int rc = su_exec(script, out, sizeof(out));
    if (rc != 0 || out[0] == '\0') {
        LOGD("magisk bridge: probe failed, rc=%d", rc);
        return;
    }

    int version = first_version_code(out);
    if (version <= 0) {
        LOGD("magisk bridge: unparsable version output");
        return;
    }

    g_version = version;
    snprintf(g_version_str, sizeof(g_version_str), "v%d", version);

    const char *sep = strstr(out, "--SU--");
    g_su_count = sep != nullptr ? last_int_in(sep) : 0;
    g_usable = true;

    LOGD("magisk bridge: ready, version=%d, su_count=%d", g_version, g_su_count);
}

// policy in Magisk's database: 1 = root granted, 0 = denied.
// The whole table is pulled once and kept in memory: the superuser page asks
// for one profile per installed package, and forking su per row would make
// that page take minutes.
#define MB_MAX_POLICY 512

static int g_pol_uid[MB_MAX_POLICY];
static int g_pol_policy[MB_MAX_POLICY];
static int g_pol_n = 0;
static bool g_pol_loaded = false;
static bool g_pol_ok = false;
// Policies can also be written by another process (the ksud-compatible
// `temporary-root` path runs in its own process), so this snapshot has to be
// invalidated from the outside. The writer bumps a millisecond stamp inside
// this app's private dir; comparing it on every call is a plain file read and
// costs nothing, unlike re-querying the table.
#define MB_POL_STAMP "/data/user/0/com.sukisu.ultra/files/.mb_pol_stamp"
static char g_pol_stamp[40] = {};

static void mb_read_stamp(char *buf, size_t n) {
    buf[0] = '\0';
    FILE *f = fopen(MB_POL_STAMP, "re");
    if (f == nullptr) {
        return;
    }
    size_t r = fread(buf, 1, n - 1, f);
    buf[r] = '\0';
    fclose(f);
}

static void mb_load_policies() {
    char stamp[40];
    mb_read_stamp(stamp, sizeof(stamp));
    if (g_pol_loaded && strcmp(stamp, g_pol_stamp) == 0) {
        return;
    }
    g_pol_loaded = true;
    snprintf(g_pol_stamp, sizeof(g_pol_stamp), "%s", stamp);
    g_pol_n = 0;
    g_pol_ok = false;

    mb_probe();
    if (!g_usable) {
        return;
    }

    char prelude[PATH_MAX + 64] = {};
    magisk_var(prelude, sizeof(prelude));

    char script[1800];
    snprintf(script, sizeof(script), "%s; \"$M\" --sqlite 'SELECT uid, policy FROM policies'",
             prelude);

    char out[4096] = {};
    if (su_exec(script, out, sizeof(out)) != 0 || out[0] == '\0') {
        return;
    }

    g_pol_ok = true;

    const char *line = out;
    while (*line != '\0' && g_pol_n < MB_MAX_POLICY) {
        const char *eol = strchr(line, '\n');
        size_t len = eol != nullptr ? static_cast<size_t>(eol - line) : strlen(line);
        if (len > 0 && len < 255) {
            char buf[256];
            memcpy(buf, line, len);
            buf[len] = '\0';
            int uid = -1;
            int policy = 0;
            // Magisk 30.x prints "uid=10230|policy=2"; older builds may print
            // a bare "10230|2" and sqlite3 shell style "10230 2".
            const char *pu = strstr(buf, "uid=");
            const char *pp = strstr(buf, "policy=");
            if (pu != nullptr && pp != nullptr) {
                uid = static_cast<int>(strtol(pu + 4, nullptr, 10));
                policy = static_cast<int>(strtol(pp + 7, nullptr, 10));
            } else if (sscanf(buf, "%d|%d", &uid, &policy) != 2 &&
                       sscanf(buf, "%d %d", &uid, &policy) != 2 &&
                       sscanf(buf, "%d", &uid) != 1) {
                uid = -1;
            }
            if (uid >= 0) {
                g_pol_uid[g_pol_n] = uid;
                g_pol_policy[g_pol_n] = policy;
                g_pol_n++;
            }
        }
        if (eol == nullptr) {
            break;
        }
        line = eol + 1;
    }

    LOGD("magisk bridge: loaded %d policy rows", g_pol_n);
}

static bool mb_query_policy(int uid, bool *allow) {
    mb_load_policies();
    if (!g_pol_ok) {
        return false;
    }
    for (int i = 0; i < g_pol_n; i++) {
        if (g_pol_uid[i] == uid) {
            *allow = g_pol_policy[i] == 2;
            return true;
        }
    }
    return false;
}

static bool mb_set_policy(int uid, bool allow) {
    mb_probe();
    if (!g_usable) {
        return false;
    }

    char prelude[PATH_MAX + 64] = {};
    magisk_var(prelude, sizeof(prelude));

    const int policy = allow ? 2 : 1;

    char script[2000];
    snprintf(script, sizeof(script),
             "%s; "
             "\"$M\" --sqlite 'UPDATE policies SET policy=%d WHERE uid=%d' >/dev/null 2>&1; "
             "\"$M\" --sqlite 'SELECT policy FROM policies WHERE uid=%d' | grep -q '%d' || "
             "\"$M\" --sqlite 'INSERT OR REPLACE INTO policies(uid,policy,until,logging,notification) "
             "VALUES(%d,%d,0,1,1)' >/dev/null 2>&1; "
             "\"$M\" --sqlite 'SELECT policy FROM policies WHERE uid=%d' | grep -q '%d' && echo OK || echo NOTOK",
             prelude, policy, uid, uid, policy, uid, policy, uid, policy);

    char out[256] = {};
    if (su_exec(script, out, sizeof(out)) != 0) {
        return false;
    }
    // Force the cached policy table to be rebuilt on the next read.
    g_pol_loaded = false;
    return strstr(out, "OK") != nullptr;
}

// ---------------------------------------------------------------------------
// ioctl dispatch for the no-driver case.
// ---------------------------------------------------------------------------
int mb_ctl(unsigned long op, void *arg) {
    if (op == KSU_IOCTL_GET_INFO) {
        mb_probe();
        if (!g_usable) {
            return -1;
        }
        auto *cmd = static_cast<ksu_get_info_cmd *>(arg);
        if (cmd == nullptr) {
            return -1;
        }
        cmd->version = static_cast<uint32_t>(g_version);
        // Magisk has no kernel driver; it is the manager's own backend, and
        // reporting the MANAGER flag is what keeps the UI out of preview mode.
        cmd->flags = KSU_GET_INFO_FLAG_MANAGER;
        cmd->features = 0;
        cmd->uapi_version = KERNEL_SU_UAPI_VERSION;
        return 0;
    }

    if (op == KSU_IOCTL_GET_INFO_LEGACY) {
        mb_probe();
        if (!g_usable) {
            return -1;
        }
        auto *cmd = static_cast<ksu_get_info_legacy_cmd *>(arg);
        if (cmd == nullptr) {
            return -1;
        }
        cmd->version = static_cast<uint32_t>(g_version);
        cmd->flags = KSU_GET_INFO_FLAG_MANAGER;
        cmd->features = 0;
        return 0;
    }

    if (op == KSU_IOCTL_GET_FULL_VERSION) {
        mb_probe();
        if (!g_usable) {
            return -1;
        }
        auto *cmd = static_cast<ksu_get_full_version_cmd *>(arg);
        if (cmd == nullptr) {
            return -1;
        }
        snprintf(cmd->version_full, sizeof(cmd->version_full), "%s", g_version_str);
        return 0;
    }

    if (op == KSU_IOCTL_HOOK_TYPE) {
        mb_probe();
        if (!g_usable) {
            return -1;
        }
        auto *cmd = static_cast<ksu_hook_type_cmd *>(arg);
        if (cmd == nullptr) {
            return -1;
        }
        snprintf(cmd->hook_type, sizeof(cmd->hook_type), "%s", "magisk");
        return 0;
    }

    if (op == KSU_IOCTL_CHECK_SAFEMODE) {
        mb_probe();
        if (!g_usable) {
            return -1;
        }
        auto *cmd = static_cast<ksu_check_safemode_cmd *>(arg);
        if (cmd == nullptr) {
            return -1;
        }
        cmd->in_safe_mode = 0;
        return 0;
    }

    if (op == KSU_IOCTL_NEW_GET_ALLOW_LIST) {
        mb_probe();
        if (!g_usable) {
            return -1;
        }
        auto *cmd = static_cast<ksu_new_get_allow_list_cmd *>(arg);
        if (cmd == nullptr) {
            return -1;
        }
        // The uid array is not part of this contract; the home page only asks
        // for the total, which is the number of granted rows in magisk.db.
        cmd->count = 0;
        cmd->total_count = g_su_count > 0xFFFF ? 0xFFFF : static_cast<uint16_t>(g_su_count);
        return 0;
    }

    if (op == KSU_IOCTL_UID_SHOULD_UMOUNT) {
        mb_probe();
        if (!g_usable) {
            return -1;
        }
        auto *cmd = static_cast<ksu_uid_should_umount_cmd *>(arg);
        if (cmd == nullptr) {
            return -1;
        }
        // Magisk mounts modules globally; there is no per-app umount policy.
        cmd->should_umount = 0;
        return 0;
    }

    if (op == KSU_IOCTL_GET_FEATURE) {
        auto *cmd = static_cast<ksu_get_feature_cmd *>(arg);
        if (cmd == nullptr) {
            return -1;
        }
        // Every KernelSU feature bit is absent; callers treat supported == 0
        // as "this backend cannot do it" and fall back to their defaults.
        cmd->value = 0;
        cmd->supported = 0;
        return 0;
    }

    if (op == KSU_IOCTL_SET_FEATURE) {
        return -1;
    }

    if (op == KSU_IOCTL_GET_APP_PROFILE) {
        mb_probe();
        if (!g_usable) {
            return -1;
        }
        auto *cmd = static_cast<ksu_get_app_profile_cmd *>(arg);
        if (cmd == nullptr) {
            return -1;
        }
        bool allow = false;
        mb_query_policy(static_cast<int>(cmd->profile.curr_uid), &allow);
        // Magisk has no per-app profile templates. Leaving the config zeroed
        // makes the manager treat every untouched app as a "custom" profile, so
        // both root and non-root configs always read as "use default".
        cmd->profile.allow_su = allow ? 1 : 0;
        cmd->profile.rp_config.use_default = 1;
        cmd->profile.nrp_config.use_default = 1;
        if (allow) {
            cmd->profile.rp_config.profile.uid = 0;
            cmd->profile.rp_config.profile.gid = 0;
            cmd->profile.rp_config.profile.namespaces = 0;
            snprintf(cmd->profile.rp_config.profile.selinux_domain,
                     sizeof(cmd->profile.rp_config.profile.selinux_domain), "%s",
                     "u:r:magisk:s0");
        }
        return 0;
    }

    if (op == KSU_IOCTL_SET_APP_PROFILE) {
        auto *cmd = static_cast<ksu_set_app_profile_cmd *>(arg);
        if (cmd == nullptr) {
            return -1;
        }
        return mb_set_policy(static_cast<int>(cmd->profile.curr_uid),
                             cmd->profile.allow_su != 0) ? 0 : -1;
    }

    // Everything else (supercall passthrough, extensions, sulog, sepolicy,
    // mark/umount lists) has no Magisk equivalent.
    return -1;
}
