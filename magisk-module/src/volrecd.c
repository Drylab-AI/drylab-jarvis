// volrecd - Volume-key daemon for the Jarvis Magisk module.
//
// What it does:
//   1. Finds the /dev/input/eventN node that reports KEY_VOLUMEUP.
//   2. Creates a uinput virtual device that mirrors that node's key bits.
//   3. Grabs the real node exclusively with EVIOCGRAB, so Android never sees
//      Volume Up or Volume Down. Every OTHER key (Power, ...) is forwarded to
//      Android through the uinput device, so those still work.
//   4. On a Volume Up *press* (value==1), debounced to 400 ms and ignoring
//      auto-repeat, it runs:
//        am start -n com.jarvis.voicerec/.ToggleActivity \
//                 -a com.jarvis.voicerec.TOGGLE --user 0
//      which toggles the audio recording in the app.
//   5. On a Volume Down *press* (same debounce), it runs:
//        am start -n com.jarvis.voicerec/.CamToggleActivity \
//                 -a com.jarvis.voicerec.CAM_TOGGLE --user 0
//      which toggles the front-camera live stream in the app.
//
// Kill switch: if /data/adb/volrec/disable exists, the daemon releases the
// node (ungrab) and forwards both volume keys, so volume works normally again.
//
// Safety: if the daemon dies, the kernel drops the EVIOCGRAB automatically,
// so the keys always come back.
//
// Build: NDK clang, target aarch64-linux-android34. See build.sh.

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <signal.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#define LOG_DIR "/data/adb/volrec"
#define LOG_PATH LOG_DIR "/volrecd.log"
#define DISABLE_PATH LOG_DIR "/disable"
#define LOG_MAX_BYTES (1024 * 1024)

#define DEBOUNCE_MS 400

// Bit helpers for the EVIOCGBIT key bitmap.
#define BITS_PER_LONG (sizeof(long) * 8)
#define NLONGS(x) (((x) + BITS_PER_LONG - 1) / BITS_PER_LONG)
#define TEST_BIT(bit, array) \
    (((array)[(bit) / BITS_PER_LONG] >> ((bit) % BITS_PER_LONG)) & 1)

static volatile sig_atomic_t g_stop = 0;

static void on_signal(int sig) {
    (void)sig;
    g_stop = 1;
}

static long long now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long long)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

static void log_msg(const char *fmt, ...) {
    mkdir(LOG_DIR, 0755);

    // Rotate if the log grew past the cap.
    struct stat st;
    if (stat(LOG_PATH, &st) == 0 && st.st_size > LOG_MAX_BYTES) {
        char old[256];
        snprintf(old, sizeof(old), "%s.old", LOG_PATH);
        rename(LOG_PATH, old);
    }

    FILE *f = fopen(LOG_PATH, "a");
    if (!f) return;

    time_t t = time(NULL);
    struct tm tmv;
    localtime_r(&t, &tmv);
    char ts[32];
    strftime(ts, sizeof(ts), "%Y-%m-%d %H:%M:%S", &tmv);
    fprintf(f, "%s ", ts);

    va_list ap;
    va_start(ap, fmt);
    vfprintf(f, fmt, ap);
    va_end(ap);
    fprintf(f, "\n");
    fclose(f);
}

