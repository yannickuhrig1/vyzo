package fr.yannickuhrig.vyzo.desktop

import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule

/** La règle Compose des tests, créée après SQLite : voir [loadSqliteBeforeCompose]. */
fun sqliteThenComposeRule(): ComposeContentTestRule {
    loadSqliteBeforeCompose()
    return createComposeRule()
}
