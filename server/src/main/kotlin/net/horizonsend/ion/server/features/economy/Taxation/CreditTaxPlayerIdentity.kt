package net.horizonsend.ion.server.features.economy.Taxation

import net.horizonsend.ion.common.database.schema.misc.SLPlayer
import net.horizonsend.ion.common.database.schema.misc.SLPlayerId
import net.horizonsend.ion.common.database.uuid
import org.bukkit.Bukkit
import java.util.UUID

enum class CreditTaxPlayerNameSource {
    ONLINE,
    SLPLAYER_LAST_KNOWN,
    BUKKIT_OFFLINE,
    UNKNOWN
}

data class CreditTaxPlayerIdentity(
    val uuid: UUID,
    val name: String,
    val nameSource: CreditTaxPlayerNameSource
)

class CreditTaxPlayerIdentityCache(val players: List<SLPlayer>) {
    private val playerDocuments = players.associateBy { it._id }
    private val cache = mutableMapOf<SLPlayerId, CreditTaxPlayerIdentity>()

    fun resolve(playerId: SLPlayerId): CreditTaxPlayerIdentity {
        return cache.getOrPut(playerId) {
            val uuid = playerId.uuid
            val onlineName = Bukkit.getPlayer(uuid)?.name

            if (!onlineName.isNullOrBlank()) {
                return@getOrPut CreditTaxPlayerIdentity(uuid, onlineName, CreditTaxPlayerNameSource.ONLINE)
            }

            val slPlayerName = playerDocuments[playerId]?.lastKnownName
            if (!slPlayerName.isNullOrBlank()) {
                return@getOrPut CreditTaxPlayerIdentity(uuid, slPlayerName, CreditTaxPlayerNameSource.SLPLAYER_LAST_KNOWN)
            }

            val bukkitName = Bukkit.getOfflinePlayer(uuid).name
            if (!bukkitName.isNullOrBlank()) {
                return@getOrPut CreditTaxPlayerIdentity(uuid, bukkitName, CreditTaxPlayerNameSource.BUKKIT_OFFLINE)
            }

            CreditTaxPlayerIdentity(uuid, "unknown", CreditTaxPlayerNameSource.UNKNOWN)
        }
    }
}
