/*
 * App Monitor native crash handler (v2).
 *
 * Installs sigaction handlers for SIGSEGV, SIGABRT, SIGBUS, SIGFPE, SIGILL and SIGTRAP on an
 * alternate stack. On a crash it records the faulting pc, unwinds with _Unwind_Backtrace, resolves
 * each pc with dladdr to module path + offset (+ exported symbol when dladdr has one) and writes a
 * small text report with open/write/close from a preallocated buffer. Numbers are formatted by
 * hand (no printf). It then restores the previous handlers and re-raises, so the system tombstone
 * (debuggerd) and any other crash tool still run.
 *
 * Note: ART installs its own SIGSEGV handling through libsigchain, which intercepts sigaction();
 * ART's handlers (implicit null checks, stack overflow checks) always run first, so this handler
 * only sees real crashes.
 *
 * Caveat: dladdr and _Unwind_Backtrace are not formally async-signal-safe (POSIX makes no promise
 * for them). They are used by most Android crash reporters in practice; everything else here is.
 *
 * Report format, one record per line (parsed by AppMonitor.Core.nativeCrashBody):
 *   AMNC1
 *   sig 11
 *   name SIGSEGV
 *   code 1
 *   fault 0x0
 *   ts 1760000000000
 *   thread <name>
 *   ctx <JSON envelope set from Java>
 *   f <pc hex> <offset hex> <symbol or -> <module path or ?>
 *   m <base hex> <module path>
 *   end
 */

#include <jni.h>

#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <signal.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <time.h>
#include <ucontext.h>
#include <unistd.h>
#include <unwind.h>

#define AM_MAX_FRAMES 64
#define AM_MAX_MODULES 64
#define AM_PATH_MAX 512
#define AM_CTX_MAX 1024
#define AM_BUF_SIZE 4096
#define AM_ALT_STACK_SIZE (64 * 1024)

static const int am_signals[] = {SIGSEGV, SIGABRT, SIGBUS, SIGFPE, SIGILL, SIGTRAP};
#define AM_NSIG ((int)(sizeof(am_signals) / sizeof(am_signals[0])))

static struct sigaction am_old[AM_NSIG];
static volatile int am_installed;
static volatile sig_atomic_t am_handling;
static char am_path[AM_PATH_MAX];

/* Double buffered context: writers fill the inactive slot, then flip the index. */
static char am_ctx[2][AM_CTX_MAX];
static volatile int am_ctx_idx;
static pthread_mutex_t am_ctx_lock = PTHREAD_MUTEX_INITIALIZER;

/* Everything the handler needs is preallocated. */
static char am_buf[AM_BUF_SIZE];
static size_t am_len;
static int am_fd = -1;
static uintptr_t am_pcs[AM_MAX_FRAMES];
static uintptr_t am_mod_base[AM_MAX_MODULES];
static const char *am_mod_name[AM_MAX_MODULES];
static uintptr_t am_self_base;

/* ------------------------------------------------------------------------------------------ */
/* Async-signal-safe output                                                                   */
/* ------------------------------------------------------------------------------------------ */

static void am_flush(void) {
    size_t off = 0;
    while (am_fd >= 0 && off < am_len) {
        ssize_t n = write(am_fd, am_buf + off, am_len - off);
        if (n < 0) {
            if (errno == EINTR) continue;
            break;
        }
        off += (size_t)n;
    }
    am_len = 0;
}

static void am_putc(char c) {
    if (am_len >= AM_BUF_SIZE) am_flush();
    am_buf[am_len++] = c;
}

static void am_put(const char *s) {
    if (s == NULL) return;
    while (*s) am_putc(*s++);
}

/* A single whitespace-free token (paths and symbols): spaces and control chars become '_'. */
static void am_put_token(const char *s) {
    if (s == NULL || *s == '\0') {
        am_putc('?');
        return;
    }
    for (; *s; s++) {
        unsigned char c = (unsigned char)*s;
        am_putc((c <= ' ' || c == 0x7f) ? '_' : (char)c);
    }
}

