package net.horizonsend.ion.server.features.economy.Taxation

data class CreditTaxBucketSummary(
    var taxed: Int = 0,
    var skipped: Int = 0,
    var removed: Double = 0.0
) {
    fun recordTax(amount: Double) {
        taxed++
        removed += amount
    }

    fun recordSkip() {
        skipped++
    }
}

data class CreditTaxRunSummary(
    var totalTaxed: Int = 0,
    var totalSkipped: Int = 0,
    var totalRemoved: Double = 0.0
) {
    fun add(bucket: CreditTaxBucketSummary) {
        totalTaxed += bucket.taxed
        totalSkipped += bucket.skipped
        totalRemoved += bucket.removed
    }
}
