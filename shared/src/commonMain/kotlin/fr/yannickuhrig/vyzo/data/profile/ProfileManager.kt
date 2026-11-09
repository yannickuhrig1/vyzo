package fr.yannickuhrig.vyzo.data.profile

import fr.yannickuhrig.vyzo.data.db.withTransaction
import fr.yannickuhrig.vyzo.data.db.AppDatabase
import fr.yannickuhrig.vyzo.data.db.DEFAULT_PROFILE_NAME
import fr.yannickuhrig.vyzo.data.db.ProfileEntity
import fr.yannickuhrig.vyzo.data.prefs.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.security.MessageDigest
import java.util.UUID

/** Profils du foyer : création, modification, choix du profil actif, codes PIN. */
class ProfileManager(
    private val db: AppDatabase,
    private val settings: SettingsStore,
    scope: CoroutineScope,
) {
    val profiles: StateFlow<List<ProfileEntity>?> =
        db.profiles().observeAll().stateIn(scope, SharingStarted.Eagerly, null)

    /** Profil actif : celui choisi sur cet appareil, ou à défaut le premier. */
    val active: StateFlow<ProfileEntity?> =
        combine(profiles.filterNotNull(), settings.flow.map { it.activeProfileId }.distinctUntilChanged()) { list, id ->
            list.firstOrNull { it.id == id } ?: list.firstOrNull()
        }.stateIn(scope, SharingStarted.Eagerly, null)

    /** Identifiant du profil actif ; n'émet qu'une fois les profils chargés. */
    val activeId: Flow<String> = active.filterNotNull().map { it.id }.distinctUntilChanged()

    val currentProfileId: String get() = active.value?.id.orEmpty()

    private val _changed = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    /** Émis à chaque modification locale d'un profil, pour déclencher l'envoi vers le compte. */
    val changed: SharedFlow<Unit> = _changed.asSharedFlow()

    private val _switched = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** Émis quand l'utilisateur change de profil (pour reverrouiller le contrôle parental…). */
    val switched: SharedFlow<String> = _switched.asSharedFlow()

    /** Profil actif à cet instant, sans dépendre de [active] qui peut être en retard juste après un changement de profil. */
    suspend fun activeNow(): ProfileEntity =
        db.profiles().get(settings.get().activeProfileId) ?: active.filterNotNull().first()

    suspend fun activeIdNow(): String = activeNow().id

    suspend fun select(id: String) {
        settings.update { it.withActiveProfile(id) }
        _switched.tryEmit(id)
    }

    suspend fun create(name: String, colorIndex: Int? = null, isKid: Boolean = false, localDeviceId: String = ""): ProfileEntity {
        val all = db.profiles().all()
        val p = ProfileEntity(
            id = UUID.randomUUID().toString(),
            name = cleanName(name),
            colorIndex = colorIndex ?: nextColor(all),
            isKid = isKid,
            position = db.profiles().maxPosition() + 1,
            dirty = localDeviceId.isBlank(),
            localDeviceId = localDeviceId,
        )
        db.profiles().upsert(p)
        _changed.tryEmit(Unit)
        return p
    }

    suspend fun update(id: String, transform: (ProfileEntity) -> ProfileEntity) {
        val cur = db.profiles().get(id) ?: return
        db.profiles().upsert(transform(cur).copy(id = cur.id, updatedAt = System.currentTimeMillis(), dirty = true))
        _changed.tryEmit(Unit)
    }

    suspend fun rename(id: String, name: String) = update(id) { it.copy(name = cleanName(name)) }

    suspend fun setColor(id: String, colorIndex: Int) = update(id) { it.copy(colorIndex = colorIndex) }

    suspend fun setKid(id: String, kid: Boolean) = update(id) { it.copy(isKid = kid) }

    suspend fun setPin(id: String, pin: String?) = update(id) { it.copy(pinHash = if (pin.isNullOrBlank()) "" else hashPin(id, pin)) }

    fun checkPin(p: ProfileEntity, pin: String): Boolean = p.pinHash.isBlank() || p.pinHash == hashPin(p.id, pin)

    /** Déplace un profil d'un cran dans la liste. */
    suspend fun move(id: String, delta: Int) {
        val list = db.profiles().all().toMutableList()
        val i = list.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in list.indices) return
        list.add(j, list.removeAt(i))
        val now = System.currentTimeMillis()
        list.forEachIndexed { pos, p -> if (p.position != pos) db.profiles().upsert(p.copy(position = pos, updatedAt = now, dirty = true)) }
        _changed.tryEmit(Unit)
    }

    /** Supprime un profil, sa progression et ses favoris. Le dernier profil ne peut pas être supprimé. */
    suspend fun delete(id: String): Boolean {
        val all = db.profiles().all()
        if (all.size <= 1 || all.none { it.id == id }) return false
        db.withTransaction {
            val cur = db.profiles().get(id) ?: return@withTransaction
            // Un profil local n'a personne à prévenir : il disparaît tout de suite.
            if (cur.localOnly) {
                db.profiles().hardDelete(id)
                db.tombstones().deleteProfile(id)
            } else {
                db.profiles().upsert(cur.copy(deleted = true, dirty = true, updatedAt = System.currentTimeMillis()))
            }
            db.progress().deleteProfile(id)
            db.progress().deleteHistory(id)
            db.groups().deleteProfileItems(id)
            db.groups().deleteProfileGroups(id)
        }
        if (settings.get().activeProfileId == id || settings.get().activeProfileId.isBlank()) select(all.first { it.id != id }.id)
        settings.update { it.copy(lastPlaces = it.lastPlaces - id) }
        _changed.tryEmit(Unit)
        return true
    }

    /**
     * Fusionne le profil local [from] dans [to] (même personne, créée séparément sur deux appareils) :
     * progression, chaînes récentes, favoris et groupes passent à [to], puis [from] disparaît sans laisser de trace.
     */
    suspend fun mergeInto(from: String, to: String) {
        if (from == to) return
        db.withTransaction {
            db.progress().mergeInto(from, to)
            db.progress().deleteProfile(from)
            db.progress().mergeHistoryInto(from, to)
            db.progress().deleteHistory(from)
            db.groups().moveItems(from, to)
            db.groups().moveGroups(from, to)
            db.profiles().hardDelete(from)
        }
        // Même personne : la place du profil actif reste en place, l'autre n'a plus lieu d'être gardée.
        if (settings.get().activeProfileId == from) settings.update { it.copy(activeProfileId = to, lastPlaces = it.lastPlaces - from - to) }
        else settings.update { it.copy(lastPlaces = it.lastPlaces - from) }
    }

    /** S'assure qu'il existe au moins un profil (base vidée, sauvegarde restaurée…). */
    suspend fun ensureOne(): ProfileEntity =
        db.profiles().all().firstOrNull() ?: create(DEFAULT_PROFILE_NAME, colorIndex = 0)

    companion object {
        const val PALETTE_SIZE = 8
        private const val MAX_NAME = 24

        fun cleanName(name: String): String = name.trim().replace(Regex("\\s+"), " ").take(MAX_NAME).ifBlank { "Profil" }

        fun hashPin(profileId: String, pin: String): String = sha256("vyzo-pin:$profileId:$pin")

        fun sha256(s: String): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

        private fun nextColor(existing: List<ProfileEntity>): Int {
            val used = existing.map { it.colorIndex }.toSet()
            return (0 until PALETTE_SIZE).firstOrNull { it !in used } ?: (existing.size % PALETTE_SIZE)
        }
    }
}
