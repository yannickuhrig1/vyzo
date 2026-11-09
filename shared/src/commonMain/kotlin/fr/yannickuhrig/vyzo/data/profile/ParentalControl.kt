package fr.yannickuhrig.vyzo.data.profile

import fr.yannickuhrig.vyzo.data.db.AppDatabase
import fr.yannickuhrig.vyzo.data.prefs.SettingsStore
import fr.yannickuhrig.vyzo.util.AdultDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Contrôle parental.
 *
 * - Un code parental active le contrôle : les catégories verrouillées (choisies, ou détectées « adultes »)
 *   et les chaînes verrouillées sont cachées partout tant que le code n'a pas été saisi.
 * - Une fois le code saisi, le contenu reste visible pendant la session (jusqu'à [RELOCK_AFTER_MS] en arrière-plan
 *   ou un changement de profil).
 * - Sur un profil enfant, le contenu verrouillé est toujours caché, même sans code parental.
 */
class ParentalControl(
    private val db: AppDatabase,
    private val settings: SettingsStore,
    private val profiles: ProfileManager,
    private val scope: CoroutineScope,
) {
    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    private val _isVaultUnlocked = MutableStateFlow(false)
    /** État en mémoire du coffre-fort pour la session active (mode furtif). */
    val isVaultUnlocked: StateFlow<Boolean> = _isVaultUnlocked.asStateFlow()

    val pinSet: StateFlow<Boolean> = settings.flow.map { it.parentalPinHash.isNotBlank() }.distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, false)

    val kidProfile: StateFlow<Boolean> = profiles.active.map { it?.isKid == true }.distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, false)

    /** Le contenu verrouillé doit être caché dans les listes. */
    val hideLocked: StateFlow<Boolean> = combine(pinSet, kidProfile, _unlocked) { pin, kid, unlocked ->
        kid || (pin && !unlocked)
    }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, false)

    private var backgroundSince = 0L

    init {
        scope.launch { profiles.switched.collect { lockVault() } }
    }

    fun checkPin(pin: String): Boolean {
        val h = settings.state.value.parentalPinHash
        return h.isNotBlank() && h == hashParental(pin)
    }

    /** Saisie du code parental : déverrouille le contenu pour la session (sauf sur un profil enfant). */
    fun unlock(pin: String): Boolean {
        if (!checkPin(pin)) return false
        _unlocked.value = true
        return true
    }

    /** Déverrouille le coffre-fort et les catégories sensibles pour la session en cours. */
    fun unlockVault(pin: String): Boolean {
        if (!checkPin(pin)) return false
        _isVaultUnlocked.value = true
        _unlocked.value = true
        return true
    }

    /** Reverrouille immédiatement le coffre-fort et la session parentale. */
    fun lockVault() {
        _isVaultUnlocked.value = false
        _unlocked.value = false
    }

    fun lock() {
        lockVault()
    }

    suspend fun setPin(pin: String?) {
        val hash = if (pin.isNullOrBlank()) "" else hashParental(pin)
        settings.update {
            it.copy(
                parentalPinHash = hash,
                stealthModeEnabled = if (hash.isNotBlank()) true else false,
            )
        }
        lockVault()
    }

    suspend fun setAutoAdult(enabled: Boolean) {
        settings.update { it.copy(parentalAutoAdult = enabled) }
        if (!enabled) db.categories().clearAdultFlags()
    }

    /**
     * Verrouille sans attendre la prochaine synchro les catégories que la détection reconnaît désormais
     * (après une mise à jour de [AdultDetector]). Ne retire jamais un verrou : la synchro s'en charge.
     */
    suspend fun refreshAdultFlags() {
        if (!settings.get().parentalAutoAdult) return
        val uids = db.categories().notAdult().filter { AdultDetector.isAdultCategory(it.name) }.map { it.uid }
        uids.chunked(500).forEach { db.categories().markAdult(it) }
    }

    /** Une catégorie demande le code avant d'être ouverte. */
    suspend fun needsPin(categoryUid: String): Boolean =
        pinSet.value && !_unlocked.value && db.categories().isLocked(categoryUid)

    fun onBackground(now: Long = System.currentTimeMillis()) {
        backgroundSince = now
        lockVault()
    }

    fun onForeground(now: Long = System.currentTimeMillis()) {
        if (backgroundSince > 0 && now - backgroundSince > RELOCK_AFTER_MS) _unlocked.value = false
        backgroundSince = 0
    }

    companion object {
        const val RELOCK_AFTER_MS = 5 * 60_000L

        fun hashParental(pin: String): String = ProfileManager.sha256("vyzo-parental:$pin")
    }
}
