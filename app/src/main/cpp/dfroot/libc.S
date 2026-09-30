    .arch armv8-a

    .include "include.inc"

    .global libc_start
    .global libc_data
    .global libc_len
    .global libc_first_inst_copy
    .data

libc_data:
executable_path:
    .asciz "/vendor/bin/modprobe"
lib_path:
    .asciz "/vendor/lib64/libbinderdebug.so"
mod_args:
    .asciz ""
ksud_path:
    // The daemon, as a path the *app* wrote. This is the whole trick of upstream's change: the shellcode no
    // longer copies anything - it bind-mounts this file over /system/bin/logcat and execs it - so the bytes
    // are placed by the app, in the app's own directory, which it can write with no root and no shell. The
    // chain only ever reads it.
    //
    // The package is this app's, and it is the one thing here that cannot be derived: the shellcode runs in
    // a different process and has no way to learn which app staged the file. `UniversalRootContractTest`
    // holds this string to the `applicationId` in app/build.gradle.kts, because a package rename would
    // otherwise break rooting in a way that looks like the exploit failing.
    // Device-protected, not `/data/data/...`, and this is a correctness fix rather than tidiness: `/data/data`
    // is credential-encrypted storage, which is not mounted until the user unlocks. A run started from a boot
    // broadcast can therefore look for this file while it does not exist - and a bind mount with no source
    // fails silently, because the shellcode writes no marker on that path and the parent only sees a timeout.
    .asciz "/data/user_de/0/dev.rushiranpise.rmgnext/ksud"
logcat_path:
    .asciz "/system/bin/logcat"
root_path:
    .asciz "/"
mark_modprobe:
    .asciz "/dev/dfm0"
mark_bind_ns:
    .asciz "/dev/dfm1"
mark_bind_ok:
    .asciz "/dev/dfm2"
mark_exec_fail:
    .asciz "/dev/dfm4"
mark_ksud_ok:
    .asciz "/dev/dfm3"
argv_prog:
    .asciz "logcat"
argv_late:
    .asciz "late-load"
argv_pkgname:
    .asciz "--package-name"
// The package the daemon is told to serve, as a **slot the app fills in** rather than a string compiled
// here. It is the one argument that differs per KernelSU: the daemon grants root to whatever package this
// names, so a run on a KernelSU-Next phone has to say `com.rifsxd.ksunext` and one on ReSukiSU has to say
// `com.resukisu.resukisu`. A compiled-in literal meant every flavour was handed KernelSU's name - which
// clap accepts, because to it the value is opaque, so the run looked fine while the daemon was told to
// serve a manager the phone may not even have.
//
// The offset is exported the same way `libc_soft_reboot_off` is, and for the same reason: the Java side
// already patches one byte of this blob at runtime, so patching a string into it is the mechanism that
// exists rather than a new one.
    .balign 4
    .global libc_package_off
libc_package_off:
    .int argv_ksu - libc_data
argv_ksu:
    .space 64

    .balign 4
    .global libc_soft_reboot_off
libc_soft_reboot_off:
    .int soft_reboot_flag - libc_data
soft_reboot_flag:
    .byte 0

    .align 4

