package io.github.towkirahmedrony.salah.data.mosque

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * The mosque finder's local database.
 *
 * **Why Room here when the rest of Salah uses DataStore and files.** The cache is a small relational
 * table with one key and two predicates — a rectangle and a cutoff timestamp. Room gives that a
 * schema that is checked at compile time, a generated DAO that runs off the main thread, and an
 * exported schema a future migration can be reviewed against. DataStore would mean reading and
 * rewriting the whole cache to update one mosque, and a flat file would mean implementing the
 * rectangle query by hand.
 *
 * **Schema version 1.** No migration exists because no earlier version shipped. [MIGRATIONS] is
 * declared empty rather than omitted so the next schema change has an obvious place to add its
 * migration, and so a reviewer can see that the absence is deliberate. See `docs/MOSQUE_FINDER.md`
 * for the cache's age bound and the downgrade policy.
 */
@Database(
    entities = [CachedMosqueEntity::class],
    version = MosqueDatabase.VERSION,
    exportSchema = true,
)
abstract class MosqueDatabase : RoomDatabase() {

    abstract fun mosqueDao(): MosqueDao

    companion object {
        /** The schema version this build writes. */
        const val VERSION: Int = 1

        /** The on-device file name. Kept stable; renaming it would orphan every cache. */
        const val NAME: String = "mosque_cache.db"

        /**
         * Migrations from every earlier version to the current one.
         *
         * Empty for version 1. A version bump must add its migration here; a downgrade is handled
         * by the builder in `SalahModules` explicitly rather than by silently accepting a schema
         * this build cannot read.
         */
        val MIGRATIONS: Array<androidx.room.migration.Migration> = emptyArray()
    }
}
