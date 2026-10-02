# Third-party code in this repository

Two projects are vendored into this repository, both of them for the Dirty Frag root path. Neither
carries a license, so nothing here is granted on the original authors' behalf this file exists so that
what came from where is written down rather than inferred from a diff, and so the next person to touch
these files knows which parts are theirs to change.

## DFReroot https://github.com/polygraphene/DFReroot

No `LICENSE`, no `NOTICE`, and no SPDX header in any of the files below: all rights reserved by default.
Taken because the mechanism is the point and the alternative was not having it.

**Taken at `274b0ef`** ("Change target lib files"), and updated from it to **`9edc769`** ("Version
2.2.0"), which is the revision every file below was checked against. Both are written down because this
copy was assembled across a week of upstream's releases rather than taken whole, so a diff against today's
upstream no longer says where it began: `exp.c` is now byte-identical to `9edc769` and was to `274b0ef`
before it, and the difference between the two is the four JNI entry points upstream deleted in between.
The eight modules are the one part that cannot answer the question - their bytes are identical at both
revisions - so they date nothing on their own.

| Here | There | State |
|---|---|---|
| `app/src/main/java/dev/busung/s25uroot/dfr/Abx.kt` | `installer/…/Abx.kt` | Close to verbatim |
| `app/src/main/java/dev/busung/s25uroot/dfr/PackagesXml.kt` | `installer/…/PackagesXml.kt` | Close to verbatim |
| `app/src/main/java/dev/busung/s25uroot/dfr/SigKey.kt` | `installer/…/SigKey.kt` | Verbatim |
| `app/src/main/java/dev/busung/s25uroot/dfr/SysKey.kt` | `installer/…/SysKey.kt` | Verbatim |
| `app/src/main/java/dev/busung/s25uroot/dfr/InjectMain.kt` | `installer/…/InjectMain.kt` | Package name and default key package changed |
| `dfr/src/main/jni/{exp.c,elf_parser.c,include.inc,logging.h,splicehelper.c}` | `app/src/main/jni/…` | Verbatim, and byte-identical to `9edc769` by sha256 |
| `dfr/src/main/jni/stage1.S` | `app/src/main/jni/stage1.S` | **Not verbatim, and the file to read before any update of this copy.** Its compiled-in data is this project's: the vendor library it patches, the daemon path it execs (`/data/system/rmgnext-ksud`, where upstream's is `/data/system/dfreroot-ksud`), and the `late-load` command line, which is this project's daemon's and takes no `--stage-from` |
| `dfr/src/main/jni/dirtyfrag-android*.ko` | `app/src/main/jni/…` | Bytes, unchanged: identical at `274b0ef` and at `9edc769` |
| `dfr/src/main/jni/splicehelper` | built by `build-splice.sh` | Upstream does not commit this binary, so there is nothing to compare it against: it is the file that recipe produces, and this repository's copy is the only one either project has |
| `dfr/src/main/jni/CMakeLists.txt` | `app/src/main/jni/CMakeLists.txt` | Verbatim except one added target: `dfr_verified_exec`, from DFReroot-S25U |
| `dfr/src/main/java/dev/busung/s25uroot/dfr/stage2/StageHop.kt` | `app/…/StageHop.kt` | Comments rewritten, logic unchanged |
| `dfr/src/main/java/dev/busung/s25uroot/dfr/stage2/StageReceiver.kt` | `app/…/StageReceiver.kt` | Codes named, and the controller is offered to the service as upstream's is; the broadcast is kept here as the fallback where upstream sends only through the bind |
| `dfr/src/main/java/dev/busung/s25uroot/dfr/stage2/KsudStage.kt` | `app/…/KsudStage.kt` | Destination and daemon sources changed |
| `dfr/src/main/java/dev/busung/s25uroot/dfr/stage2/Stage2Activity.kt` | `app/…/MainActivity.kt` | Rewritten in code rather than XML layouts |
| `dfr/src/main/java/dev/busung/s25uroot/dfr/stage2/DmcVault.kt` | `app/…/DmcVault.kt` | Rewritten: the reflection is theirs, the shape check and its reasons are ours |
| `dfr/src/main/java/dev/busung/s25uroot/dfr/stage2/DmcBootReceiver.kt` | `app/…/DmcBootReceiver.kt` | Rewritten: gated on this app's setting, where theirs writes unconditionally |
| `.DmcBootReceiver` in `dfr/src/main/AndroidManifest.xml` | the same entry in `app/src/main/AndroidManifest.xml` | Same receiver, declared in the helper instead |
| `dfr/src/main/java/dev/busung/s25uroot/dfr/stage2/DmcGate.kt` | | **Ours, and it has no counterpart upstream** |

