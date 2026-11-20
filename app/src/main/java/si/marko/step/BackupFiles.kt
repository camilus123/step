package si.marko.step

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import si.marko.step.core.BackupFormat
import si.marko.step.core.BackupFormatException
import si.marko.step.core.ImportSummary
import si.marko.step.data.StepRepository
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Reads and writes backup files at the places the user picked in the system file picker.
 * The picker hands out access to exactly that one file, so no storage permission is needed.
 */
class BackupFiles(private val context: Context, private val repository: StepRepository) {

    /** @return the number of hours written. */
    suspend fun exportJson(target: Uri): Int = withContext(Dispatchers.IO) {
        val data = repository.exportData()
        val json = BackupFormat.toJson(
            data = data,
            exportedAt = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS)),
            appVersion = BuildConfig.VERSION_NAME,
        )
        write(target, json)
        data.hours.size
    }

    /** @return the number of hours written. */
    suspend fun exportCsv(target: Uri): Int = withContext(Dispatchers.IO) {
        val data = repository.exportData()
        write(target, BackupFormat.toCsv(data))
        data.hours.size
    }

    /** @throws BackupFormatException when the file is not a usable backup; nothing is changed then. */
    suspend fun importJson(source: Uri): ImportSummary = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(source)?.use { stream ->
            // A backup of ten years is a few megabytes. Anything far beyond that is not one.
            val collected = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                collected.write(buffer, 0, read)
                if (collected.size() > MAX_IMPORT_BYTES) throw BackupFormatException("The file is too large to be a Step backup.")
            }
            collected.toString(Charsets.UTF_8.name())
        } ?: throw BackupFormatException("The file could not be opened.")
        repository.importData(BackupFormat.fromJson(text))
    }

    private fun write(target: Uri, text: String) {
        // "wt": truncate, in case the picker handed us an existing, longer file.
        val stream = context.contentResolver.openOutputStream(target, "wt")
            ?: throw java.io.IOException("The file could not be opened for writing.")
        stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }

    private companion object {
        const val MAX_IMPORT_BYTES = 50 * 1024 * 1024
    }
}
