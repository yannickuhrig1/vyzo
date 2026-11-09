package fr.yannickuhrig.vyzo.data.db

import androidx.room.RoomDatabase
import androidx.room.withTransaction as roomWithTransaction

actual suspend fun <R> RoomDatabase.withTransaction(block: suspend () -> R): R = roomWithTransaction(block)
