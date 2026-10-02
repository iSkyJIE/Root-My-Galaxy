package dev.busung.s25uroot

import android.content.Context
import android.system.Os
import java.io.File
import java.io.FileOutputStream

/** Immutable q7q payload carried inside Root Galaxy XP for first-run offline use. */
internal object BundledPayload {
    const val PROFILE_ID = "q7q-F966USQU9BZDN"
    private const val ASSET_PREFIX = "asset://"
    private const val ASSET_ROOT = "payloads/$PROFILE_ID"
    private const val EXPLOIT_NAME = "cve-2026-43499-app.so"
    private const val KSUD_NAME = "ksud-s25u-kdp"

    val profile = TargetProfile(
        profileId = PROFILE_ID,
        displayName = "Galaxy Z Fold 7 | Kernel 6.6.98",
        models = setOf("SM-F966U", "SM-F966U1"),
        kernelVersions = setOf("6.6.98"),
        exploit = RemoteArtifact(
            url = "$ASSET_PREFIX$ASSET_ROOT/$EXPLOIT_NAME",
            size = 126_352,
            sha256 = "92f1959c4944fae2825f094715b9f89e4b06b5ae141b1f6a3f4cb59f1ab84904",
        ),
        kernelSu = RemoteArtifact(
            url = "$ASSET_PREFIX$ASSET_ROOT/$KSUD_NAME",
            size = 6_407_096,
            sha256 = "fa3edcc7d168637394877b30cb1f909d762dda788ec14051f4ae79edd6562d63",
        ),
        sourceLabel = "Root Galaxy XP built-in",
    )

    fun isAsset(artifact: RemoteArtifact): Boolean = artifact.url.startsWith(ASSET_PREFIX)

    /**
     * The built-in payload when it can actually run on this phone.
     *
     * This is deliberately side-effect free so Settings and the run-plan preview can show the
     * offline fallback without recording a payload as selected just because the screen was opened.
     */
    fun availableProfile(requestedProfileId: String? = null): TargetProfile? {
        if (requestedProfileId != null && requestedProfileId != PROFILE_ID) return null
        val snapshot = DeviceSnapshot.current()
        return profile.takeIf { it.matches(snapshot) }
    }

    fun isAvailable(context: Context, requestedProfileId: String? = null): Boolean =
        runCatching { profileFor(context, requestedProfileId) }.isSuccess

    fun profileFor(context: Context, requestedProfileId: String? = null): TargetProfile {
        require(requestedProfileId == null || requestedProfileId == PROFILE_ID) {
            "The bundled payload is not the selected target"
        }
        val snapshot = DeviceSnapshot.current()
        require(profile.matches(snapshot)) {
            "The bundled payload does not support ${snapshot.model} / ${snapshot.kernelVersion}"
        }
        rememberResolvedPayload(context, profile)
        return profile
    }

    fun load(context: Context, requestedProfileId: String? = null): VerifiedPayloads {
        val selected = profileFor(context, requestedProfileId)
        val directory = directory(context).apply { mkdirs() }
        val exploit = stageArtifact(context, selected.exploit, File(directory, EXPLOIT_NAME))
        val kernelSu = stageArtifact(context, selected.kernelSu, File(directory, KSUD_NAME))
        Os.chmod(exploit.absolutePath, 0b100100100)
        Os.chmod(kernelSu.absolutePath, 0b100100100)
        return VerifiedPayloads(selected, exploit, kernelSu, PayloadOrigin.Cached)
    }

    fun daemon(context: Context): File? = runCatching {
        val selected = profileFor(context)
        val directory = directory(context).apply { mkdirs() }
        stageArtifact(context, selected.kernelSu, File(directory, KSUD_NAME)).also {
            Os.chmod(it.absolutePath, 0b100100100)
        }
    }.getOrNull()

    fun stageArtifact(context: Context, artifact: RemoteArtifact, destination: File): File {
        require(isAsset(artifact)) { "Not a bundled payload artifact: ${artifact.url}" }
        destination.parentFile?.mkdirs()
        if (fileMatchesArtifact(destination, artifact)) {
            Os.chmod(destination.absolutePath, 0b100100100)
            return destination
        }
        val temporary = File(destination.parentFile, "${destination.name}.part")
        if (temporary.exists()) temporary.delete()
        val assetPath = artifact.url.removePrefix(ASSET_PREFIX)
        context.assets.open(assetPath).use { input ->
            FileOutputStream(temporary).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
        require(fileMatchesArtifact(temporary, artifact)) {
            "Bundled payload verification failed: $assetPath"
        }
        if (destination.exists()) destination.delete()
        require(temporary.renameTo(destination)) {
            "Unable to stage bundled payload: $assetPath"
        }
        Os.chmod(destination.absolutePath, 0b100100100)
        return destination
    }

    private fun directory(context: Context): File =
        File(context.filesDir, "payloads/bundled-$PROFILE_ID")
}
