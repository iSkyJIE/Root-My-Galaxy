# Third-party code in this repository

Two projects are vendored into this repository, both of them for the Dirty Frag root path. Neither
carries a license, so nothing here is granted on the original authors' behalf — this file exists so that
what came from where is written down rather than inferred from a diff, and so the next person to touch
these files knows which parts are theirs to change.

## DFReroot — https://github.com/polygraphene/DFReroot

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
| `dfr/src/main/java/dev/busung/s25uroot/dfr/stage2/DmcGate.kt` | — | **Ours, and it has no counterpart upstream** |

**Ours, in the same flow:** `dfr/` as a Gradle module (their `app` module), `DfrInstall.kt`,
`DfrFlow.kt`, `DfrApk.kt`, `DfrUi.kt`, every test under `dfr` in both modules, and the decision of how
the flow is driven. Their two-APK split is forced by `sharedUserId="android.uid.system"` rather than
chosen — see the module comment in `settings.gradle.kts`.

**The D2 fix is gated here and is not upstream.** Theirs writes the vault's flag at every boot for
everyone who installed it; ours is a setting in this app that starts off, because the write is a change
to a Samsung store whose layout was confirmed on one chip and a wrong write there cannot be undone.
That is also the whole reason `DmcGate.kt` exists: the switch is the app's, the write is the helper's
(the vault only answers to a system-uid process), and the point in the boot where the write has to
happen is a point where the app cannot run at all.

## DFRoot — https://github.com/diabl0w/DFRoot

No `LICENSE`, no `NOTICE`, no SPDX header in any of the files below: all rights reserved by default. Taken
for the same reason as DFReroot above — the mechanism is the point — and it is the chain that roots a phone
from an **ordinary app**, with no system-uid helper, no `packages.xml` inject and no first temporary root.

| Here | There | State |
|---|---|---|
| `app/src/main/cpp/dfroot/exp.c` | `app/src/main/jni/exp.c` | Verbatim except two things: the JNI entry is renamed for our class, and `JNI_OnLoad` is replaced by a reporter resolved from the object it was handed |
| `app/src/main/cpp/dfroot/{libc.S,libcxx.S,elf_parser.c,splicehelper.c,include.inc,logging.h,aes256.h,hmac_sha256.h,splicehelper}` | `app/src/main/jni/…` | Verbatim |
| `app/src/main/cpp/dfroot/ko/dirtyfrag-android*.ko` | `app/src/main/jni/ko/…` | Bytes, unchanged |
| `app/src/main/cpp/dfroot/CMakeLists.txt` | `app/src/main/jni/CMakeLists.txt` | Build paths rewritten for a subdirectory; the two custom steps and the `.incbin` layout are theirs |
| *(no longer shipped)* | `app/src/main/assets/ksud` | **Their daemon is not in this repository any more.** It was, and it was replaced by one our payload repository builds for the device's own kernel — see the note below |
| `UniversalRoot.kt`, `UniversalRootRun.kt`, `UniversalRootUi.kt` | — | **Ours.** The `IpSecManager` driver is a rewrite of their `MainActivity`/`BootReceiver`: the same calls in the same order, but written here rather than ported, which is worth knowing when it misbehaves |

**Why the daemon is bundled, against this project's own preference.** Everywhere else, the daemon comes
from the payload, because a daemon is version-locked to the kernel module that loads it and three managers
here have their own builds. This chain is the exception, and it was measured rather than assumed: handed
the payload's daemon — either flavour — it starts and dies in silence, leaving no module and no log line,
while their `ksud` in the same chain on the same boot logs a complete late-load and roots the phone.

The reason is what the two daemons are built for. Theirs carries its kernel module **inside itself**, which
is what this invocation asks for: the argv is `late-load --package-name me.weishu.kernelsu --stage-from
/data/system/ksud --ro-partitions`, with no path to a module anywhere in it. Our payload's daemons are built
for the regular flow, where the app stages files around them first.

## LSPromise — https://github.com/LSPosed/LSPromise

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
