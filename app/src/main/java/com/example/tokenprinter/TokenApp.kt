package com.example.tokenprinter

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import okhttp3.OkHttpClient

/** Gives image loading a proper User-Agent (Scryfall asks for one) and a generous on-disk cache. */
class TokenApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        Fonts.init(this)
    }

    override fun newImageLoader(): ImageLoader {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", Scryfall.UA)
                        .header("Accept", "image/*,*/*;q=0.8")
                        .build()
                )
            }
            .build()
        return ImageLoader.Builder(this)
            .okHttpClient(client)
            .crossfade(true)
            .respectCacheHeaders(false)
            .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.25).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("images"))
                    .maxSizeBytes(300L * 1024 * 1024)
                    .build()
            }
            .build()
    }
}