// Open the physical-button input node that sends KEY_VOLUMEUP. Returns fd
// or -1, and writes the chosen node path into path_out.
//
// On the Pixel 8 Pro two nodes expose KEY_VOLUMEUP: "gpio_keys" (the real
// buttons: VolumeUp + VolumeDown + Power, no touch axes) and
// "goodix_fingerprint" (a big virtual keyset for fingerprint gestures).
// Grabbing the fingerprint node would swallow those gestures and still miss
// the real button. So we SCORE every candidate and keep the best:
//   + must have KEY_VOLUMEUP
//   + big bonus for also having KEY_VOLUMEDOWN and KEY_POWER (button cluster)
//   + bonus for being named like the gpio button device
//   - reject nodes that carry touch/absolute axes (touchscreen) or FF (haptics)
static int score_node(int fd, const char *name) {
    unsigned long keybits[NLONGS(KEY_MAX)];
    memset(keybits, 0, sizeof(keybits));
    if (ioctl(fd, EVIOCGBIT(EV_KEY, sizeof(keybits)), keybits) < 0) return -1;
    if (!TEST_BIT(KEY_VOLUMEUP, keybits)) return -1;

    // Reject anything with absolute axes (touchscreen/joystick): a button
    // node has none.
    unsigned long absbits[NLONGS(ABS_MAX)];
    memset(absbits, 0, sizeof(absbits));
    if (ioctl(fd, EVIOCGBIT(EV_ABS, sizeof(absbits)), absbits) >= 0) {
        for (size_t i = 0; i < NLONGS(ABS_MAX); i++) {
            if (absbits[i]) return -1;
        }
    }

    int score = 1;
    if (TEST_BIT(KEY_VOLUMEDOWN, keybits)) score += 5;
    if (TEST_BIT(KEY_POWER, keybits)) score += 5;
    // Fingerprint node is full of navigation/camera keys; a pure button node
    // is not. Penalise nodes that also carry these virtual keys.
    if (TEST_BIT(KEY_CAMERA, keybits)) score -= 4;
    if (TEST_BIT(KEY_BACK, keybits)) score -= 2;
    if (TEST_BIT(KEY_HOME, keybits)) score -= 2;
    if (name && (strstr(name, "gpio") || strstr(name, "pmic") ||
                 strstr(name, "key"))) {
        score += 3;
    }
    if (name && strstr(name, "fingerprint")) score -= 6;
    return score;
}

static int find_volup_node(char *path_out, size_t path_len) {
    DIR *d = opendir("/dev/input");
    if (!d) {
        log_msg("ERROR opendir /dev/input: %s", strerror(errno));
        return -1;
    }

    struct dirent *de;
    int best_fd = -1;
    int best_score = 0;  // require a positive score to accept
    char best_path[300] = {0};
    char best_name[256] = {0};

    while ((de = readdir(d)) != NULL) {
        if (strncmp(de->d_name, "event", 5) != 0) continue;

        char path[300];
        snprintf(path, sizeof(path), "/dev/input/%s", de->d_name);
        int fd = open(path, O_RDWR);
        if (fd < 0) continue;

        char devname[256] = "unknown";
        ioctl(fd, EVIOCGNAME(sizeof(devname)), devname);
        int s = score_node(fd, devname);
        if (s > best_score) {
            // New winner: close the previous best and keep this one open.
            if (best_fd >= 0) close(best_fd);
            best_fd = fd;
            best_score = s;
            snprintf(best_path, sizeof(best_path), "%s", path);
            snprintf(best_name, sizeof(best_name), "%s", devname);
        } else {
            close(fd);
        }
    }
    closedir(d);

    if (best_fd >= 0) {
        log_msg("selected %s (%s) score=%d for KEY_VOLUMEUP",
                best_path, best_name, best_score);
        snprintf(path_out, path_len, "%s", best_path);
    } else {
        log_msg("ERROR no suitable KEY_VOLUMEUP button node found");
    }
    return best_fd;
}

// Create a uinput device that can emit every key the real node can, so we can
// replay non-VolumeUp events back to Android.
static int make_uinput(int src_fd) {
    int ufd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (ufd < 0) {
        log_msg("ERROR open /dev/uinput: %s", strerror(errno));
        return -1;
    }

    // Enable EV_KEY and copy every key bit from the source node.
    ioctl(ufd, UI_SET_EVBIT, EV_KEY);
    unsigned long keybits[NLONGS(KEY_MAX)];
    memset(keybits, 0, sizeof(keybits));
    ioctl(src_fd, EVIOCGBIT(EV_KEY, sizeof(keybits)), keybits);
    for (int code = 0; code < KEY_MAX; code++) {
        if (TEST_BIT(code, keybits)) ioctl(ufd, UI_SET_KEYBIT, code);
    }

    struct uinput_setup usetup;
    memset(&usetup, 0, sizeof(usetup));
    usetup.id.bustype = BUS_VIRTUAL;
    usetup.id.vendor = 0x18d1;   // Google
    usetup.id.product = 0x5245;  // "RE"
    snprintf(usetup.name, sizeof(usetup.name), "volrecd-passthrough");

    if (ioctl(ufd, UI_DEV_SETUP, &usetup) < 0 || ioctl(ufd, UI_DEV_CREATE) < 0) {
        log_msg("ERROR uinput setup: %s", strerror(errno));
        close(ufd);
        return -1;
    }
    log_msg("uinput passthrough device created");
    return ufd;
}

static void emit(int ufd, const struct input_event *ev) {
    // Replay the event verbatim onto uinput. We keep SYN frames intact.
    write(ufd, ev, sizeof(*ev));
}

