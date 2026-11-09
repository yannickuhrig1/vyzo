package fr.yannickuhrig.vyzo.data.db

import androidx.room.RoomDatabase

/**
 * Exécute [block] dans une transaction en écriture : les appels aux DAO faits dans [block] passent par la même connexion.
 * Android : androidx.room.withTransaction (fil dédié, les transactions SQLite d'Android étant liées au fil) ;
 * PC : connexion d'écriture du pilote SQLite.
 */
expect suspend fun <R> RoomDatabase.withTransaction(block: suspend () -> R): R
