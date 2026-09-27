package dev.busung.s25uroot.dfr.stage2

/**
 * Samsung's DMC vault, as much of it as the D2 fix needs.
 *
 * Galaxy firmware from around April 2026 locks Odin - download mode - on a phone with a lock screen set,
 * and the switch that decides it is a byte in a record the VaultKeeper service keeps under the name `DMC`.
 * Writing that byte is the counterpart upstream DFReroot added for this lockdown, and it is here for the
 * same reason it is there: a phone rooted through this flow is a phone somebody is about to flash with Odin,
 * and a rooted phone that cannot reach download mode is a support case rather than a fix.
 *
 * ## Why this is reflection, and why every call is wrapped
 *
 * `com.samsung.android.service.vaultkeeper.VaultKeeperManager` is a framework class with no SDK, so it is
 * reached by name. A device without it answers [Reading.Unsupported], which is a sentence the screen can
 * print rather than an exception it has to survive - and there is no other way to ask, because the class
 * either is or is not in this firmware.
 *
 * The helper runs as `android.uid.system`, and that is the only reason these calls are allowed at all. It
 * is also why this code is in this APK rather than in the app that owns the switch: an ordinary app on the
 * same phone cannot make them, so the app can ask for the fix and cannot perform it.
 *
 * ## The one rule that matters
 *
 * The blob is [BLOB_SIZE] bytes on the device this was measured from, and **nothing here writes a blob whose
 * shape it does not recognise**. A vault write is not a change that can be taken back, the layout is
 * Samsung's and undocumented, and the three fields below were confirmed on one chip. So [understood] gates
 * the write, the result is verified by re-reading, and a device that does not answer in that shape is
 * reported as unsupported instead of written to on a guess.
 *
 * ## What is not claimed here
 *
 * The other twenty-nine bytes are not interpreted and not preserved by name - the whole record is read,
 * one byte of it is set, and the record is written back unchanged otherwise. That is deliberate: anything
 * else would be this code inventing meaning for fields nobody has documented, in a store a wrong write
 * cannot be undone in.
 */
internal object DmcVault {

    /** The framework class, which is the only handle on the vault there is. */
    internal const val MANAGER_CLASS = "com.samsung.android.service.vaultkeeper.VaultKeeperManager"

    /** The vault the Odin flags live in, as `getInstance` takes it. */
    internal const val VAULT = "DMC"

    /** The record `read` and `write` address. One, on the one device this was measured from. */
    internal const val RECORD = 1

    /** The record's length, which is the whole of the shape check. */
    internal const val BLOB_SIZE = 32

    /** `lock`: zero means Odin is not locked. */
    internal const val INDEX_LOCK = 0

    /** `maint`: one means maintenance mode is armed, which allows Odin. */
    internal const val INDEX_MAINT = 1

    /** `at`: the flag a boot writes, and the only byte this code ever changes. */
    internal const val INDEX_AT = 2

    /**
     * Whether Odin may be reached, from the three readings upstream treats as deciding it.
     *
     * Three readings and an `or`, rather than the one flag this writes, because the lockdown can be lifted
     * three ways and a phone whose owner took one of the others does not need this fix to say so. Reading it
     * this way is also what keeps the screen honest on a device where the write is refused: `Odin allowed`
     * from `lock == 0` is still the truth about the phone.
     */
    internal fun odinAllowed(lock: Int, maint: Int, at: Int): Boolean =
        lock == 0 || maint == 1 || at == 1

    /**
     * Whether this is a record this code may write.
     *
     * Length is the only test the format allows. The three fields above are known and the rest are not, so a
     * shorter or longer record is a different firmware's layout rather than this one - and a different
     * layout is the one case where writing would be guessing at somebody else's store.
     */
    internal fun understood(blob: ByteArray?): Boolean = blob != null && blob.size == BLOB_SIZE

    /** Whether the flag is already set, which is what makes a boot's write a no-op rather than a write. */
    internal fun atFlagSet(blob: ByteArray): Boolean =
        blob.size == BLOB_SIZE && blob[INDEX_AT].toInt() == 1

    /** What a read found. */
    internal sealed interface Reading {
        /** No such class, no instance, a refused call, or a record of another shape. */
        data class Unsupported(val because: String) : Reading

