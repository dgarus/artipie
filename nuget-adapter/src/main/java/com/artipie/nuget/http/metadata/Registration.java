/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.nuget.http.metadata;

import com.artipie.asto.Content;
import com.artipie.http.Headers;
import com.artipie.http.ResponseBuilder;
import com.artipie.http.Response;
import com.artipie.nuget.PackageKeys;
import com.artipie.nuget.Repository;
import com.artipie.nuget.Versions;
import com.artipie.nuget.http.Resource;
import com.artipie.nuget.metadata.NuspecField;

import javax.json.Json;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;
import javax.json.JsonWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Registration resource.
 * See <a href="https://docs.microsoft.com/en-us/nuget/api/registration-base-url-resource#registration-pages-and-leaves">Registration pages and leaves</a>
 */
class Registration implements Resource {

    /**
     * Repository to read data from.
     */
    private final Repository repository;

    /**
     * Package content location.
     */
    private final ContentLocation content;

    /**
     * Package identifier.
     */
    private final NuspecField id;

    /**
     * @param repository Repository to read data from.
     * @param content Package content location.
     * @param id Package identifier.
     */
    Registration(Repository repository, ContentLocation content, NuspecField id) {
        this.repository = repository;
        this.content = content;
        this.id = id;
    }

    @Override
    public CompletableFuture<Response> get(final Headers headers) {
        return this.pages()
            .thenCompose(
                pages -> new CompletionStages<>(pages.stream().map(RegistrationPage::json)).all()
            ).thenApply(
                pages -> {
                    final JsonArrayBuilder items = Json.createArrayBuilder();
                    for (final JsonObject page : pages) {
                        items.add(page);
                    }
                    final JsonObject json = Json.createObjectBuilder()
                        .add("count", pages.size())
                        .add("items", items)
                        .build();
                    try (ByteArrayOutputStream out = new ByteArrayOutputStream();
                         JsonWriter writer = Json.createWriter(out)) {
                        writer.writeObject(json);
                        out.flush();
                        return ResponseBuilder.ok()
                            .body(out.toByteArray())
                            .build();
                    } catch (final IOException ex) {
                        throw new UncheckedIOException(ex);
                    }
                }
            ).toCompletableFuture();
    }

    @Override
    public CompletableFuture<Response> put(Headers headers, Content body) {
        return ResponseBuilder.methodNotAllowed().completedFuture();
    }

    /**
     * Enumerate version pages.
     *
     * @return List of pages.
     */
    private CompletionStage<List<RegistrationPage>> pages() {
        return this.repository.versions(new PackageKeys(this.id)).thenApply(Versions::all)
            .thenApply(
                versions -> {
                    if (versions.isEmpty()) {
                        return Collections.emptyList();
                    }
                    return Collections.singletonList(
                        new RegistrationPage(this.repository, this.content, this.id, versions)
                    );
                }
            );
    }
}
