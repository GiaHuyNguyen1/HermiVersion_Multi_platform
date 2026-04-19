package com.hermitech.hermivision.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.hermitech.hermivision.db.AppDatabase

actual class DatabaseDriverFactory actual constructor() {
    actual fun createDriver(): SqlDriver =
        NativeSqliteDriver(AppDatabase.Schema, DATABASE_NAME)

    private companion object {
        const val DATABASE_NAME = "hermivision.db"
    }
}