/* Rest-of-line text: only newlines and control chars are replaced. */
static void am_put_line_text(const char *s) {
    if (s == NULL) return;
    for (; *s; s++) {
        unsigned char c = (unsigned char)*s;
        am_putc((c < ' ' || c == 0x7f) ? ' ' : (char)c);
    }
}

static void am_put_hex(uintptr_t v) {
    char tmp[2 * sizeof(uintptr_t)];
    int n = 0;
    static const char digits[] = "0123456789abcdef";
    am_put("0x");
    do {
        tmp[n++] = digits[v & 0xf];
        v >>= 4;
    } while (v != 0 && n < (int)sizeof(tmp));
    while (n > 0) am_putc(tmp[--n]);
}

static void am_put_dec(long long v) {
    char tmp[24];
    int n = 0;
    unsigned long long u;
    if (v < 0) {
        am_putc('-');
        u = (unsigned long long)(-(v + 1)) + 1u;
    } else {
        u = (unsigned long long)v;
    }
    do {
        tmp[n++] = (char)('0' + (u % 10u));
        u /= 10u;
    } while (u != 0 && n < (int)sizeof(tmp));
    while (n > 0) am_putc(tmp[--n]);
}

static const char *am_signal_name(int sig) {
    switch (sig) {
        case SIGSEGV: return "SIGSEGV";
        case SIGABRT: return "SIGABRT";
        case SIGBUS: return "SIGBUS";
        case SIGFPE: return "SIGFPE";
        case SIGILL: return "SIGILL";
        case SIGTRAP: return "SIGTRAP";
        default: return "SIGUNKNOWN";
    }
}

/* ------------------------------------------------------------------------------------------ */
/* Unwinding                                                                                  */
/* ------------------------------------------------------------------------------------------ */

struct am_unwind_state {
    uintptr_t *pcs;
    int count;
    int max;
};

static _Unwind_Reason_Code am_unwind_cb(struct _Unwind_Context *ctx, void *arg) {
    struct am_unwind_state *st = (struct am_unwind_state *)arg;
    uintptr_t pc = (uintptr_t)_Unwind_GetIP(ctx);
#if defined(__arm__)
    pc &= ~(uintptr_t)1; /* clear the Thumb bit */
#endif
    if (pc == 0) return _URC_NO_REASON;
    if (st->count >= st->max) return _URC_END_OF_STACK;
    st->pcs[st->count++] = pc;
    return _URC_NO_REASON;
}

static uintptr_t am_context_pc(void *ucv) {
    ucontext_t *uc = (ucontext_t *)ucv;
    if (uc == NULL) return 0;
#if defined(__aarch64__)
    return (uintptr_t)uc->uc_mcontext.pc;
#elif defined(__arm__)
    return (uintptr_t)uc->uc_mcontext.arm_pc;
#elif defined(__x86_64__)
    return (uintptr_t)uc->uc_mcontext.gregs[REG_RIP];
#elif defined(__i386__)
    return (uintptr_t)uc->uc_mcontext.gregs[REG_EIP];
#else
    return 0;
#endif
}

static uintptr_t am_context_lr(void *ucv) {
    ucontext_t *uc = (ucontext_t *)ucv;
    if (uc == NULL) return 0;
#if defined(__aarch64__)
    return (uintptr_t)uc->uc_mcontext.regs[30];
#elif defined(__arm__)
    return (uintptr_t)uc->uc_mcontext.arm_lr & ~(uintptr_t)1;
#else
    return 0;
#endif
}

static int am_module_count;

static void am_remember_module(uintptr_t base, const char *name) {
    int i;
    if (base == 0 || name == NULL) return;
    for (i = 0; i < am_module_count; i++) {
        if (am_mod_base[i] == base) return;
    }
    if (am_module_count < AM_MAX_MODULES) {
        am_mod_base[am_module_count] = base;
        am_mod_name[am_module_count] = name;
        am_module_count++;
    }
}

