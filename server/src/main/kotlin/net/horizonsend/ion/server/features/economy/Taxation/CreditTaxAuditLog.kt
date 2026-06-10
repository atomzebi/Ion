package net.horizonsend.ion.server.features.economy.Taxation

import net.horizonsend.ion.server.IonServer
import java.io.File
import java.time.Instant
import java.util.Locale

class CreditTaxAuditLog private constructor(private val file: File) {
    companion object {
        fun create(
            runId: String,
            startedAt: Long,
            scheduledFor: Long,
            lateByMillis: Long,
            frequencyDescription: String,
            config: CreditTaxConfiguration
        ): CreditTaxAuditLog {
            val directory = IonServer.dataFolder
                .resolve("logs")
                .resolve("credit-tax")
                .apply { mkdirs() }

            val audit = CreditTaxAuditLog(directory.resolve("credit-tax-$runId.log"))

            audit.writeLine("status=STARTED")
            audit.writeLine("runId=$runId")
            audit.writeLine("startedAt=${Instant.ofEpochMilli(startedAt)}")
            audit.writeLine("scheduledFor=${Instant.ofEpochMilli(scheduledFor)}")
            audit.writeLine("lateByMillis=$lateByMillis")
            audit.writeLine("ratePercent=${config.taxRatePercent}")
            audit.writeLine("taxableBalanceThreshold=${config.taxableBalanceThreshold}")
            audit.writeLine("taxBazaarBuyOrderEscrowFromPlayerVaultFirst=${config.taxBazaarBuyOrderEscrowFromPlayerVaultFirst}")
            audit.writeLine("missedPeriodsStacked=false")
            audit.writeLine("taxRunsApplied=1")
            audit.writeLine("frequency=$frequencyDescription")
            audit.writeLine("")

            return audit
        }

        fun amount(value: Double): String = String.format(Locale.US, "%.2f", value)

        fun quote(value: Any?): String {
            val text = value?.toString() ?: "null"
            return "\"${text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ")}\""
        }
    }

    fun path(): String = file.path

    @Synchronized
    fun writeLine(line: String) {
        file.appendText(line + System.lineSeparator())
    }

    fun writeBucketSummary(bucket: String, summary: CreditTaxBucketSummary) {
        writeLine(
            "bucket=$bucket taxed=${summary.taxed} skipped=${summary.skipped} " +
                "removed=${amount(summary.removed)} status=COMPLETED"
        )
    }

    fun writeCompleted(completedAt: Long, durationMillis: Long, summary: CreditTaxRunSummary, nextRunAt: Long) {
        writeLine("")
        writeLine("status=COMPLETED")
        writeLine("completedAt=${Instant.ofEpochMilli(completedAt)}")
        writeLine("durationMillis=$durationMillis")
        writeLine("totalBalancesTaxed=${summary.totalTaxed}")
        writeLine("totalBalancesSkipped=${summary.totalSkipped}")
        writeLine("totalTaxRemoved=${amount(summary.totalRemoved)}")
        writeLine("nextRunAt=${Instant.ofEpochMilli(nextRunAt)}")
    }

    fun writeFailed(failedAt: Long, bucket: String, throwable: Throwable) {
        writeLine("")
        writeLine("status=FAILED")
        writeLine("failedAt=${Instant.ofEpochMilli(failedAt)}")
        writeLine("failedBucket=$bucket")
        writeLine("error=${quote(throwable.message ?: throwable.javaClass.name)}")
    }
}