static int disabled(void) {
    return access(DISABLE_PATH, F_OK) == 0;
}

// Fork+exec `am start -n <component> -a <action> --user 0` without blocking
// the event loop. We double-fork so we never create zombies and the loop keeps
// forwarding keys.
static void trigger(const char *component, const char *action) {
    pid_t p = fork();
    if (p == 0) {
        setsid();
        pid_t p2 = fork();
        if (p2 == 0) {
            execl("/system/bin/am", "am", "start",
                  "-n", component,
                  "-a", action,
                  "--user", "0",
                  (char *)NULL);
            _exit(127);
        }
        _exit(0);
    } else if (p > 0) {
        // Reap the first child immediately; the grandchild is re-parented.
        int st;
        waitpid(p, &st, 0);
    }
}

int main(void) {
    signal(SIGTERM, on_signal);
    signal(SIGINT, on_signal);
    signal(SIGCHLD, SIG_DFL);

    mkdir(LOG_DIR, 0755);
    log_msg("volrecd starting");

    char node_path[300] = {0};
    int src_fd = find_volup_node(node_path, sizeof(node_path));
    if (src_fd < 0) {
        log_msg("FATAL: no node with KEY_VOLUMEUP found");
        return 1;
    }

    int ufd = make_uinput(src_fd);
    if (ufd < 0) {
        close(src_fd);
        return 1;
    }

    int grabbed = 0;
    long long last_toggle = 0;      // Volume Up (audio recorder)
    long long last_cam_toggle = 0;  // Volume Down (front-camera stream)

    while (!g_stop) {
        // Honour the kill switch: ungrab + forward both volume keys when
        // disabled.
        int want_grab = !disabled();
        if (want_grab && !grabbed) {
            if (ioctl(src_fd, EVIOCGRAB, 1) == 0) {
                grabbed = 1;
                log_msg("grabbed %s (Volume Up captured)", node_path);
            }
        } else if (!want_grab && grabbed) {
            ioctl(src_fd, EVIOCGRAB, 0);
            grabbed = 0;
            log_msg("released grab (kill switch on)");
        }

        struct input_event ev;
        ssize_t n = read(src_fd, &ev, sizeof(ev));
        if (n != (ssize_t)sizeof(ev)) {
            if (errno == EINTR) continue;
            if (n < 0) {
                log_msg("read error: %s; re-finding node", strerror(errno));
                if (grabbed) ioctl(src_fd, EVIOCGRAB, 0);
                close(src_fd);
                sleep(1);
                src_fd = find_volup_node(node_path, sizeof(node_path));
                if (src_fd < 0) {
                    log_msg("FATAL: node gone");
                    break;
                }
                grabbed = 0;
                continue;
            }
            continue;
        }

        // When not grabbed (kill switch), we are not consuming anything; the
        // real node still delivers to Android, so just skip our logic.
        if (!grabbed) continue;

        if (ev.type == EV_KEY && ev.code == KEY_VOLUMEUP) {
            // value: 1 = press, 0 = release, 2 = auto-repeat.
            if (ev.value == 1) {
                long long t = now_ms();
                if (t - last_toggle >= DEBOUNCE_MS) {
                    last_toggle = t;
                    log_msg("Volume Up press -> toggle");
                    trigger("com.jarvis.voicerec/.ToggleActivity",
                            "com.jarvis.voicerec.TOGGLE");
                }
            }
            // Swallow all Volume Up events (press/release/repeat): do NOT emit.
            continue;
        }

        if (ev.type == EV_KEY && ev.code == KEY_VOLUMEDOWN) {
            if (ev.value == 1) {
                long long t = now_ms();
                if (t - last_cam_toggle >= DEBOUNCE_MS) {
                    last_cam_toggle = t;
                    log_msg("Volume Down press -> cam toggle");
                    trigger("com.jarvis.voicerec/.CamToggleActivity",
                            "com.jarvis.voicerec.CAM_TOGGLE");
                }
            }
            // Swallow all Volume Down events too: do NOT emit.
            continue;
        }

        // Everything else (Power, SYN frames) goes back to Android so those
        // keys behave normally.
        emit(ufd, &ev);
    }

    log_msg("volrecd stopping");
    if (grabbed) ioctl(src_fd, EVIOCGRAB, 0);
    ioctl(ufd, UI_DEV_DESTROY);
    close(ufd);
    close(src_fd);
    return 0;
}
