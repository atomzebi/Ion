package net.horizonsend.ion.server.features.economy.Taxation

import net.horizonsend.ion.common.database.schema.economy.BazaarItem
import net.horizonsend.ion.common.database.schema.economy.BazaarOrder
import net.horizonsend.ion.common.database.schema.economy.StationRentalZone
import net.horizonsend.ion.common.database.schema.misc.SLPlayer
import net.horizonsend.ion.common.database.schema.nations.Nation
import net.horizonsend.ion.common.database.schema.nations.Settlement
import net.horizonsend.ion.server.IonServer
import net.horizonsend.ion.server.configuration.ConfigurationFiles
import net.horizonsend.ion.server.core.IonServerComponent
import net.horizonsend.ion.server.miscellaneous.utils.VAULT_ECO
import org.bukkit.Bukkit
import org.bukkit.scheduler.BukkitTask
import org.litote.kmongo.and
import org.litote.kmongo.eq
import org.litote.kmongo.gte
import org.litote.kmongo.inc
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.floor

object CreditTaxes : IonServerComponent(runAfterTick = true) {
    private const val CHECK_INTERVAL_TICKS: Long = 20L * 60L * 15L

    private var checkTask: BukkitTask? = null
    private val taxRunning = AtomicBoolean(false)

    override fun onEnable() {
        Bukkit.getScheduler().runTaskAsynchronously(IonServer, Runnable {
            checkAndMaybeRun(logStartupState = true)
        })

        checkTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
            IonServer,
            Runnable { checkAndMaybeRun(logStartupState = false) },
            CHECK_INTERVAL_TICKS,
            CHECK_INTERVAL_TICKS
        )
    }

    override fun onDisable() {
        checkTask?.cancel()
        checkTask = null
    }

    private fun checkAndMaybeRun(logStartupState: Boolean) {
        val config = ConfigurationFiles.creditTaxConfiguration()

        if (!config.enabled) {
            if (logStartupState) IonServer.slF4JLogger.info("[CreditTaxes] Credit taxation is disabled in creditTax.json.")
            return
        }

        val validationError = validateConfig(config)
        if (validationError != null) {
            IonServer.slF4JLogger.error("[CreditTaxes] Invalid creditTax.json: $validationError")
            return
        }

        val frequencyMillis = frequencyMillis(config)
        if (frequencyMillis == 0L) {
            if (logStartupState) IonServer.slF4JLogger.info("[CreditTaxes] Credit tax frequency is 0; scheduled tax is disabled.")
            return
        }

        val now = System.currentTimeMillis()
        var state = CreditTaxStateStore.load()

        if (state == null) {
            state = CreditTaxStateStore.create(now, frequencyMillis)
            CreditTaxStateStore.save(state)

            IonServer.slF4JLogger.info("[CreditTaxes] No credit tax state found. Initializing new state at ${CreditTaxStateStore.path()}.")
            IonServer.slF4JLogger.info("[CreditTaxes] First scheduled tax set for ${Instant.ofEpochMilli(state.nextRunAtEpochMillis)}. No tax will run immediately.")
            return
        }

        if (now < state.nextRunAtEpochMillis) {
            if (logStartupState) {
                IonServer.slF4JLogger.info("[CreditTaxes] Nontaxable day. Next credit tax run is scheduled for ${Instant.ofEpochMilli(state.nextRunAtEpochMillis)}.")
            }
            return
        }

        startTaxRun(config, state, now, frequencyMillis)
    }

    private fun validateConfig(config: CreditTaxConfiguration): String? {
        if (config.taxRatePercent < 0.0) return "taxRatePercent must be >= 0"
        if (config.taxRatePercent > 100.0) return "taxRatePercent must be <= 100"
        if (config.frequency.length < 0L) return "frequency.length must be >= 0"
        if (config.taxableBalanceThreshold < 0.0) return "taxableBalanceThreshold must be >= 0"
        return null
    }

    private fun frequencyMillis(config: CreditTaxConfiguration): Long {
        if (config.frequency.length == 0L) return 0L
        return config.frequency.unit.toMillis(config.frequency.length)
    }

    private fun startTaxRun(config: CreditTaxConfiguration, state: CreditTaxState, now: Long, frequencyMillis: Long) {
        if (!taxRunning.compareAndSet(false, true)) {
            IonServer.slF4JLogger.warn("[CreditTaxes] Tax is due, but a credit tax run is already active.")
            return
        }

        Bukkit.getScheduler().runTaskAsynchronously(IonServer, Runnable {
            val startedAt = System.currentTimeMillis()
            val runId = buildRunId(startedAt)
            val lateByMillis = maxOf(0L, startedAt - state.nextRunAtEpochMillis)
            val frequencyDescription = "${config.frequency.unit}:${config.frequency.length}"
            var currentBucket = "STARTUP"
            var audit: CreditTaxAuditLog? = null

            try {
                currentBucket = "PREFLIGHT"
                val preflightFailures = preflightAvailability(config)

                if (preflightFailures.isNotEmpty()) {
                    IonServer.slF4JLogger.error("[CreditTaxes] Credit tax preflight failed. No balances were taxed.")
                    preflightFailures.forEach { failure ->
                        IonServer.slF4JLogger.error("[CreditTaxes] Preflight failure: $failure")
                    }
                    IonServer.slF4JLogger.error("[CreditTaxes] State was not advanced. Tax will still be considered due on next startup/check.")
                    return@Runnable
                }

                audit = CreditTaxAuditLog.create(
                    runId = runId,
                    startedAt = startedAt,
                    scheduledFor = state.nextRunAtEpochMillis,
                    lateByMillis = lateByMillis,
                    frequencyDescription = frequencyDescription,
                    config = config
                )

                IonServer.slF4JLogger.info("[CreditTaxes] Tax due. Starting credit tax run $runId.")
                IonServer.slF4JLogger.info("[CreditTaxes] Scheduled for ${Instant.ofEpochMilli(state.nextRunAtEpochMillis)}. Current time ${Instant.ofEpochMilli(startedAt)}.")
                if (lateByMillis > 0L) {
                    IonServer.slF4JLogger.info("[CreditTaxes] Tax is late by ${formatDuration(lateByMillis)}. Applying one normal ${config.taxRatePercent}% tax only.")
                } else {
                    IonServer.slF4JLogger.info("[CreditTaxes] Rate: ${config.taxRatePercent}%. Missed periods will not stack. Applying one normal tax only.")
                }
                IonServer.slF4JLogger.info("[CreditTaxes] Writing audit log to ${audit.path()}.")

                val runSummary = CreditTaxRunSummary()
                val players = SLPlayer.all().toList()
                val identities = CreditTaxPlayerIdentityCache(players)

                fun runBucket(name: String, consoleName: String, block: () -> CreditTaxBucketSummary) {
                    currentBucket = name
                    IonServer.slF4JLogger.info("[CreditTaxes] Taxing $consoleName...")
                    val summary = block()
                    audit!!.writeBucketSummary(name, summary)
                    runSummary.add(summary)
                }

                if (config.taxPlayerVaultBalances) runBucket("PLAYER_BALANCES", "player Vault balances") {
                    taxPlayerVaultBalances(config, audit!!, identities)
                }

                if (config.taxNations) runBucket("NATIONS", "nation balances") {
                    taxNationBalances(config, audit!!)
                }

                if (config.taxSettlements) runBucket("SETTLEMENTS", "settlement balances") {
                    taxSettlementBalances(config, audit!!)
                }

                if (config.taxBazaarSellListingProceeds) runBucket("BAZAAR_SELL_PROCEEDS", "bazaar sell-listing proceeds") {
                    taxBazaarSellListingProceeds(config, audit!!, identities)
                }

                if (config.taxBazaarBuyOrderEscrow) runBucket("BAZAAR_BUY_ESCROW", "bazaar buy-order escrow") {
                    taxBazaarBuyOrderEscrow(config, audit!!, identities)
                }

                if (config.taxRentalZonePrepaidBalances) runBucket("RENTAL_ZONE_BALANCES", "rental-zone prepaid balances") {
                    taxRentalZoneBalances(config, audit!!, identities)
                }

                if (config.taxBounties) runBucket("BOUNTIES", "bounty balances") {
                    taxBountyBalances(config, audit!!, identities)
                }

                val completedAt = System.currentTimeMillis()
                val nextRunAt = startedAt + frequencyMillis

                audit.writeCompleted(
                    completedAt = completedAt,
                    durationMillis = completedAt - startedAt,
                    summary = runSummary,
                    nextRunAt = nextRunAt
                )

                CreditTaxStateStore.save(
                    CreditTaxState(
                        lastRunAtEpochMillis = startedAt,
                        nextRunAtEpochMillis = nextRunAt
                    )
                )

                IonServer.slF4JLogger.info("[CreditTaxes] Credit tax run $runId completed successfully.")
                IonServer.slF4JLogger.info("[CreditTaxes] Taxed ${runSummary.totalTaxed} balances. Removed ${CreditTaxAuditLog.amount(runSummary.totalRemoved)}C total.")
                IonServer.slF4JLogger.info("[CreditTaxes] Next run scheduled for ${Instant.ofEpochMilli(nextRunAt)}.")
                IonServer.slF4JLogger.info("[CreditTaxes] Audit log: ${audit.path()}")
            } catch (throwable: Throwable) {
                audit?.writeFailed(System.currentTimeMillis(), currentBucket, throwable)
                IonServer.slF4JLogger.error("[CreditTaxes] Credit tax run $runId failed during bucket $currentBucket.", throwable)
                IonServer.slF4JLogger.error("[CreditTaxes] State was not advanced. Tax will still be considered due on next startup/check.")
                audit?.let { IonServer.slF4JLogger.error("[CreditTaxes] Inspect incomplete audit log: ${it.path()}") }
            } finally {
                taxRunning.set(false)
            }
        })
    }

    private fun preflightAvailability(config: CreditTaxConfiguration): List<String> {
        val failures = mutableListOf<String>()

        fun check(bucket: String, block: () -> Unit) {
            try {
                block()
            } catch (throwable: Throwable) {
                failures += "$bucket unavailable: ${throwable.javaClass.simpleName}: ${throwable.message}"
            }
        }

        val vaultRequiredBy = mutableListOf<String>()
        if (config.taxPlayerVaultBalances) vaultRequiredBy += "PLAYER_BALANCES"
        if (config.taxBazaarBuyOrderEscrow && config.taxBazaarBuyOrderEscrowFromPlayerVaultFirst) {
            vaultRequiredBy += "BAZAAR_BUY_ESCROW"
        }

        if (vaultRequiredBy.isNotEmpty()) {
            check("VAULT_ECONOMY requiredBy=${vaultRequiredBy.joinToString("+")}") {
                val economy = VAULT_ECO
                economy.toString()
            }
        }

        if (config.taxNations) {
            check("NATIONS") { Nation.col.countDocuments() }
        }

        if (config.taxSettlements) {
            check("SETTLEMENTS") { Settlement.col.countDocuments() }
        }

        if (config.taxBazaarSellListingProceeds) {
            check("BAZAAR_SELL_PROCEEDS") { BazaarItem.col.countDocuments() }
        }

        if (config.taxBazaarBuyOrderEscrow) {
            check("BAZAAR_BUY_ESCROW") { BazaarOrder.col.countDocuments() }
        }

        if (config.taxRentalZonePrepaidBalances) {
            check("RENTAL_ZONE_BALANCES") { StationRentalZone.col.countDocuments() }
        }

        if (config.taxBounties) {
            check("BOUNTIES") { SLPlayer.col.countDocuments() }
        }

        return failures
    }

    private fun writeTaxedBalance(
        config: CreditTaxConfiguration,
        audit: CreditTaxAuditLog,
        line: String
    ) {
        if (config.auditLogEveryTaxedBalance) {
            audit.writeLine(line)
        }
    }

    private fun taxPlayerVaultBalances(
        config: CreditTaxConfiguration,
        audit: CreditTaxAuditLog,
        identities: CreditTaxPlayerIdentityCache
    ): CreditTaxBucketSummary {
        val summary = CreditTaxBucketSummary()

        for (player in identities.players) {
            val identity = identities.resolve(player._id)
            val offlinePlayer = Bukkit.getOfflinePlayer(identity.uuid)
            val before = VAULT_ECO.getBalance(offlinePlayer)
            val tax = calculateDoubleTax(before, config)

            if (tax <= 0.0) {
                summary.recordSkip()
                continue
            }

            val response = VAULT_ECO.withdrawPlayer(offlinePlayer, tax)
            if (!response.transactionSuccess()) {
                summary.recordSkip()
                audit.writeLine(
                    "type=PLAYER_SKIPPED uuid=${identity.uuid} name=${CreditTaxAuditLog.quote(identity.name)} " +
                        "nameSource=${identity.nameSource} before=${CreditTaxAuditLog.amount(before)} tax=${CreditTaxAuditLog.amount(tax)} " +
                        "reason=${CreditTaxAuditLog.quote(response.errorMessage ?: "Vault withdrawal failed")}"
                )
                continue
            }

            summary.recordTax(tax)
            writeTaxedBalance(
                config,
                audit,
                "type=PLAYER uuid=${identity.uuid} name=${CreditTaxAuditLog.quote(identity.name)} nameSource=${identity.nameSource} " +
                    "before=${CreditTaxAuditLog.amount(before)} tax=${CreditTaxAuditLog.amount(tax)} after=${CreditTaxAuditLog.amount(before - tax)}"
            )
        }

        return summary
    }

    private fun taxNationBalances(config: CreditTaxConfiguration, audit: CreditTaxAuditLog): CreditTaxBucketSummary {
        val summary = CreditTaxBucketSummary()

        for (nation in Nation.all()) {
            val before = nation.balance
            val tax = calculateIntTax(before, config)

            if (tax <= 0) {
                summary.recordSkip()
                continue
            }

            val result = Nation.col.updateOne(
                and(Nation::_id eq nation._id, Nation::balance gte tax),
                inc(Nation::balance, -tax)
            )

            if (result.modifiedCount != 1L) {
                summary.recordSkip()
                continue
            }

            summary.recordTax(tax.toDouble())
            writeTaxedBalance(
                config,
                audit,
                "type=NATION id=${nation._id} name=${CreditTaxAuditLog.quote(nation.name)} " +
                    "before=$before tax=$tax after=${before - tax}"
            )
        }

        return summary
    }

    private fun taxSettlementBalances(config: CreditTaxConfiguration, audit: CreditTaxAuditLog): CreditTaxBucketSummary {
        val summary = CreditTaxBucketSummary()

        for (settlement in Settlement.all()) {
            val before = settlement.balance
            val tax = calculateIntTax(before, config)

            if (tax <= 0) {
                summary.recordSkip()
                continue
            }

            val result = Settlement.col.updateOne(
                and(Settlement::_id eq settlement._id, Settlement::balance gte tax),
                inc(Settlement::balance, -tax)
            )

            if (result.modifiedCount != 1L) {
                summary.recordSkip()
                continue
            }

            summary.recordTax(tax.toDouble())
            writeTaxedBalance(
                config,
                audit,
                "type=SETTLEMENT id=${settlement._id} name=${CreditTaxAuditLog.quote(settlement.name)} " +
                    "before=$before tax=$tax after=${before - tax}"
            )
        }

        return summary
    }

    private fun taxBazaarSellListingProceeds(
        config: CreditTaxConfiguration,
        audit: CreditTaxAuditLog,
        identities: CreditTaxPlayerIdentityCache
    ): CreditTaxBucketSummary {
        val summary = CreditTaxBucketSummary()

        for (item in BazaarItem.all()) {
            val before = item.balance
            val tax = calculateDoubleTax(before, config)

            if (tax <= 0.0) {
                summary.recordSkip()
                continue
            }

            val result = BazaarItem.col.updateOne(
                and(BazaarItem::_id eq item._id, BazaarItem::balance gte tax),
                inc(BazaarItem::balance, -tax)
            )

            if (result.modifiedCount != 1L) {
                summary.recordSkip()
                continue
            }

            val seller = identities.resolve(item.seller)
            summary.recordTax(tax)
            writeTaxedBalance(
                config,
                audit,
                "type=BAZAAR_SELL_PROCEEDS id=${item._id} sellerUuid=${seller.uuid} " +
                    "sellerName=${CreditTaxAuditLog.quote(seller.name)} sellerNameSource=${seller.nameSource} " +
                    "item=${CreditTaxAuditLog.quote(item.itemString)} before=${CreditTaxAuditLog.amount(before)} " +
                    "tax=${CreditTaxAuditLog.amount(tax)} after=${CreditTaxAuditLog.amount(before - tax)}"
            )
        }

        return summary
    }

    private fun taxBazaarBuyOrderEscrow(
        config: CreditTaxConfiguration,
        audit: CreditTaxAuditLog,
        identities: CreditTaxPlayerIdentityCache
    ): CreditTaxBucketSummary {
        val summary = CreditTaxBucketSummary()
        val orders = BazaarOrder.all().toList()

        if (!config.taxBazaarBuyOrderEscrowFromPlayerVaultFirst) {
            for (order in orders) {
                taxBazaarBuyOrderEscrowDirectEntry(config, audit, identities, order, summary)
            }

            return summary
        }

        val taxableOrdersByPlayer = orders.mapNotNull { order ->
            val tax = calculateDoubleTax(order.balance, config)

            if (tax <= 0.0) {
                summary.recordSkip()
                null
            } else {
                order to tax
            }
        }.groupBy { it.first.player }

        for ((playerId, playerOrders) in taxableOrdersByPlayer) {
            val buyer = identities.resolve(playerId)
            val offlinePlayer = Bukkit.getOfflinePlayer(buyer.uuid)
            val totalTax = playerOrders.fold(0.0) { total, (_, tax) -> total + tax }
            val vaultBefore = VAULT_ECO.getBalance(offlinePlayer)

            if (vaultBefore >= totalTax) {
                val response = VAULT_ECO.withdrawPlayer(offlinePlayer, totalTax)

                if (response.transactionSuccess()) {
                    for ((order, tax) in playerOrders) {
                        val before = order.balance

                        summary.recordTax(tax)
                        writeTaxedBalance(
                            config,
                            audit,
                            "type=BAZAAR_BUY_ESCROW_VAULT id=${order._id} buyerUuid=${buyer.uuid} " +
                                "buyerName=${CreditTaxAuditLog.quote(buyer.name)} buyerNameSource=${buyer.nameSource} " +
                                "item=${CreditTaxAuditLog.quote(order.itemString)} escrowBefore=${CreditTaxAuditLog.amount(before)} " +
                                "tax=${CreditTaxAuditLog.amount(tax)} escrowAfter=${CreditTaxAuditLog.amount(before)} " +
                                "vaultTaxTotal=${CreditTaxAuditLog.amount(totalTax)} vaultBefore=${CreditTaxAuditLog.amount(vaultBefore)} " +
                                "vaultAfter=${CreditTaxAuditLog.amount(vaultBefore - totalTax)}"
                        )
                    }

                    continue
                }

                audit.writeLine(
                    "type=BAZAAR_BUY_ESCROW_VAULT_SKIPPED buyerUuid=${buyer.uuid} " +
                        "buyerName=${CreditTaxAuditLog.quote(buyer.name)} buyerNameSource=${buyer.nameSource} " +
                        "vaultBefore=${CreditTaxAuditLog.amount(vaultBefore)} requestedTax=${CreditTaxAuditLog.amount(totalTax)} " +
                        "reason=${CreditTaxAuditLog.quote(response.errorMessage ?: "Vault withdrawal failed")}"
                )
            }

            for ((order, _) in playerOrders) {
                taxBazaarBuyOrderEscrowDirectEntry(config, audit, identities, order, summary)
            }
        }

        return summary
    }

    private fun taxBazaarBuyOrderEscrowDirectEntry(
        config: CreditTaxConfiguration,
        audit: CreditTaxAuditLog,
        identities: CreditTaxPlayerIdentityCache,
        order: BazaarOrder,
        summary: CreditTaxBucketSummary
    ) {
        val before = order.balance
        val tax = calculateDoubleTax(before, config)

        if (tax <= 0.0) {
            summary.recordSkip()
            return
        }

        val result = BazaarOrder.col.updateOne(
            and(BazaarOrder::_id eq order._id, BazaarOrder::balance gte tax),
            inc(BazaarOrder::balance, -tax)
        )

        if (result.modifiedCount != 1L) {
            summary.recordSkip()
            return
        }

        val buyer = identities.resolve(order.player)
        summary.recordTax(tax)
        writeTaxedBalance(
            config,
            audit,
            "type=BAZAAR_BUY_ESCROW id=${order._id} taxSource=ESCROW buyerUuid=${buyer.uuid} " +
                "buyerName=${CreditTaxAuditLog.quote(buyer.name)} buyerNameSource=${buyer.nameSource} " +
                "item=${CreditTaxAuditLog.quote(order.itemString)} before=${CreditTaxAuditLog.amount(before)} " +
                "tax=${CreditTaxAuditLog.amount(tax)} after=${CreditTaxAuditLog.amount(before - tax)}"
        )
    }

    private fun taxRentalZoneBalances(
        config: CreditTaxConfiguration,
        audit: CreditTaxAuditLog,
        identities: CreditTaxPlayerIdentityCache
    ): CreditTaxBucketSummary {
        val summary = CreditTaxBucketSummary()

        for (zone in StationRentalZone.all()) {
            val before = zone.rentBalance
            val tax = calculateDoubleTax(before, config)

            if (tax <= 0.0) {
                summary.recordSkip()
                continue
            }

            val result = StationRentalZone.col.updateOne(
                and(StationRentalZone::_id eq zone._id, StationRentalZone::rentBalance gte tax),
                inc(StationRentalZone::rentBalance, -tax)
            )

            if (result.modifiedCount != 1L) {
                summary.recordSkip()
                continue
            }

            val owner = zone.owner?.let { identities.resolve(it) }
            summary.recordTax(tax)
            writeTaxedBalance(
                config,
                audit,
                "type=RENTAL_ZONE_BALANCE id=${zone._id} ownerUuid=${owner?.uuid ?: "null"} " +
                    "ownerName=${CreditTaxAuditLog.quote(owner?.name ?: "none")} ownerNameSource=${owner?.nameSource ?: "UNKNOWN"} " +
                    "zone=${CreditTaxAuditLog.quote(zone.name)} before=${CreditTaxAuditLog.amount(before)} " +
                    "tax=${CreditTaxAuditLog.amount(tax)} after=${CreditTaxAuditLog.amount(before - tax)}"
            )
        }

        return summary
    }

    private fun taxBountyBalances(
        config: CreditTaxConfiguration,
        audit: CreditTaxAuditLog,
        identities: CreditTaxPlayerIdentityCache
    ): CreditTaxBucketSummary {
        val summary = CreditTaxBucketSummary()

        for (player in identities.players) {
            val before = player.bounty
            val tax = calculateDoubleTax(before, config)

            if (tax <= 0.0) {
                summary.recordSkip()
                continue
            }

            val result = SLPlayer.col.updateOne(
                and(SLPlayer::_id eq player._id, SLPlayer::bounty gte tax),
                inc(SLPlayer::bounty, -tax)
            )

            if (result.modifiedCount != 1L) {
                summary.recordSkip()
                continue
            }

            val identity = identities.resolve(player._id)
            summary.recordTax(tax)
            writeTaxedBalance(
                config,
                audit,
                "type=BOUNTY targetUuid=${identity.uuid} targetName=${CreditTaxAuditLog.quote(identity.name)} " +
                    "targetNameSource=${identity.nameSource} before=${CreditTaxAuditLog.amount(before)} " +
                    "tax=${CreditTaxAuditLog.amount(tax)} after=${CreditTaxAuditLog.amount(before - tax)}"
            )
        }

        return summary
    }

    private fun calculateIntTax(balance: Int, config: CreditTaxConfiguration): Int {
        if (balance <= 0) return 0
        if (balance.toDouble() < config.taxableBalanceThreshold) return 0
        return floor(balance * config.taxRatePercent / 100.0).toInt()
    }

    private fun calculateDoubleTax(balance: Double, config: CreditTaxConfiguration): Double {
        if (balance <= 0.0) return 0.0
        if (balance < config.taxableBalanceThreshold) return 0.0
        return floor(balance * (config.taxRatePercent / 100.0) * 100.0) / 100.0
    }

    private fun buildRunId(startedAt: Long): String {
        val timestamp = Instant.ofEpochMilli(startedAt)
            .toString()
            .replace(":", "-")
            .replace(".", "-")

        return "$timestamp-${UUID.randomUUID().toString().take(6)}"
    }

    private fun formatDuration(milliseconds: Long): String {
        val duration = Duration.ofMillis(milliseconds)
        val days = duration.toDays()
        val hours = duration.minusDays(days).toHours()
        val minutes = duration.minusDays(days).minusHours(hours).toMinutes()

        return "${days}d ${hours}h ${minutes}m"
    }
}
