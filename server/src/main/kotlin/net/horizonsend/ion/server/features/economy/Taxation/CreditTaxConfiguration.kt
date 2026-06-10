package net.horizonsend.ion.server.features.economy.Taxation

import kotlinx.serialization.Serializable
import net.horizonsend.ion.server.configuration.util.DurationConfig
import java.util.concurrent.TimeUnit

@Serializable
data class CreditTaxConfiguration(
    val enabled: Boolean = true,
    val taxRatePercent: Double = 1.0,
    val frequency: DurationConfig = DurationConfig(TimeUnit.DAYS, 7),

    val taxPlayerVaultBalances: Boolean = true,
    val taxNations: Boolean = true,
    val taxSettlements: Boolean = true,
    val taxBazaarSellListingProceeds: Boolean = true,
    val taxBazaarBuyOrderEscrow: Boolean = true,
    val taxBazaarBuyOrderEscrowFromPlayerVaultFirst: Boolean = true,
    val taxRentalZonePrepaidBalances: Boolean = true,
    val taxBounties: Boolean = true,

    val auditLogEveryTaxedBalance: Boolean = true,
    val taxableBalanceThreshold: Double = 100.0
)