static void am_write_frame(uintptr_t pc) {
    Dl_info info;
    memset(&info, 0, sizeof(info));
    am_put("f ");
    am_put_hex(pc);
    am_putc(' ');
    if (dladdr((const void *)pc, &info) != 0 && info.dli_fname != NULL) {
        uintptr_t base = (uintptr_t)info.dli_fbase;
        am_put_hex(pc - base);
        am_putc(' ');
        if (info.dli_sname != NULL && info.dli_sname[0] != '\0') {
            am_put_token(info.dli_sname);
        } else {
            am_putc('-');
        }
        am_putc(' ');
        am_put_token(info.dli_fname);
        am_remember_module(base, info.dli_fname);
    } else {
        am_put("0x0 - ?");
    }
    am_putc('\n');
}

static int am_in_self(uintptr_t pc) {
    Dl_info info;
    memset(&info, 0, sizeof(info));
    if (am_self_base == 0) return 0;
    return dladdr((const void *)pc, &info) != 0 && (uintptr_t)info.dli_fbase == am_self_base;
}

/* ------------------------------------------------------------------------------------------ */
/* Report                                                                                     */
/* ------------------------------------------------------------------------------------------ */

static void am_write_report(int sig, siginfo_t *si, void *uc) {
    struct am_unwind_state st;
    struct timespec now;
    char thread_name[32];
    uintptr_t ctx_pc;
    int start = -1;
    int i;

    if (am_path[0] == '\0') return;
    am_fd = open(am_path, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    if (am_fd < 0) return;
    am_len = 0;
    am_module_count = 0;

    am_put("AMNC1\nsig ");
    am_put_dec(sig);
    am_put("\nname ");
    am_put(am_signal_name(sig));
    am_put("\ncode ");
    am_put_dec(si != NULL ? si->si_code : 0);
    am_put("\nfault ");
    am_put_hex(si != NULL ? (uintptr_t)si->si_addr : 0);
    if (clock_gettime(CLOCK_REALTIME, &now) == 0) {
        am_put("\nts ");
        am_put_dec((long long)now.tv_sec * 1000LL + (long long)(now.tv_nsec / 1000000L));
    }
    memset(thread_name, 0, sizeof(thread_name));
    if (prctl(PR_GET_NAME, (unsigned long)thread_name, 0, 0, 0) == 0 && thread_name[0] != '\0') {
        thread_name[sizeof(thread_name) - 1] = '\0';
        am_put("\nthread ");
        am_put_line_text(thread_name);
    }
    {
        const char *ctx = am_ctx[am_ctx_idx & 1];
        if (ctx[0] != '\0') {
            am_put("\nctx ");
            am_put_line_text(ctx);
        }
    }
    am_putc('\n');

    ctx_pc = am_context_pc(uc);
    st.pcs = am_pcs;
    st.count = 0;
    st.max = AM_MAX_FRAMES;
    _Unwind_Backtrace(am_unwind_cb, &st);

    /* The unwinder starts inside this handler; skip to the faulting frame when it is found. */
    if (ctx_pc != 0) {
        for (i = 0; i < st.count; i++) {
            if (am_pcs[i] == ctx_pc) {
                start = i;
                break;
            }
        }
    }
    if (start >= 0) {
        for (i = start; i < st.count; i++) am_write_frame(am_pcs[i]);
    } else {
        /* Unwinding did not cross the signal frame: report pc (and lr for leaf functions), then
         * whatever the unwinder found outside this library. */
        uintptr_t lr = am_context_lr(uc);
        if (ctx_pc != 0) am_write_frame(ctx_pc);
        if (lr != 0) am_write_frame(lr);
        for (i = 0; i < st.count; i++) {
            if (!am_in_self(am_pcs[i])) am_write_frame(am_pcs[i]);
        }
    }

    for (i = 0; i < am_module_count; i++) {
        am_put("m ");
        am_put_hex(am_mod_base[i]);
        am_putc(' ');
        am_put_token(am_mod_name[i]);
        am_putc('\n');
    }
    am_put("end\n");
    am_flush();
    close(am_fd);
    am_fd = -1;
}

static void am_restore_handlers(void) {
    int i;
    for (i = 0; i < AM_NSIG; i++) sigaction(am_signals[i], &am_old[i], NULL);
}

static void am_handler(int sig, siginfo_t *si, void *uc) {
    int saved_errno = errno;
    if (!am_handling) {
        am_handling = 1;
        am_write_report(sig, si, uc);
    }
    am_restore_handlers();
    /* Faults (SEGV/BUS/FPE/ILL/TRAP from the CPU) re-execute the instruction on return and hit the
     * previous handler. Signals sent by software (abort, kill, tgkill) must be raised again. */
    if (si == NULL || si->si_code <= 0 || sig == SIGABRT) {
        syscall(SYS_tgkill, getpid(), (pid_t)syscall(SYS_gettid), sig);
    }
    errno = saved_errno;
}

/* ------------------------------------------------------------------------------------------ */
/* JNI                                                                                        */
/* ------------------------------------------------------------------------------------------ */

static void am_copy(char *dst, size_t cap, const char *src) {
    size_t n = 0;
    if (src != NULL) {
        while (src[n] != '\0' && n + 1 < cap) {
            dst[n] = src[n];
            n++;
        }
    }
    dst[n] = '\0';
}

JNIEXPORT jboolean JNICALL
Java_com_housamkak_appmonitor_AppMonitorNative_install(JNIEnv *env, jclass cls, jstring path) {
    struct sigaction sa;
    stack_t current;
    Dl_info self;
    const char *p;
    int i;
    (void)cls;

    if (path == NULL) return JNI_FALSE;
    p = (*env)->GetStringUTFChars(env, path, NULL);
    if (p == NULL) return JNI_FALSE;
    am_copy(am_path, sizeof(am_path), p);
    (*env)->ReleaseStringUTFChars(env, path, p);

    if (am_installed) return JNI_TRUE;

    /* Bionic gives every pthread its own alternate stack; add one only if this thread has none. */
    memset(&current, 0, sizeof(current));
    if (sigaltstack(NULL, &current) == 0 && (current.ss_flags & SS_DISABLE) != 0) {
        stack_t alt;
        memset(&alt, 0, sizeof(alt));
        alt.ss_sp = malloc(AM_ALT_STACK_SIZE);
        alt.ss_size = AM_ALT_STACK_SIZE;
        alt.ss_flags = 0;
        if (alt.ss_sp != NULL && sigaltstack(&alt, NULL) != 0) {
            free(alt.ss_sp);
        }
    }

    memset(&self, 0, sizeof(self));
    if (dladdr((const void *)&am_handler, &self) != 0) am_self_base = (uintptr_t)self.dli_fbase;

    memset(&sa, 0, sizeof(sa));
    sigemptyset(&sa.sa_mask);
    sa.sa_sigaction = am_handler;
    sa.sa_flags = SA_SIGINFO | SA_ONSTACK;
    for (i = 0; i < AM_NSIG; i++) {
        if (sigaction(am_signals[i], &sa, &am_old[i]) != 0) {
            memset(&am_old[i], 0, sizeof(am_old[i]));
            am_old[i].sa_handler = SIG_DFL;
        }
    }
    am_installed = 1;
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_housamkak_appmonitor_AppMonitorNative_setContext(JNIEnv *env, jclass cls, jstring json) {
    const char *s;
    int next;
    (void)cls;
    if (json == NULL) return;
    s = (*env)->GetStringUTFChars(env, json, NULL);
    if (s == NULL) return;
    pthread_mutex_lock(&am_ctx_lock);
    next = (am_ctx_idx + 1) & 1;
    am_copy(am_ctx[next], AM_CTX_MAX, s);
    __sync_synchronize();
    am_ctx_idx = next;
    pthread_mutex_unlock(&am_ctx_lock);
    (*env)->ReleaseStringUTFChars(env, json, s);
}
