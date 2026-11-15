package fr.yannickuhrig.vyzo.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collectLatest

/**
 * Élément survolé à la télécommande, traité par [onSettle] (qui commence en général par un court délai).
 * Une nouvelle valeur annule le traitement en cours, comme un LaunchedEffect qui l'aurait pour clé ; mais seule la
 * coroutine lit la valeur : descendre une liste ne recompose plus tout l'écran à chaque ligne.
 */
@Composable
internal fun <T : Any> rememberSettledFocus(onSettle: suspend (T) -> Unit): MutableState<T?> {
    val state = remember { mutableStateOf<T?>(null) }
    val action by rememberUpdatedState(onSettle)
    LaunchedEffect(state) {
        snapshotFlow { state.value }.collectLatest { value -> if (value != null) action(value) }
    }
    return state
}
