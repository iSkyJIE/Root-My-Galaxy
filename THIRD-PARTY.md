# Third-party code in this repository

Two projects are vendored into this repository, both of them for the Dirty Frag root path. Neither
carries a license, so nothing here is granted on the original authors' behalf this file exists so that
what came from where is written down rather than inferred from a diff, and so the next person to touch
these files knows which parts are theirs to change.

## DFReroot https://github.com/polygraphene/DFReroot

No `LICENSE`, no `NOTICE`, and no SPDX header in any of the files below: all rights reserved by default.
Taken because the mechanism is the point and the alternative was not having it.

| Here | There | State |
|---|---|---|
| `app/src/main/java/dev/busung/s25uroot/dfr/Abx.kt` | `installer/…/Abx.kt` | Close to verbatim |
| `app/src/main/java/dev/busung/s25uroot/dfr/PackagesXml.kt` | `installer/…/PackagesXml.kt` | Close to verbatim |
| `app/src/main/java/dev/busung/s25uroot/dfr/SigKey.kt` | `installer/…/SigKey.kt` | Verbatim |
| `app/src/main/java/dev/busung/s25uroot/dfr/SysKey.kt` | `installer/…/SysKey.kt` | Verbatim |
| `app/src/main/java/dev/busung/s25uroot/dfr/InjectMain.kt` | `installer/…/InjectMain.kt` | Package name and default key package changed |
| `dfr/src/main/jni/exp.c`, `stage1.S`, `elf_parser.c`, `include.inc`, `logging.h`, `splicehelper.c` | `app/src/main/jni/…` | Verbatim |
| `dfr/src/main/jni/dirtyfrag-android*.ko`, `splicehelper` | built by their `build.sh` | Bytes, unchanged |
| `dfr/src/main/jni/CMakeLists.txt` | `app/src/main/jni/CMakeLists.txt` | Verbatim |
| `dfr/src/main/java/dev/busung/s25uroot/dfr/stage2/StageHop.kt` | `app/…/StageHop.kt` | Comments rewritten, logic unchanged |
| `dfr/src/main/java/dev/busung/s25uroot/dfr/stage2/StageReceiver.kt` | `app/…/StageReceiver.kt` | Codes named, else unchanged |
| `dfr/src/main/java/dev/busung/s25uroot/dfr/stage2/KsudStage.kt` | `app/…/KsudStage.kt` | Destination and daemon sources changed |
| `dfr/src/main/java/dev/busung/s25uroot/dfr/stage2/Stage2Activity.kt` | `app/…/MainActivity.kt` | Rewritten in code rather than XML layouts |
| `dfr/src/main/java/dev/busung/s25uroot/dfr/stage2/DmcVault.kt` | `app/…/DmcVault.kt` | Rewritten: the reflection is theirs, the shape check and its reasons are ours |
| `dfr/src/main/java/dev/busung/s25uroot/dfr/stage2/DmcBootReceiver.kt` | `app/…/DmcBootReceiver.kt` | Rewritten: gated on this app's setting, where theirs writes unconditionally |
| `.DmcBootReceiver` in `dfr/src/main/AndroidManifest.xml` | the same entry in `app/src/main/AndroidManifest.xml` | Same receiver, declared in the helper instead |
| `dfr/src/main/java/dev/busung/s25uroot/dfr/stage2/DmcGate.kt` | | **Ours, and it has no counterpart upstream** |

**Ours, in the same flow:** `dfr/` as a Gradle module (their `app` module), `DfrInstall.kt`,
`DfrFlow.kt`, `DfrApk.kt`, `DfrUi.kt`, every test under `dfr` in both modules, and the decision of how
the flow is driven. Their two-APK split is forced by `sharedUserId="android.uid.system"` rather than
chosen see the module comment in `settings.gradle.kts`.

