/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.http.client.auth;

import com.artipie.asto.Content;
import com.artipie.http.Headers;
import com.artipie.http.ResponseBuilder;
import com.artipie.http.headers.Authorization;
import com.artipie.http.headers.Header;
import com.artipie.http.rq.RequestLine;
import com.artipie.http.rq.RqMethod;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tests for {@link AuthClientSlice}.
 *
 * @since 0.3
 */
final class AuthClientSliceTest {

    @Test
    void shouldAuthenticateFirstRequestWithEmptyHeadersFirst() {
        final FakeAuthenticator fake = new FakeAuthenticator(Headers.EMPTY);
        new AuthClientSlice(
            (line, headers, body) -> ResponseBuilder.ok().completedFuture(), fake
        ).response(
            new RequestLine(RqMethod.GET, "/"),
            Headers.from("X-Header", "The Value"),
            Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            fake.capture(0),
            new IsEqual<>(Headers.EMPTY)
        );
    }

    @Test
    void shouldAuthenticateOnceIfNotUnauthorized() {
        final AtomicReference<Headers> capture = new AtomicReference<>();
        final Header original = new Header("Original", "Value");
        final Authorization.Basic auth = new Authorization.Basic("me", "pass");
        new AuthClientSlice(
            (line, headers, body) -> {
                Headers aa = headers.copy();
                capture.set(aa);
                return ResponseBuilder.ok().completedFuture();
            },
            new FakeAuthenticator(Headers.from(auth))
        ).response(
            new RequestLine(RqMethod.GET, "/resource"),
            Headers.from(original),
            Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            capture.get(),
            Matchers.containsInAnyOrder(original, auth)
        );
    }

    @Test
    void shouldAuthenticateWithHeadersIfUnauthorized() {
        final Header rsheader = new Header("Abc", "Def");
        final FakeAuthenticator fake = new FakeAuthenticator(Headers.EMPTY, Headers.EMPTY);
        new AuthClientSlice(
            (line, headers, body) ->
                ResponseBuilder.unauthorized().header(rsheader).completedFuture(), fake
        ).response(
            new RequestLine(RqMethod.GET, "/foo/bar"),
            Headers.EMPTY, Content.EMPTY).join();
        MatcherAssert.assertThat(
            fake.capture(1),
            Matchers.containsInAnyOrder(rsheader)
        );
    }

    @Test
    void shouldAuthenticateOnceIfUnauthorizedButAnonymous() {
        final AtomicInteger capture = new AtomicInteger();
        new AuthClientSlice(
            (line, headers, body) -> {
                capture.incrementAndGet();
                return ResponseBuilder.unauthorized().completedFuture();
            },
            Authenticator.ANONYMOUS
        ).response(
            new RequestLine(RqMethod.GET, "/secret/resource"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            capture.get(),
            new IsEqual<>(1)
        );
    }

    @Test
    void shouldAuthenticateTwiceIfNotUnauthorized() {
        final AtomicReference<Headers> capture = new AtomicReference<>();
        final Header original = new Header("RequestHeader", "Original Value");
        final Authorization.Basic auth = new Authorization.Basic("user", "password");
        new AuthClientSlice(
            (line, headers, body) -> {
                capture.set(headers);
                return ResponseBuilder.unauthorized().completedFuture();
            },
            new FakeAuthenticator(Headers.EMPTY, Headers.from(auth))
        ).response(
            new RequestLine(RqMethod.GET, "/top/secret"),
            Headers.from(original),
            Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            capture.get(),
            Matchers.containsInAnyOrder(original, auth)
        );
    }

    @Test
    void shouldPassRequestForBothAttempts() {
        final Headers auth = Headers.from("some", "header");
        final byte[] request = "request".getBytes();
        final AtomicReference<List<byte[]>> capture = new AtomicReference<>(new ArrayList<>(0));
        new AuthClientSlice(
            (line, headers, body) ->
                new Content.From(body).asBytesFuture().thenApply(
                    bytes -> {
                        capture.get().add(bytes);
                        return ResponseBuilder.unauthorized().build();
                    }
            ),
            new FakeAuthenticator(auth, auth)
        ).response(
            new RequestLine(RqMethod.GET, "/api"),
            Headers.EMPTY,
            new Content.OneTime(new Content.From(request))
        ).join();
        MatcherAssert.assertThat(
            "Body sent in first request",
            capture.get().get(0),
            new IsEqual<>(request)
        );
        MatcherAssert.assertThat(
            "Body sent in second request",
            capture.get().get(1),
            new IsEqual<>(request)
        );
    }

    /**
     * Fake authenticator providing specified results
     * and capturing `authenticate()` method arguments.
     *
     * @since 0.3
     */
    private static final class FakeAuthenticator implements Authenticator {

        /**
         * Results `authenticate()` method should return by number of invocation.
         */
        private final List<Headers> results;

        /**
         * Captured `authenticate()` method arguments by number of invocation..
         */
        private final AtomicReference<List<Headers>> captures;

        private FakeAuthenticator(final Headers... results) {
            this(Arrays.asList(results));
        }

        private FakeAuthenticator(final List<Headers> results) {
            this.results = results;
            this.captures = new AtomicReference<>(Collections.emptyList());
        }

        public Headers capture(final int index) {
            return this.captures.get().get(index);
        }

        @Override
        public CompletionStage<Headers> authenticate(final Headers headers) {
            final List<Headers> prev = this.captures.get();
            final List<Headers> updated = new ArrayList<>(prev);
            updated.add(headers);
            this.captures.set(updated);
            return CompletableFuture.completedFuture(this.results.get(prev.size()));
        }
    }
}
