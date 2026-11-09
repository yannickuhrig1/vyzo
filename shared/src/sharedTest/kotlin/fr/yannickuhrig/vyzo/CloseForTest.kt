package fr.yannickuhrig.vyzo

import fr.yannickuhrig.vyzo.data.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Fin de test : arrête les tâches de fond, puis ferme la base. Dans l'autre ordre, un flux encore actif (profils,
 * contrôle parental…) peut lire une base fermée, et l'erreur fait échouer le test suivant, qui n'y est pour rien.
 */
fun closeForTest(scope: CoroutineScope, db: AppDatabase) = runBlocking {
    withTimeoutOrNull(5_000) { scope.coroutineContext.job.cancelAndJoin() }
    db.close()
}

fun AppContainer.closeForTest() = closeForTest(scope, db)
