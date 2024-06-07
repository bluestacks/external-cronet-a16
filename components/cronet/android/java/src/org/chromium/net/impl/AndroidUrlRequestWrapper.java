// Copyright 2023 The Chromium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.net.impl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import static org.chromium.net.impl.HttpEngineNativeProvider.EXT_API_LEVEL;
import static org.chromium.net.impl.HttpEngineNativeProvider.EXT_VERSION;

import androidx.annotation.RequiresExtension;

import org.chromium.net.UrlResponseInfo;

import java.nio.ByteBuffer;

@RequiresExtension(extension = EXT_API_LEVEL, version = EXT_VERSION)
class AndroidUrlRequestWrapper extends org.chromium.net.ExperimentalUrlRequest {
    private final android.net.http.UrlRequest mBackend;

    private AndroidUrlRequestWrapper(android.net.http.UrlRequest backend) {
        this.mBackend = backend;
    }

    @Override
    public void start() {
        mBackend.start();
    }

    @Override
    public void followRedirect() {
        mBackend.followRedirect();
    }

    @Override
    public void read(ByteBuffer buffer) {
        mBackend.read(buffer);
    }

    @Override
    public void cancel() {
        mBackend.cancel();
    }

    @Override
    public boolean isDone() {
        return mBackend.isDone();
    }

    @Override
    public void getStatus(StatusListener listener) {
        mBackend.getStatus(new AndroidUrlRequestStatusListenerWrapper(listener));
    }

    @Override
    public int getTrafficStatsUid() {
        return mBackend.getTrafficStatsUid();
    }

    @Override
    public int getPriority() {
        return mBackend.getPriority();
    }

    @Override
    public boolean hasTrafficStatsTag() {
        return mBackend.hasTrafficStatsTag();
    }

    @Override
    public boolean hasTrafficStatsUid() {
        return mBackend.hasTrafficStatsUid();
    }

    @Override
    public int getTrafficStatsTag() {
        return mBackend.getTrafficStatsTag();
    }

    @Override
    public boolean isDirectExecutorAllowed() {
        return mBackend.isDirectExecutorAllowed();
    }

    @Override
    public boolean isCacheDisabled() {
        return mBackend.isCacheDisabled();
    }

    @NonNull
    @Override
    public UrlResponseInfo.HeaderBlock getHeaders() {
        return new AndroidHeaderBlockWrapper(mBackend.getHeaders());
    }

    @Nullable
    @Override
    public String getHttpMethod() {
        return mBackend.getHttpMethod();
    }

    /**
     * Creates an {@link AndroidUrlRequestWrapper} that is recorded on the callback.
     *
     * @param backend the http UrlRequest
     * @param callback the stream's callback
     * @return the wrapped request
     */
    static AndroidUrlRequestWrapper withRecordingToCallback(
            android.net.http.UrlRequest backend, AndroidUrlRequestCallbackWrapper callback) {
        AndroidUrlRequestWrapper wrappedRequest = new AndroidUrlRequestWrapper(backend);
        callback.recordWrappedRequest(wrappedRequest);
        return wrappedRequest;
    }

    android.net.http.UrlRequest getBackend() {
        return mBackend;
    }
}
