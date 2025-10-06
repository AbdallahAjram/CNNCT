package com.cnnct.chat.player

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import java.io.File

@UnstableApi
object MediaCache {

    // ~200MB LRU cache for videos
    private const val MAX_CACHE_BYTES: Long = 200L * 1024L * 1024L

    @Volatile private var cache: Cache? = null

    private fun cache(context: Context): Cache {
        return cache ?: synchronized(this) {
            cache ?: run {
                val dir = File(context.cacheDir, "video_cache")
                SimpleCache(
                    dir,
                    LeastRecentlyUsedCacheEvictor(MAX_CACHE_BYTES)
                ).also { cache = it }
            }
        }
    }

    fun dataSourceFactory(context: Context): CacheDataSource.Factory {
        val upstream = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setTransferListener(DefaultBandwidthMeter.getSingletonInstance(context))

        return CacheDataSource.Factory()
            .setCache(cache(context))
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }
}