        /** The three bytes the lockdown is decided by. */
        data class Available(val lock: Int, val maint: Int, val at: Int) : Reading
    }

    /** What a write did. */
    internal sealed interface Writing {
        /** [wrote] is false when the flag was already set, so there was nothing to write. */
        data class Done(val wrote: Boolean) : Writing

        data class Skipped(val because: String) : Writing

        data class Failed(val because: String) : Writing
    }

    /** One line for the screen and the log, because the screen and the log should not phrase it twice. */
    internal fun describe(reading: Reading): String = when (reading) {
        is Reading.Unsupported -> "unsupported (${reading.because})"
        is Reading.Available -> buildString {
            append("lock=").append(reading.lock)
            append(" maint=").append(reading.maint)
            append(" at=").append(reading.at)
            append(if (odinAllowed(reading.lock, reading.maint, reading.at)) " - Odin allowed" else " - Odin locked")
        }
    }

    /**
     * The vault's three bytes, or why they could not be had.
     *
     * The manager class, the instance and the `read` method are each a separate failure with a separate
     * sentence, because on this device they have separate causes: a non-Samsung build has no class, a build
     * whose vault service is not up has no instance, and a call that is refused throws. Collapsing them into
     * "unsupported" would leave a person with nothing to look at but a word that is true of all three.
     */
    internal fun read(): Reading = try {
        val manager = Class.forName(MANAGER_CLASS)
        val instance = manager.getMethod("getInstance", String::class.java).invoke(null, VAULT)
            ?: return Reading.Unsupported("no $VAULT instance")
        val blob = manager.getMethod("read", Int::class.javaPrimitiveType)
            .invoke(instance, RECORD) as? ByteArray
        // The shape is checked here rather than through [understood] alone, because the null check is what
        // lets the three reads below be plain indexed ones: a helper that only answered a Boolean would
        // leave this code asserting non-null over a value the compiler cannot see through.
        if (blob == null || blob.size != BLOB_SIZE) {
            return Reading.Unsupported("record is ${blob?.size ?: -1} bytes, not $BLOB_SIZE")
        }
        Reading.Available(
            lock = blob[INDEX_LOCK].toInt(),
            maint = blob[INDEX_MAINT].toInt(),
            at = blob[INDEX_AT].toInt(),
        )
    } catch (error: Throwable) {
        Reading.Unsupported(error.javaClass.simpleName + (error.message?.let { ": $it" } ?: ""))
    }

    /**
     * Sets the AT flag, and only that flag.
     *
     * The whole record is read, the one byte is set on the copy that was read, and that copy is written back
     * - so every other byte goes back exactly as it came, including the twenty-nine this code does not
     * understand. Then it is read again: a write that reports success and did not land is the failure this
     * fix cannot afford to report as a success, because the card it answers is only visible from download
     * mode, which is the one place the person who finds out cannot tell us about it.
     */
    internal fun writeAtFlag(): Writing = try {
        val manager = Class.forName(MANAGER_CLASS)
        val instance = manager.getMethod("getInstance", String::class.java).invoke(null, VAULT)
            ?: return Writing.Skipped("no $VAULT instance")
        val read = manager.getMethod("read", Int::class.javaPrimitiveType)
        val write = manager.getMethod("write", Int::class.javaPrimitiveType, ByteArray::class.java)

        val blob = read.invoke(instance, RECORD) as? ByteArray
        if (blob == null || !understood(blob)) {
            return Writing.Failed("record is ${blob?.size ?: -1} bytes, not $BLOB_SIZE, so nothing was written")
        }
        if (atFlagSet(blob)) return Writing.Done(wrote = false)

        blob[INDEX_AT] = 1
        val code = write.invoke(instance, RECORD, blob) as? Int ?: -1
        if (code != 0) return Writing.Failed("write returned $code")

        val check = read.invoke(instance, RECORD) as? ByteArray
        if (check == null || !atFlagSet(check)) return Writing.Failed("the flag did not read back")
        Writing.Done(wrote = true)
    } catch (error: Throwable) {
        Writing.Failed(error.javaClass.simpleName + (error.message?.let { ": $it" } ?: ""))
    }
}
