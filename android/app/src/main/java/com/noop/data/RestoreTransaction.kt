package com.noop.data

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** Both directories are beside the database so publication and installation are single renames.
 * Only a fully validated stage can become pending. Once pending, recovery always rolls forward.
 * The caller must keep Room closed until recovery finishes. */
internal class RestoreTransaction(val live: File) {
    val stage = File(live.parentFile, "${live.name}.restore-preparing")
    private val pending = File(live.parentFile, "${live.name}.restore-pending")
    val database get() = File(stage, "database")
    val settings get() = File(stage, "settings.json")
    val isPending get() = pending.isDirectory
    val databaseInstalled get() = isPending && !File(pending, database.name).exists() && live.isFile

    fun prepare() {
        check(!isPending) { "A previous restore is waiting to finish." }
        discardStage()
        if (!stage.mkdirs()) throw IOException("Could not create restore staging directory.")
    }

    fun discardStage() {
        if (stage.exists() && !stage.deleteRecursively()) throw IOException("Could not clean up restore staging.")
    }

    fun publish(preservePrevious: Boolean = false) {
        check(!isPending && database.isFile)
        if (preservePrevious) Files.createDirectory(File(stage, "previous").toPath())
        Files.move(stage.toPath(), pending.toPath(), ATOMIC_MOVE)
    }

    fun recover(applySettings: (File) -> Unit) {
        if (!isPending) return
        val replacement = File(pending, database.name)
        val previous = File(pending, "previous")
        if (replacement.exists()) {
            for (suffix in listOf("-wal", "-shm", "-journal", "")) {
                val old = File(live.path + suffix)
                if (previous.exists() && old.exists()) {
                    // Legacy rollback recovery preserves the interrupted library for diagnosis.
                    Files.move(old.toPath(), File(previous, old.name).toPath(), ATOMIC_MOVE)
                } else if (suffix.isNotEmpty()) {
                    Files.deleteIfExists(old.toPath())
                }
            }
            Files.move(replacement.toPath(), live.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        }
        if (previous.exists()) {
            val quarantine = File(live.parentFile, "interrupted-restore-${java.util.UUID.randomUUID()}")
            Files.move(previous.toPath(), quarantine.toPath(), ATOMIC_MOVE)
        }
        if (!live.isFile) throw IOException("The pending restore has no installed database.")
        val prefs = File(pending, settings.name)
        if (prefs.exists()) {
            applySettings(prefs)
            Files.delete(prefs.toPath())
        }
        Files.delete(pending.toPath())
    }
}
