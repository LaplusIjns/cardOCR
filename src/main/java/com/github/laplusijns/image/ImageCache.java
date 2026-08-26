package com.github.laplusijns.image;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
public class ImageCache {
    private final Cache<String, byte[]> fileCache;
    private final Cache<String, byte[]> thumbNailCache;

    public ImageCache() {
        this.fileCache = Caffeine.newBuilder()
                .expireAfterWrite(24, TimeUnit.HOURS)
                .maximumSize(Integer.MAX_VALUE)
                .build();
        this.thumbNailCache = Caffeine.newBuilder()
                .expireAfterWrite(24, TimeUnit.HOURS)
                .maximumSize(Integer.MAX_VALUE)
                .build();
        super();
    }

    public void put(final String uuid, final byte[] bs) {
        fileCache.put(uuid, bs);
    }

    public @Nullable byte[] get(final String uuid) {
        return fileCache.getIfPresent(uuid);
    }

    public @Nullable byte[] getThumbnail(final String uuid) {
        return thumbNailCache.getIfPresent(uuid);
    }

    public void delete(final String uuid) {
        fileCache.invalidate(uuid);
        thumbNailCache.invalidate(uuid);
    }

    public void putThumbnail(final String uuid, final byte[] resizeBytes) {
        thumbNailCache.put(uuid, resizeBytes);
    }
}
