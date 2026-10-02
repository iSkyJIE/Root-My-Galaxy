package dev.busung.s25uroot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The KMI a kernel release names, which is the fact the generic tier is matched on.
 *
 * The value matters more than it looks: `ksud` picks its kernel module out of its own asset directory by
 * asking for `<kmi>_kernelsu.ko`, worked out the same way from the same kernel, so a KMI this app got wrong
 * is either a module the loader refuses at `finit_module` or a refusal of a payload that would have worked.
 * Every release string here is one this app has seen from a real phone or one of its own build documents.
 */
class KmiTest {

    @Test
    fun `the generation comes off the release, and the kernel off its major and minor`() {
        // The maintainer's own device: the token is what the kernel says about itself, and it is what ksud
        // reads, so it wins over anything this app could infer.
        assertEquals(
            "android15-6.6",
            "6.6.98-android15-8-pd6ff1cd-abogkiS938USQSCCZF9-4k".kmi(),
        )
        assertEquals("android12-5.10", "5.10.237-android12-9-31117096".kmi())
        assertEquals("android14-6.1", "6.1.157-android14-11 SMP preempt mod_unload aarch64".kmi())
        assertEquals("android13-5.15", "5.15.189-android13-8-32000000-abcd".kmi())
    }

    @Test
    fun `the patch level is not part of the name`() {
        // A KMI names a family - the DDK publishes one image per family and never per release - so the two
        // five-fifteen builds this repository carries are both `android13-5.15`.
        assertEquals("android13-5.15", "5.15.153-android13-9abcdef".kmi())
        assertEquals("android13-5.15", "5.15.189-android13-8-fedcba".kmi())
    }

    @Test
    fun `a release without the token falls back to the platform's family table`() {
        // Not every kernel prints `-android<N>-`. The pairing of kernel family to Android generation is the
        // platform's own contract, so it is the fallback rather than a guess.
        assertEquals("android15-6.6", "6.6.98-4k-g46a034eca005-dirty".kmi())
        assertEquals("android14-6.1", "6.1.145 SMP preempt aarch64".kmi())
        assertEquals("android12-5.10", "5.10.226-dirty SMP preempt aarch64".kmi())
    }

    @Test
    fun `a release that names neither is not read as a KMI`() {
        assertNull("nothing to read", "".kmi())
        assertNull("no version at all", "garbage".kmi())
        assertNull("a lone major number", "6-android15-x".kmi())
        // A family no generic daemon is built for reads as a name that matches nothing, which is the right
        // answer: the run refuses with the KMI printed rather than loading a module for another kernel.
        assertEquals("android9-4.19", "4.19.100-android9-8-x".kmi())
    }

    @Test
    fun `a snapshot reads it off its own release`() {
        val snapshot = DeviceSnapshot(
            manufacturer = "samsung",
            model = "SM-S938U1",
            device = "pa3q",
            kernelRelease = "6.6.98-android15-8-pd6ff1cd-abogkiS938USQSCCZF9-4k",
            kernelVersionInfo = "#1 SMP PREEMPT",
            machine = "aarch64",
            buildId = "BP3A.251005.003",
            fingerprint = "samsung/pa3q/pa3q:16/BP3A/1:user/release-keys",
            androidRelease = "16",
            sdk = 36,
            abi = "arm64-v8a",
            pageSize = 4096,
        )
        assertEquals("android15-6.6", snapshot.kmi)
        // The two are different facts and both are still read: the version is what a device entry matches on,
        // the KMI is what decides whether a generic daemon carries this kernel's module.
        assertEquals("6.6.98", snapshot.kernelVersion)
    }
}