**Of the twelve upstream commits between `274b0ef` and `9edc769`, exactly one had code this copy did not
have, and it has now been applied: `a9a81bd` ("Remove unused codes").** It deleted four JNI entry points -
`patchMod`, `patchLibc`, `patchCxx`, `createOrphanProcess` - from `exp.c` and `DirtyFrag.java`, the four
transaction codes that called them in `StageReceiver`, and two helpers nothing had used for a release
(`Abx.probeInt`/`probeBytesHex`, `PackagesXml.findInstalledKey`). The four transactions were a per-step way
to drive the same kernel writes `runAll` makes in one call, and no caller existed on either side.

Of the other eleven, four are upstream's own version bumps, one is a README line and one is `D2_Error.md`
- there is no code in this repository to change for any of them. The remaining five were ported as they
landed rather than in one pass, which is why the copy is a week's worth of upstream's releases: the
`packages.xml` permissions read from the original file rather than the backup (`f9120a6`), the D2 vault
write (`d5ff10c`) and its switch (`1031e82`), root at boot (`906c144`), and the controller handed back
through a bound service (`e6caf5b`).

**Ours, in the same flow:** `dfr/` as a Gradle module (their `app` module), `DfrInstall.kt`,
`DfrFlow.kt`, `DfrApk.kt`, `DfrUi.kt`, every test under `dfr` in both modules, and the decision of how
the flow is driven. Their two-APK split is forced by `sharedUserId="android.uid.system"` rather than
chosen see the module comment in `settings.gradle.kts`.

**The D2 fix is gated here, and upstream gates it too now - not the same way.** When this was ported
theirs wrote the vault's flag at every boot for everyone who had installed it; `1031e82` (v2.1.0) added a
switch of their own, kept in a `/data/system/dfreroot.xml` their helper owns and written by a checkbox in
its screen. This project's switch is the **app's**, offered where the rest of the flow is configured, and
the helper is told what it decided through its own device-protected storage - because the write has to
happen at `LOCKED_BOOT_COMPLETED`, when the app cannot run at all and a credential-encrypted preference
cannot be read. Off is the default in both, for the same reason: the write is a change to a Samsung store
whose layout was confirmed on one chip, and a wrong write there cannot be undone. `DmcGate.kt` exists for
the reading half of that and has no counterpart upstream.

**Root at boot is upstream's feature and this project's own answer to it.** `906c144` (v2.2.0) has their
helper start its own run from `LOCKED_BOOT_COMPLETED`, guarded by a flag file
(`/data/system/dfreroot-running`) and by the boot id they keep in `dfreroot.xml`. Here the decision is
the app's - `AutoRootBootReceiver`, `DfrBoot`, `AutoRootSupport` - because the app is the half that can
see whether the manager is already live and tell the user why a boot did nothing, and the helper only
refuses a launch it cannot attribute to root or the `shell` user ([`Autorun.kt`](dfr/src/main/java/dev/busung/s25uroot/dfr/stage2/Autorun.kt)).
Neither `AutoRoot.kt` nor `AutoRootReceiver.kt` is vendored.

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