libc_start:
    stp x0, x1, [sp, #-16]!
    stp x2, x3, [sp, #-16]!
    stp x4, x5, [sp, #-16]!
    stp x6, x7, [sp, #-16]!
    stp x8, x9, [sp, #-16]!
    stp x26, x27, [sp, #-16]!
    stp x28, x29, [sp, #-16]!
    str x30, [sp, #-16]!

    // x0 -> int argc
    ldr     w1, [x0]            // w1 = argc
    cbz     w1, matched      // argc == 0，直接开干

    // 假定 argv 按 8 字节对齐，位于 argc 后面
    // 即布局为: int argc; int pad; char *argv[argc];
    add     x2, x0, #8
    ldr     x3, [x2]            // x3 = argv[0]
    cbz     x3, return_now

    adr     x4, executable_path // x4 = expected path

strcmp_loop:
    ldrb    w5, [x3], #1
    ldrb    w6, [x4], #1
    cmp     w5, w6
    b.ne    return_now
    cbnz    w5, strcmp_loop     // 还没遇到 '\0'，继续比较

matched:
    // argv[0] == executable_path

    // open lib
    mov     x0, -100
    adr     x1, lib_path
    mov    x2, O_RDONLY
    mov     x8, #SYS_openat
    svc     0
    mov     x26, x0         // save lib fd

    // finit_module
    adr x1, mod_args
    mov x2, xzr
    mov x8, #SYS_finit_module
    svc 0

    // close lib fd
    mov     x0, x26
    mov     x8, #SYS_close
    svc     0

    create_mark mark_modprobe

    sub sp, sp, 80

    create_mark mark_bind_ns

    movl x0, (CLONE_NEWNS)
    mov x8, #SYS_unshare
    svc 0
    cbnz w0, myexit

    // mount(NULL, "/", NULL, MS_REC|MS_PRIVATE, NULL)
    mov x0, xzr
    adr x1, root_path
    mov x2, xzr
    movl x3, (MS_REC | MS_PRIVATE)
    mov x4, xzr
    mov x8, #SYS_mount
    svc 0
    cbnz w0, myexit

    create_mark mark_bind_ok

    // mount(ksud_path, logcat_path, NULL, MS_BIND, NULL)
    adr x0, ksud_path
    adr x1, logcat_path
    mov x2, xzr
    mov x3, MS_BIND
    mov x4, xzr
    mov x8, #SYS_mount
    svc 0
    cbnz w0, myexit

    // argv = [logcat, late-load, --package-name, me.weishu.kernelsu, NULL]
    //
    // Four arguments, and nothing after them. DFRoot's own argv ends `--ro-partitions [--soft-reboot]`, which
    // are the two options **their fork** adds to `late-load`; the daemon this build drives is upstream's,
    // built by our payload repository for this device's KMI, and it takes neither. A daemon handed an option
    // it does not know exits at argument parsing, before it logs a line, and the chain never sees its stderr
    // - so that mistake is silent, and the phone is left with a rooted kernel and no daemon.
    //
    // The two things those options did are the app's business instead, and it already does both: read-only
    // partitions, and the reboot when the setting asks for one.
    adr x0, argv_prog
    str x0, [sp]

    adr x0, argv_late
    str x0, [sp, 8]

    adr x0, argv_pkgname
    str x0, [sp, 16]

    adr x0, argv_ksu
    str x0, [sp, 24]

    str xzr, [sp, 32]
fork_ksud:
    movl x0, SIGCHLD
    mov x1, xzr
    mov x2, xzr
    mov x3, xzr
    mov x4, xzr
    mov x8, #SYS_clone
    svc 0
    str x0, [sp, 64]    // child: 0; parent: child pid
    cbnz w0, wait_ksud

    // child: execve ksud (bind-mounted over logcat)
    adr x0, logcat_path
    mov x1, sp
    mov x2, xzr
    mov x8, #SYS_execve
    svc 0

    mov x0, #1
    mov x8, #SYS_exit
    svc #0

wait_ksud:
    ldr  x0, [sp, 64]
    add  x1, sp, 72
    mov  x2, xzr
    mov  x3, xzr
    mov  x8, #SYS_wait4
    svc  0
    ldr  w0, [sp, 72]
    lsr  w0, w0, 8
    and  w0, w0, #0xff
    cbnz w0, ksud_failed
    create_mark mark_ksud_ok
    b    myexit
ksud_failed:
    create_mark mark_exec_fail

myexit:
    mov x0, #1
    mov x8, #SYS_exit
    svc #0

return_now:
    ldr x30, [sp], #16
    ldp x28, x29, [sp], #16
    ldp x26, x27, [sp], #16
    ldp x8, x9, [sp], #16
    ldp x6, x7, [sp], #16
    ldp x4, x5, [sp], #16
    ldp x2, x3, [sp], #16
    ldp x0, x1, [sp], #16
libc_first_inst_copy:
    stp x29, x30, [sp, #-0x30]!
    b end2
end2:

libc_end:
libc_len:
    .word libc_end - libc_data
