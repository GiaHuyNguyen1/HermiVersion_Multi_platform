package com.hermitech.hermivision.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.hermitech.hermivision.db.AppDatabase
import com.hermitech.hermivision.platform.AppContextHolder

actual class DatabaseDriverFactory actual constructor() {
    actual fun createDriver(): SqlDriver =
        AndroidSqliteDriver(AppDatabase.Schema, AppContextHolder.applicationContext, DATABASE_NAME)

    private companion object {
        const val DATABASE_NAME = "hermivision.db"
    }
}
