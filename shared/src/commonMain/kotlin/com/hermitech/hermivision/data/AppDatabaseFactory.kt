package com.hermitech.hermivision.data

import app.cash.sqldelight.db.SqlDriver
import com.hermitech.hermivision.db.AppDatabase

object AppDatabaseFactory {
    fun create(driver: SqlDriver): AppDatabase = AppDatabase(driver)
}

fun createAppDatabase(factory: DatabaseDriverFactory = DatabaseDriverFactory()): AppDatabase =
    AppDatabaseFactory.create(factory.createDriver())
