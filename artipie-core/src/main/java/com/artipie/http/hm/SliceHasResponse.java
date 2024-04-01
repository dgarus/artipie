/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.http.hm;

import com.artipie.asto.Content;
import com.artipie.http.Headers;
import com.artipie.http.Response;
import com.artipie.http.ResponseImpl;
import com.artipie.http.Slice;
import com.artipie.http.rq.RequestLine;
import io.reactivex.Flowable;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeMatcher;

import java.util.function.Function;

/**
 * Matcher for {@link Slice} response.
 * @since 0.16
 */
public final class SliceHasResponse extends TypeSafeMatcher<Slice> {

    /**
     * Response matcher.
     */
    private final Matcher<? extends Response> rsp;

    /**
     * Function to get response from slice.
     */
    private final Function<? super Slice, ? extends ResponseImpl> responser;

    /**
     * Response cache.
     */
    private ResponseImpl response;

    /**
     * New response matcher for slice with request line.
     * @param rsp Response matcher
     * @param line Request line
     */
    public SliceHasResponse(final Matcher<? extends ResponseImpl> rsp, final RequestLine line) {
        this(rsp, line, Headers.EMPTY, new Content.From(Flowable.empty()));
    }

    /**
     * New response matcher for slice with request line.
     *
     * @param rsp Response matcher
     * @param headers Headers
     * @param line Request line
     */
    public SliceHasResponse(Matcher<? extends ResponseImpl> rsp, Headers headers, RequestLine line) {
        this(rsp, line, headers, new Content.From(Flowable.empty()));
    }

    /**
     * New response matcher for slice with request line, headers and body.
     * @param rsp Response matcher
     * @param line Request line
     * @param headers Headers
     * @param body Body
     */
    public SliceHasResponse(
        Matcher<? extends ResponseImpl> rsp,
        RequestLine line,
        Headers headers,
        Content body
    ) {
        this.rsp = rsp;
        this.responser = slice -> slice.response(line, headers, body).join();
    }

    @Override
    public boolean matchesSafely(final Slice item) {
        return this.rsp.matches(this.response(item));
    }

    @Override
    public void describeTo(final Description description) {
        description.appendText("response: ").appendDescriptionOf(this.rsp);
    }

    @Override
    public void describeMismatchSafely(final Slice item, final Description description) {
        description.appendText("response was: ").appendValue(this.response(item));
    }

    /**
     * Response for slice.
     * @param slice Target slice
     * @return Cached response
     */
    private ResponseImpl response(final Slice slice) {
        if (this.response == null) {
            this.response = this.responser.apply(slice);
        }
        return this.response;
    }
}
