package dev.busung.s25uroot

import android.os.Build
import android.system.Os
import android.system.OsConstants

/**
 * The Android generation each GKI family belongs to.
 *
 * The pairing is the platform's own contract rather than a guess: `android12-5.10`, `android13-5.15`,
 * `android14-6.1`, `android15-6.6`, `android16-6.12`. It is here because a kernel release does not always
 * carry the `-android<N>-` token that states it (`6.6.98-android15-8-pd6ff1cd-abogkiS938USQSCCZF9-4k` does;
 * a GKI image built from a different tree may not), and the kernel version alone still identifies the family.
 */
private val GKI_GENERATIONS = mapOf(
    "5.10" to 12,
    "5.15" to 13,
    "6.1" to 14,
    "6.6" to 15,
    "6.12" to 16,
    "6.18" to 17,
)

/** `android(\d+)` in a release: the Android generation the kernel was built for. */
private val ANDROID_TOKEN = Regex("android(\\d+)")

/**
 * The KMI a kernel release names, or null when it cannot be read.
 *
 * `6.6.98-android15-8-pd6ff1cd-abogkiS938USQSCCZF9-4k` -> `android15-6.6`, which is the name the feed's
 * generic daemons list in `kmis` and the name `ksud` builds for itself.
 *
 * The `-android<N>-` token wins when it is present, because it is what the kernel says about itself and what
 * `ksud` reads; the table is the fallback for a release that omits it. The kernel part is always the leading
 * `major.minor`, since the patch level is not part of a KMI name.
 */
internal fun String.kmi(): String? {
    val family = takeWhile { it.isDigit() || it == '.' }
    val parts = family.split('.')
    if (parts.size < 2) return null
    val majorMinor = "${parts[0]}.${parts[1]}"
    val generation = ANDROID_TOKEN.find(this)?.groupValues?.get(1)?.toIntOrNull()
        ?: GKI_GENERATIONS[majorMinor]
        ?: return null
    return "android$generation-$majorMinor"
}

data class DeviceSnapshot(
    val manufacturer: String,
    val model: String,
    val device: String,
    val kernelRelease: String,
    val kernelVersionInfo: String,
    val machine: String,
    val buildId: String,
    val fingerprint: String,
    val androidRelease: String,
    val sdk: Int,
    val abi: String,
    val pageSize: Long,
) {
    val kernelVersion: String
        get() = kernelRelease.takeWhile { it.isDigit() || it == '.' }

    /**
     * The GKI KMI this kernel belongs to, such as `android15-6.6`, or null when it cannot be read.
     *
     * This is the name a KernelSU daemon asks for its own kernel module by - `format!("{kmi}_kernelsu.ko")`
     * against a KMI it works out the same way - so it is the one fact that decides whether a daemon carrying
     * several modules has one for this phone. The generic tier matches on it and refuses when it misses,
     * because the alternative is handing a phone a module for another kernel, which the loader refuses at
     * `finit_module` with nothing in the log to say why.
     */
    val kmi: String?
        get() = kernelRelease.kmi()

    val kernelVersionFull: String
        get() = listOf(kernelRelease, kernelVersionInfo, machine)
            .filter(String::isNotBlank)
            .joinToString(" ")

    companion object {
        fun current(): DeviceSnapshot {
            val uname = Os.uname()
            return DeviceSnapshot(
                manufacturer = Build.MANUFACTURER,
                model = Build.MODEL,
                device = Build.DEVICE,
                kernelRelease = uname.release,
                kernelVersionInfo = uname.version,
                machine = uname.machine,
                buildId = Build.DISPLAY,
                fingerprint = Build.FINGERPRINT,
                androidRelease = Build.VERSION.RELEASE,
                sdk = Build.VERSION.SDK_INT,
                abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
                pageSize = Os.sysconf(OsConstants._SC_PAGESIZE),
            )
        }
    }
}