**The D2 fix is gated here and is not upstream.** Theirs writes the vault's flag at every boot for
everyone who installed it; ours is a setting in this app that starts off, because the write is a change
to a Samsung store whose layout was confirmed on one chip and a wrong write there cannot be undone.
That is also the whole reason `DmcGate.kt` exists: the switch is the app's, the write is the helper's
(the vault only answers to a system-uid process), and the point in the boot where the write has to
happen is a point where the app cannot run at all.

The kernel module's **source and build script** are vendored at [`dirtyfrag-lkm/`](dirtyfrag-lkm/) —
`dirtyfrag.c`, its `Makefile`, and upstream's `build.sh` taken from the same repository at 2.2.0. Until now
this project shipped the eight prebuilt modules without their recipe: nothing here could rebuild one, and a
KMI could not be added from this tree at all. The script builds each module inside the same DDK images the
payload repository already pulls (`ghcr.io/ylarod/ddk-min:<kmi>`), and applies the size diet upstream
documents `-Os` with unwind tables dropped, then `llvm-objcopy --strip-unneeded` and the `-R` removals that
take it from 13.4 KiB to about 7.8 KiB. That diet is not tidiness: the module is written through the exploit
page by page, so its size is a page count.

The eight modules the **helper** flow embeds are **verified as taken rather than merely similar**: the copies
in `dfr/src/main/jni/` are byte-identical to upstream's committed ones, KMI for KMI, by sha256.

The chain's own eight, in `app/src/main/cpp/dfroot/ko/`, are no longer taken from anyone - see the DFRoot
section below. They are built from a module of DFRoot's, so `dirtyfrag-lkm/` above is **not** the source of
the module the universal root loads: one directory name, two different modules, and the helper's flow still
depends on this one.

## DFReroot-S25U https://github.com/igorcv88/DFReroot-S25U

A fork of DFReroot (above) whose work is mostly *hardening* rather than features, and which found two defects in
`packages.xml` handling by running it on hardware. One of them was in this repository's own copy: the mode and
owner written back onto the file were read off the backup instead of the original. The other - a 0-byte backup
being kept as if it were usable - is fixed here too.

From the same project, vendored into [`dfr/src/main/jni/`](dfr/src/main/jni/): `dfr_verified_exec.c` and its
minimal `sha256.c`/`sha256.h`. The launcher opens a candidate file once, hashes **that descriptor**, rewinds it
and gives the same descriptor to `execveat(AT_EMPTY_PATH)` - so a rename or replacement after the open cannot
change the bytes that run. Hashing a pathname and exec'ing it later leaves exactly that window, and this
repository had it. Its `CMakeLists.txt` builds it as an executable; getting it onto the phone and into a stage
command is a separate step, because an executable target is not packaged into the APK the way a library's `.so`
is.

## DFRoot https://github.com/diabl0w/DFRoot

No `LICENSE`, no `NOTICE`, no SPDX header in any of the files below: all rights reserved by default. Taken
for the same reason as DFReroot above the mechanism is the point and it is the chain that roots a phone
from an **ordinary app**, with no system-uid helper, no `packages.xml` inject and no first temporary root.

