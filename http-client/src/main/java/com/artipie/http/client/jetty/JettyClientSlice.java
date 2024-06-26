/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.http.client.jetty;

import com.artipie.http.Headers;
import com.artipie.http.ResponseBuilder;
import com.artipie.http.Response;
import com.artipie.http.Slice;
import com.artipie.http.headers.Header;
import com.artipie.http.rq.RequestLine;
import com.artipie.http.rq.RqMethod;
import com.artipie.http.RsStatus;
import io.reactivex.Flowable;
import org.apache.hc.core5.net.URIBuilder;
import org.eclipse.jetty.client.AsyncRequestContent;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * ClientSlices implementation using Jetty HTTP client as back-end.
 * <a href="https://eclipse.dev/jetty/documentation/jetty-12/programming-guide/index.html#pg-client-http-non-blocking">Docs</a>
 */
final class JettyClientSlice implements Slice {

    private static final Logger LOGGER = LoggerFactory.getLogger(JettyClientSlice.class);

    /**
     * HTTP client.
     */
    private final HttpClient client;

    /**
     * Secure connection flag.
     */
    private final boolean secure;

    /**
     * Host name.
     */
    private final String host;

    /**
     * Port.
     */
    private final int port;

    /**
     * @param client HTTP client.
     * @param secure Secure connection flag.
     * @param host Host name.
     * @param port Port.
     */
    JettyClientSlice(HttpClient client, boolean secure, String host, int port) {
        this.client = client;
        this.secure = secure;
        this.host = host;
        this.port = port;
    }

    public CompletableFuture<Response> response(
        RequestLine line, Headers headers, com.artipie.asto.Content body
    ) {
        final Request request = this.buildRequest(headers, line);
        final CompletableFuture<Response> res = new CompletableFuture<>();
        final List<Content.Chunk> buffers = new LinkedList<>();
        if (line.method() != RqMethod.HEAD) {
            final AsyncRequestContent async = new AsyncRequestContent();
            Flowable.fromPublisher(body).doOnComplete(async::close).forEach(
                buf -> async.write(buf, Callback.NOOP)
            );
            request.body(async);
        }
        request.onResponseContentSource(
                (response, source) -> {
                    // The function (as a Runnable) that reads the response content.
                    final Runnable demander = new Demander(source, response, buffers);
                    // Initiate the reads.
                    demander.run();
                }
        );
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Send {} {}:{}{} {} \n{}",
                request.getMethod(), request.getHost(), request.getPort(), request.getPath(), request.getVersion(),
                request.getHeaders().asString()
            );
        }
        request.send(
                result -> {
                    if (result.getFailure() == null) {
                        RsStatus status = RsStatus.byCode(result.getResponse().getStatus());
                        Flowable<ByteBuffer> content = Flowable.fromIterable(buffers)
                            .map(chunk -> {
                                    final ByteBuffer item = chunk.getByteBuffer();
                                    chunk.release();
                                    return item;
                                }
                            );
                        if (LOGGER.isDebugEnabled()) {
                            LOGGER.debug("Got {}\n{}",
                                    result.getResponse(), result.getResponse().getHeaders().asString());
                        }
                        res.complete(
                            ResponseBuilder.from(status)
                                .headers(toHeaders(result.getResponse().getHeaders()))
                                .body(content)
                                .build()
                        );
                    } else {
                        LOGGER.error("Got failure", result.getFailure());
                        res.completeExceptionally(result.getFailure());
                    }
                }
        );
        return res;
    }

    private Headers toHeaders(HttpFields fields) {
        return new Headers(
            fields.stream()
                .map(field -> new Header(field.getName(), field.getValue()))
                .toList()
        );
    }

    /**
     * Builds jetty basic request from artipie request line and headers.
     * @param headers Headers
     * @param req Artipie request line
     * @return Jetty request
     */
    private Request buildRequest(Headers headers, RequestLine req) {
        final String scheme = this.secure ? "https" : "http";
        final URI uri = req.uri();
        final Request request = this.client.newRequest(
            new URIBuilder()
                .setScheme(scheme)
                .setHost(this.host)
                .setPort(this.port)
                .setPath(uri.getPath())
                .setCustomQuery(uri.getQuery())
                .toString()
        ).method(req.method().value());
        for (Header header : headers) {
            request.headers(mutable -> mutable.add(header.getKey(), header.getValue()));
        }
        return request;
    }

    /**
     * Demander.This class reads response content from request asynchronously piece by piece.
     * See <a href="https://eclipse.dev/jetty/documentation/jetty-12/programming-guide/index.html#pg-client-http-content-response">jetty docs</a>
     * for more details.
     * @since 0.3
     */
    @SuppressWarnings({"PMD.OnlyOneReturn", "PMD.CognitiveComplexity"})
    private static final class Demander implements Runnable {

        /**
         * Content source.
         */
        private final Content.Source source;

        /**
         * Response.
         */
        private final org.eclipse.jetty.client.Response response;

        /**
         * Content chunks.
         */
        private final List<Content.Chunk> chunks;

        /**
         * Ctor.
         * @param source Content source
         * @param response Response
         * @param chunks Content chunks for further process
         */
        private Demander(
            final Content.Source source,
            final org.eclipse.jetty.client.Response response,
            final List<Content.Chunk> chunks
        ) {
            this.source = source;
            this.response = response;
            this.chunks = chunks;
        }

        @Override
        public void run() {
            while (true) {
                final Content.Chunk chunk = this.source.read();
                if (chunk == null) {
                    this.source.demand(this);
                    return;
                }
                if (Content.Chunk.isFailure(chunk)) {
                    final Throwable failure = chunk.getFailure();
                    if (chunk.isLast()) {
                        this.response.abort(failure);
                        LOGGER.error(failure.getMessage());
                        return;
                    } else {
                        // A transient failure such as a read timeout.
                        if (RsStatus.byCode(this.response.getStatus()).success()) {
                            // Try to read again.
                            continue;
                        } else {
                            // The transient failure is treated as a terminal failure.
                            this.response.abort(failure);
                            LOGGER.error(failure.getMessage());
                            return;
                        }
                    }
                }
                chunk.retain();
                this.chunks.add(chunk);
                if (chunk.isLast()) {
                    return;
                }
            }
        }
    }
}
