/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.conda.http;

import com.artipie.asto.Content;
import com.artipie.asto.Key;
import com.artipie.asto.Storage;
import com.artipie.asto.ext.KeyLastPart;
import com.artipie.http.Headers;
import com.artipie.http.ResponseBuilder;
import com.artipie.http.Response;
import com.artipie.http.Slice;
import com.artipie.http.headers.ContentFileName;
import com.artipie.http.rq.RequestLine;

import javax.json.Json;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Slice to download repodata.json. If the repodata item does not exists in storage, empty
 * json is returned.
 */
public final class DownloadRepodataSlice implements Slice {

    /**
     * Request path pattern.
     */
    private static final Pattern RQ_PATH = Pattern.compile(".*/((.+)/(current_)?repodata\\.json)");

    private final Storage asto;

    /**
     * @param asto Abstract storage
     */
    public DownloadRepodataSlice(final Storage asto) {
        this.asto = asto;
    }

    @Override
    public CompletableFuture<Response> response(
        RequestLine line, Headers headers, Content body) {
        String path = line.uri().getPath();

        final Matcher matcher = DownloadRepodataSlice.RQ_PATH.matcher(path);
        if (matcher.matches()) {
            final Key key = new Key.From(matcher.group(1));
            return this.asto.exists(key).thenCompose(
                exist -> {
                    if (exist) {
                        return this.asto.value(key);
                    }
                    return CompletableFuture.completedFuture(
                        new Content.From(
                            Json.createObjectBuilder().add(
                                    "info", Json.createObjectBuilder()
                                        .add("subdir", matcher.group(2))
                                ).build().toString()
                                .getBytes(StandardCharsets.US_ASCII)
                        )
                    );
                }
            ).thenApply(
                content -> ResponseBuilder.ok()
                    .header(new ContentFileName(new KeyLastPart(key).get()))
                    .body(content)
                    .build()
            );
        }
        return ResponseBuilder.badRequest().completedFuture();
    }
}
