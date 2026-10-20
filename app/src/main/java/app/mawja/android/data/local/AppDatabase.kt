package app.mawja.android.data.local

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room.databaseBuilder
import androidx.room.RoomDatabase
import app.mawja.android.data.local.dao.CacheDao
import app.mawja.android.data.local.dao.DownloadCollectionDao
import app.mawja.android.data.local.dao.DownloadDao
import app.mawja.android.data.local.dao.HomeFeedCacheDao
import app.mawja.android.data.local.dao.MusicGraphDao
import app.mawja.android.data.local.dao.NoteDao
import app.mawja.android.data.local.dao.NotificationDao
import app.mawja.android.data.local.dao.PlaylistDao
import app.mawja.android.data.local.dao.RecognitionHistoryDao
import app.mawja.android.data.local.dao.SubscriptionGroupDao
import app.mawja.android.data.local.dao.SyncLogDao
import app.mawja.android.data.local.dao.SyncPeerDao
import app.mawja.android.data.local.dao.VideoDao
import app.mawja.android.data.local.dao.WatchHistoryDao
import app.mawja.android.data.local.entity.DownloadCollectionEntity
import app.mawja.android.data.local.entity.DownloadCollectionItemEntity
import app.mawja.android.data.local.entity.DownloadEntity
import app.mawja.android.data.local.entity.DownloadItemEntity
import app.mawja.android.data.local.entity.HomeFeedCacheEntity
import app.mawja.android.data.local.entity.MusicGraphAlbumEntity
import app.mawja.android.data.local.entity.MusicGraphArtistEntity
import app.mawja.android.data.local.entity.MusicGraphEdgeEntity
import app.mawja.android.data.local.entity.MusicGraphPlaylistEntity
import app.mawja.android.data.local.entity.MusicGraphTrackEntity
import app.mawja.android.data.local.entity.MusicHomeCacheEntity
import app.mawja.android.data.local.entity.MusicHomeChipEntity
import app.mawja.android.data.local.entity.NoteEntity
import app.mawja.android.data.local.entity.NotificationEntity
import app.mawja.android.data.local.entity.PlaylistEntity
import app.mawja.android.data.local.entity.PlaylistVideoCrossRef
import app.mawja.android.data.local.entity.RecognitionHistoryEntity
import app.mawja.android.data.local.entity.SubscriptionFeedEntity
import app.mawja.android.data.local.entity.SubscriptionGroupEntity
import app.mawja.android.data.local.entity.SyncLogEntity
import app.mawja.android.data.local.entity.SyncPeerEntity
import app.mawja.android.data.local.entity.VideoEntity
import app.mawja.android.data.local.entity.WatchHistoryEntity
import app.mawja.android.data.local.migrations.MIGRATIONS
import app.mawja.android.data.local.migrations.Migration24To25

@Database(
    entities = [
        VideoEntity::class,
        PlaylistEntity::class,
        PlaylistVideoCrossRef::class,
        NotificationEntity::class,
        SubscriptionFeedEntity::class,
        MusicHomeCacheEntity::class,
        MusicHomeChipEntity::class,
        DownloadEntity::class,
        DownloadItemEntity::class,
        WatchHistoryEntity::class,
        HomeFeedCacheEntity::class,
        SubscriptionGroupEntity::class,
        RecognitionHistoryEntity::class,
        SyncLogEntity::class,
        SyncPeerEntity::class,
        MusicGraphTrackEntity::class,
        MusicGraphArtistEntity::class,
        MusicGraphAlbumEntity::class,
        MusicGraphPlaylistEntity::class,
        MusicGraphEdgeEntity::class,
        NoteEntity::class,
        DownloadCollectionEntity::class,
        DownloadCollectionItemEntity::class,
    ],
    autoMigrations = [
        AutoMigration(from = 24, to = 25, spec = Migration24To25::class),
        AutoMigration(from = 25, to = 26),
        AutoMigration(from = 26, to = 27),
        AutoMigration(from = 27, to = 28),
        AutoMigration(from = 28, to = 29),
        AutoMigration(from = 29, to = 30),
        AutoMigration(from = 30, to = 31),
        AutoMigration(from = 31, to = 32),
        AutoMigration(from = 32, to = 33),
    ],
    version = 33,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun videoDao(): VideoDao

    abstract fun playlistDao(): PlaylistDao

    abstract fun notificationDao(): NotificationDao

    abstract fun noteDao(): NoteDao

    abstract fun cacheDao(): CacheDao

    abstract fun downloadDao(): DownloadDao

    abstract fun downloadCollectionDao(): DownloadCollectionDao

    abstract fun watchHistoryDao(): WatchHistoryDao

    abstract fun homeFeedCacheDao(): HomeFeedCacheDao

    abstract fun subscriptionGroupDao(): SubscriptionGroupDao

    abstract fun recognitionHistoryDao(): RecognitionHistoryDao

    abstract fun syncLogDao(): SyncLogDao

    abstract fun syncPeerDao(): SyncPeerDao

    abstract fun musicGraphDao(): MusicGraphDao

    companion object {
        @Volatile
        @Suppress("ktlint:standard:property-naming")
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: android.content.Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                val instance =
                    databaseBuilder(
                        context.applicationContext,
                        AppDatabase::class.java,
                        "flow_database",
                    ).addMigrations(*MIGRATIONS)
                        .fallbackToDestructiveMigration(false)
                        .build()
                INSTANCE = instance
                instance
            }
    }
}
