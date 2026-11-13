package me.piyaphat.kei.di

import android.content.Context
import androidx.room.Room
import coil3.disk.DiskCache
import coil3.ImageLoader
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import me.piyaphat.kei.data.api.ServerRepository
import me.piyaphat.kei.data.api.EventStream
import me.piyaphat.kei.data.api.LibraryRepository
import me.piyaphat.kei.data.api.ReaderRepository
import me.piyaphat.kei.data.api.ReaderPrefs
import me.piyaphat.kei.data.api.DeviceRepository
import me.piyaphat.kei.data.impl.db.KeiDatabase
import me.piyaphat.kei.data.impl.network.EventStreamImpl
import me.piyaphat.kei.data.impl.network.ApiFactory
import me.piyaphat.kei.data.impl.network.KeiService
import me.piyaphat.kei.core.api.SseEventParser
import me.piyaphat.kei.core.impl.LineBasedSseEventParser
import me.piyaphat.kei.data.impl.repo.ServerRepositoryImpl
import me.piyaphat.kei.data.impl.repo.LibraryRepositoryImpl
import me.piyaphat.kei.data.impl.repo.ReaderRepositoryImpl
import me.piyaphat.kei.data.impl.prefs.ReaderPrefsImpl
import me.piyaphat.kei.discovery.api.ServerDiscovery
import me.piyaphat.kei.discovery.impl.NsdServerDiscovery
import me.piyaphat.kei.work.api.UploadCleanup
import me.piyaphat.kei.work.api.UploadNotifier
import me.piyaphat.kei.work.api.UploadRemote
import me.piyaphat.kei.work.api.UploadScheduler
import me.piyaphat.kei.work.api.UploadSource
import me.piyaphat.kei.work.api.UploadTaskStore
import me.piyaphat.kei.work.api.UploadTaskRepository
import me.piyaphat.kei.work.impl.AndroidUploadSource
import me.piyaphat.kei.data.impl.db.RoomUploadTaskStore
import me.piyaphat.kei.data.impl.network.RetrofitUploadRemote
import me.piyaphat.kei.work.impl.UploadNotificationHelper
import me.piyaphat.kei.work.impl.WorkUploadScheduler
import me.piyaphat.kei.core.api.FolderStructureDetector
import me.piyaphat.kei.core.api.PageArchiveReader
import me.piyaphat.kei.core.api.FileHasher
import me.piyaphat.kei.core.api.UploadBatchPlanner
import me.piyaphat.kei.core.api.ProgressMerger
import me.piyaphat.kei.core.impl.DefaultFolderStructureDetector
import me.piyaphat.kei.core.impl.LastWriteWinsProgressMerger
import me.piyaphat.kei.core.impl.ZipPageArchiveReader
import me.piyaphat.kei.core.impl.Sha256FileHasher
import me.piyaphat.kei.core.impl.SizeAndCountBatchPlanner
import androidx.work.WorkManager
import javax.inject.Singleton
import okio.Path.Companion.toPath

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {
    @Binds @Singleton abstract fun serverRepository(impl: ServerRepositoryImpl): ServerRepository
    @Binds @Singleton abstract fun eventStream(impl: EventStreamImpl): EventStream
    @Binds @Singleton abstract fun libraryRepository(impl: LibraryRepositoryImpl): LibraryRepository
    @Binds @Singleton abstract fun readerRepository(impl: ReaderRepositoryImpl): ReaderRepository
    @Binds @Singleton abstract fun deviceRepository(impl: ReaderRepositoryImpl): DeviceRepository
    @Binds @Singleton abstract fun readerPrefs(impl: ReaderPrefsImpl): ReaderPrefs
    @Binds @Singleton abstract fun serverDiscovery(impl: NsdServerDiscovery): ServerDiscovery
    @Binds @Singleton abstract fun uploadScheduler(impl: WorkUploadScheduler): UploadScheduler
    @Binds @Singleton abstract fun uploadTaskStore(impl: RoomUploadTaskStore): UploadTaskStore
    @Binds @Singleton abstract fun uploadTaskRepository(impl: RoomUploadTaskStore): UploadTaskRepository
    @Binds @Singleton abstract fun uploadSource(impl: AndroidUploadSource): UploadSource
    @Binds @Singleton abstract fun uploadCleanup(impl: AndroidUploadSource): UploadCleanup
    @Binds @Singleton abstract fun uploadRemote(impl: RetrofitUploadRemote): UploadRemote
}

@Module
@InstallIn(SingletonComponent::class)
object StorageModule {
    @Provides @Singleton
    fun sseParser(): SseEventParser = LineBasedSseEventParser()
    @Provides @Singleton fun keiService(api: ApiFactory): KeiService = api.service

    @Provides @Singleton
    fun database(@ApplicationContext context: Context): KeiDatabase =
        Room.databaseBuilder(context, KeiDatabase::class.java, "kei.db")
            .addMigrations(KeiDatabase.MIGRATION_1_2).build()

    @Provides @Singleton fun folderDetector(): FolderStructureDetector = DefaultFolderStructureDetector()
    @Provides @Singleton fun archiveReader(): PageArchiveReader = ZipPageArchiveReader()
    @Provides @Singleton fun fileHasher(): FileHasher = Sha256FileHasher()
    @Provides @Singleton fun uploadBatchPlanner(): UploadBatchPlanner = SizeAndCountBatchPlanner()
    @Provides @Singleton fun progressMerger(): ProgressMerger = LastWriteWinsProgressMerger()
    @Provides @Singleton fun uploadNotifier(@ApplicationContext context: Context): UploadNotifier =
        UploadNotificationHelper(context, WorkManager.getInstance(context))

    @Provides @Singleton
    fun imageLoader(@ApplicationContext context: Context, api: ApiFactory): ImageLoader =
        ImageLoader.Builder(context).components {
            add(OkHttpNetworkFetcherFactory(callFactory = { api.client }))
        }.memoryCache {
            MemoryCache.Builder().maxSizePercent(context, 0.25).build()
        }.diskCache {
            DiskCache.Builder().directory(context.cacheDir.resolve("images").absolutePath.toPath())
                .maxSizeBytes(256L * 1024 * 1024).build()
        }.build()
}