**Taken at `40702b8`** ("Remove ksud copy requirements"), and updated from it to **`e47ea6e`** ("Major
refactor: Remove libc patching -> use insmod directly in libc++ Move most heavy lifting to custom LKM").
Neither was written down at the time, which is why identifying the pair took hashing the eight kernel
modules against upstream's history: their bytes are the one part of this port a diff cannot check by eye.
Both are recorded here now, and the two paragraphs below are the facts a later update should start from.

| Here | There | State |
|---|---|---|
| `app/src/main/cpp/dfroot/exp.c` | `app/src/main/jni/exp.c` | Verbatim except ours: the JNI entry is renamed for our class and takes this project's arguments, and the reporter is resolved from the object it was handed rather than by `FindClass` in `JNI_OnLoad` (see `reporter.h`) |
| `app/src/main/cpp/dfroot/{elf_parser.c,splicehelper.c,include.inc,aes256.h,hmac_sha256.h,splicehelper,reporter.h}` | `app/src/main/jni/…` | Verbatim |
| `app/src/main/cpp/dfroot/libcxx.S` | `app/src/main/jni/libcxx.S` | Verbatim except two things in its data: the third `insmod` argument is this project's (`package_name=<manager>`, a buffer the app fills, where theirs is `soft_reboot=1`), and the offset exported for it replaces `libcxx_soft_reboot_off` |
| *(deleted upstream at `e47ea6e`)* | `app/src/main/jni/libc.S` | **Gone, and not replaced.** That file was the libc patch; the chain `insmod`s the module now instead of patching libc to run it. Our copy went with it |
| *(deleted upstream at `e47ea6e`)* | `app/src/main/jni/logging.h` | Folded into `reporter.h`, which is where the reporting plumbing lives now |
| `app/src/main/cpp/dfroot/ko/dirtyfrag-android*.ko` | `dirtyfrag-lkm/`, built per KMI | **Built by our payload repository**, not taken. `dfroot-lkm/` there holds the module's source and its three divergences, and `.github/workflows/dfroot-lkm.yml` builds the eight images and refuses one that does not name this app's daemon |
| `app/src/main/cpp/dfroot/CMakeLists.txt` | `app/src/main/jni/CMakeLists.txt` | Build paths rewritten for a subdirectory and `libc.S` dropped from the source list; the two custom steps and the `.incbin` layout are theirs |
| *(no longer shipped)* | `app/src/main/assets/ksud` | **Their daemon is not in this repository.** The daemon this chain runs is built by our payload repository, one per flavour, and the module is told which manager to serve |
| `UniversalRoot.kt`, `UniversalRootRun.kt` | `ExploitRunner.java` | **Ours.** The `IpSecManager` driver is a rewrite of theirs, the same calls in the same order, and it carries upstream's later `a4abd4f` on top: which vendor library gets patched is a choice the app makes per device and passes down, rather than a constant |

**The daemon, and why it is one of ours.** Everywhere else in this project the daemon comes from the
payload, because a daemon is version-locked to the kernel module that loads it and three managers here have
their own builds. This chain needs a daemon with a different property: the module's command runs
`ksud late-load --package-name <manager>` with **no path to a module anywhere in it**, so the daemon has to
carry its own - which is what `ksud` built from KernelSU's userspace does, picking the module for the
running kernel's KMI out of its own asset directory (`format!("{kmi}_kernelsu.ko")`, the mechanism the
payload repository's `tools/generic_daemon.py` reads back out of a built binary).

**Two options of theirs are deliberately not used.** Upstream's fork adds `--ro-partitions` and
`--soft-reboot` to `late-load`, and the module at `e47ea6e` passes both. The daemons built for this project
take neither - a daemon handed an option it does not know exits at argument parsing, before it logs a line -
and the two behaviours are the app's own anyway: the read-only partition wall, and *Auto soft restart*,
which the app now performs itself after a universal run. That is why the module here carries our command
rather than theirs, and why its workflow checks for both flags.

## LSPromise https://github.com/LSPosed/LSPromise

Also no license. One file, and it is the JNI bridge whose package name cannot change: `exp.c` registers
`Java_org_lsposed_lspromise_DirtyFrag_*` natives, so the Java class has to keep that package.

| Here | There |
|---|---|
| `dfr/src/main/java/org/lsposed/lspromise/DirtyFrag.java` | `…/DirtyFrag.java` |

## The kernel module

`lkm/permissive/` in the payload repository is **not** vendored from either project: it is written here
from the kernel's own headers, because the module this one replaces hardcodes the byte it writes and we
derive the offset per kernel instead. Its provenance notes are in that directory's own README.

## If either project ever grants a license

Replace this file's first paragraph and the header on each ported file with that license's terms. Until
then, these files are the only ones in the repository that are not Apache-2.0 that grant nothing, and
they should stay named as such.
