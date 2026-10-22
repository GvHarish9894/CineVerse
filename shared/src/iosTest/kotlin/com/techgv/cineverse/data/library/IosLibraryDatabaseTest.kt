package com.techgv.cineverse.data.library

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver

class IosLibraryDatabaseTest : LibraryDatabaseTest() {
    override fun newDatabase(): LibraryDatabase =
        Room.inMemoryDatabaseBuilder<LibraryDatabase>(factory = { LibraryDatabaseConstructor.initialize() })
            .setDriver(BundledSQLiteDriver())
            .build()
}
