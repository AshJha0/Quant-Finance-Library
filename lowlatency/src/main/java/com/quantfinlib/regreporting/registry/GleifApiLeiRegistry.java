package com.quantfinlib.regreporting.registry;

import com.quantfinlib.regreporting.ReportingIdentifiers;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Online LEI lookup against the public GLEIF API
 * ({@code GET https://api.gleif.org/api/v1/lei-records/{lei}}, JSON:API, no key needed).
 *
 * <p>Uses {@link java.net.http.HttpClient} with a connect timeout and a per-request timeout
 * and no JSON library: it extracts {@code entity.legalName.name} and
 * {@code registration.status} with two narrow patterns. A 404 means not found; any other
 * non-200 status or an I/O failure throws {@link UncheckedIOException}. A malformed LEI is
 * rejected locally without a call. Since 3.0.0 {@link #validityOn} also extracts
 * {@code registration.initialRegistrationDate}, {@code registration.lastUpdateDate} and
 * the entity status for the ESMA LEI rules. The field names were checked against a real
 * API record (LEI 506700GE1G29325QX363, kept as a test fixture; published reference). The live call is covered by a test that runs only with
 * {@code QFL_NETWORK_TESTS=1}; parsing is covered offline by a local HTTP server test.</p>
 */
public final class GleifApiLeiRegistry implements LeiRegistry {
    /** Default API base. */
    public static final URI DEFAULT_BASE = URI.create("https://api.gleif.org/api/v1/lei-records/");

    private static final Pattern NAME = Pattern.compile(
            "\"legalName\"\\s*:\\s*\\{\\s*\"name\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern STATUS = Pattern.compile(
            "\"registration\"\\s*:\\s*\\{[^}]*?\"status\"\\s*:\\s*\"([A-Z_]+)\"");

    private static final Pattern INITIAL = Pattern.compile(
            "\"registration\"\\s*:\\s*\\{[^}]*?\"initialRegistrationDate\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern UPDATED = Pattern.compile(
            "\"registration\"\\s*:\\s*\\{[^}]*?\"lastUpdateDate\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern ENTITY_STATUS = Pattern.compile(
            "\"status\"\\s*:\\s*\"(ACTIVE|INACTIVE)\"");

    private final HttpClient client;
    private final URI base;
    private final Duration requestTimeout;

    /**
     * Creates a client with the given timeouts against {@link #DEFAULT_BASE}.
     *
     * @param connectTimeout connect timeout
     * @param requestTimeout per-request timeout
     */
    public GleifApiLeiRegistry(Duration connectTimeout, Duration requestTimeout) {
        this(HttpClient.newBuilder().connectTimeout(connectTimeout).build(), DEFAULT_BASE, requestTimeout);
    }

    /**
     * Creates a client with an explicit HTTP client and base URI (a mirror or a test server).
     *
     * @param client the HTTP client
     * @param base base URI ending in {@code /}
     * @param requestTimeout per-request timeout
     */
    public GleifApiLeiRegistry(HttpClient client, URI base, Duration requestTimeout) {
        this.client = Objects.requireNonNull(client, "client");
        this.base = Objects.requireNonNull(base, "base");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
    }

    @Override
    public Optional<LeiRecord> lookup(String lei) {
        return fetch(lei).map(body -> parse(lei, body));
    }

    /** One API call; the dates and entity status come from the same JSON body as the status. */
    @Override
    public Optional<LeiValidity> validityOn(String lei, LocalDate date) {
        return fetch(lei).map(body -> parseValidity(lei, body));
    }

    private Optional<String> fetch(String lei) {
        if (!ReportingIdentifiers.isValidLei(lei)) {
            return Optional.empty();
        }
        HttpRequest req = HttpRequest.newBuilder(base.resolve(lei))
                .timeout(requestTimeout)
                .header("Accept", "application/vnd.api+json")
                .GET().build();
        HttpResponse<String> resp;
        try {
            resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UncheckedIOException(new IOException("interrupted", e));
        }
        if (resp.statusCode() == 404) {
            return Optional.empty();
        }
        if (resp.statusCode() != 200) {
            throw new UncheckedIOException(new IOException("GLEIF API HTTP " + resp.statusCode()));
        }
        return Optional.of(resp.body());
    }

    /** Extracts the record from a GLEIF API JSON body. Package-private for tests. */
    static LeiRecord parse(String lei, String json) {
        Matcher n = NAME.matcher(json);
        Matcher s = STATUS.matcher(json);
        String name = n.find() ? n.group(1).replace("\\\"", "\"").replace("\\\\", "\\") : "";
        LeiStatus st = s.find() ? LeiStatus.parse(s.group(1)) : LeiStatus.UNKNOWN;
        return new LeiRecord(lei, name, st);
    }

    /**
     * Extracts status, {@code registration.initialRegistrationDate},
     * {@code registration.lastUpdateDate} and the entity status ({@code ACTIVE} or
     * {@code INACTIVE}; only INACTIVE marks the entity inactive). Package-private for tests.
     */
    static LeiValidity parseValidity(String lei, String json) {
        LeiStatus st = parse(lei, json).status();
        Matcher e = ENTITY_STATUS.matcher(json);
        boolean active = !(e.find() && "INACTIVE".equals(e.group(1)));
        return new LeiValidity(st, date(INITIAL, json), active, date(UPDATED, json));
    }

    private static @Nullable LocalDate date(Pattern p, String json) {
        Matcher m = p.matcher(json);
        if (!m.find()) {
            return null;
        }
        try {
            return Csv.date(m.group(1));
        } catch (IOException ex) {
            return null;
        }
    }
}
