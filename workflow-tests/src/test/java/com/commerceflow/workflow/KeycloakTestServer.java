/*
 * Owns the real test Keycloak lifecycle and realm setup so application tests do not implement an
 * identity provider.
 */
package com.commerceflow.workflow;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;

final class KeycloakTestServer implements AutoCloseable {

    static final String SECRET = "local-test-service-only";
    private Process process;
    private GenericContainer<?> container;
    private String url;

    static KeycloakTestServer start(EmbeddedPostgres postgres) throws Exception {
        return start(postgres, 0);
    }

    static KeycloakTestServer start(EmbeddedPostgres postgres, int fixedPort) throws Exception {
        var server = new KeycloakTestServer();
        Path home = Path.of(System.getenv()
                .getOrDefault("KEYCLOAK_HOME", System.getProperty("keycloak.home.path", "")));
        Path realm = Path.of(System.getProperty("commerceflow.root"))
                .resolve("infra/keycloak/commerceflow-realm.json");
        if (Files.isExecutable(home.resolve("bin/kc.sh"))) {
            int port = fixedPort;
            if (port == 0)
                try (var socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
                    port = socket.getLocalPort();
                }
            Files.createDirectories(home.resolve("data/import"));
            Files.copy(
                    realm,
                    home.resolve("data/import/commerceflow-realm.json"),
                    StandardCopyOption.REPLACE_EXISTING);
            var builder = new ProcessBuilder(
                    home.resolve("bin/kc.sh").toString(),
                    "start-dev",
                    "--import-realm",
                    "--db=postgres",
                    "--db-url=" + postgres.getJdbcUrl("cf_keycloak", "cf_keycloak"),
                    "--db-username=cf_keycloak",
                    "--db-password=test-only-keycloak",
                    "--http-host=127.0.0.1",
                    "--http-port=" + port,
                    "--cache=local");
            builder.environment().put("SERVICE_CLIENT_SECRET", SECRET);
            if (fixedPort != 0)
                builder.environment().put("KC_HOSTNAME", "http://localhost:" + port);
            builder.environment().put("JAVA_OPTS_APPEND", "-Xms128m -Xmx512m");
            server.process = builder
                    .redirectErrorStream(true)
                    .redirectOutput(Files.createTempFile("commerceflow-keycloak-", ".log").toFile())
                    .start();
            server.url = "http://" + (fixedPort == 0 ? "127.0.0.1" : "localhost") + ":" + port;
        } else {
            Testcontainers.exposeHostPorts(postgres.getPort());
            server.container = new GenericContainer<>(DockerImageName.parse("quay.io/keycloak/keycloak:26.8.1"))
                    .withExposedPorts(8080)
                    .withCommand("start-dev", "--import-realm", "--cache=local")
                    .withEnv("KC_DB", "postgres")
                    .withEnv("KC_DB_URL",
                            "jdbc:postgresql://host.testcontainers.internal:"
                                    + postgres.getPort()
                                    + "/cf_keycloak")
                    .withEnv("KC_DB_USERNAME", "cf_keycloak")
                    .withEnv("KC_DB_PASSWORD", "test-only-keycloak")
                    .withEnv("SERVICE_CLIENT_SECRET", SECRET)
                    .withCopyFileToContainer(
                            MountableFile.forHostPath(realm),
                            "/opt/keycloak/data/import/commerceflow-realm.json")
                    .waitingFor(
                            Wait.forHttp("/realms/commerceflow/.well-known/openid-configuration")
                                    .forPort(8080)
                                    .forStatusCode(200));
            server.container.start();
            server.url = "http://" + server.container.getHost() + ":" + server.container.getMappedPort(8080);
        }
        var http = HttpClient.newHttpClient();
        await().atMost(Duration.ofSeconds(90))
                .pollInterval(Duration.ofMillis(500))
                .until(() -> {
                    try {
                        return http.send(HttpRequest.newBuilder(
                                                        URI.create(server.issuer() + "/.well-known/openid-configuration"))
                                                .timeout(Duration.ofSeconds(2))
                                                .build(),
                                        HttpResponse.BodyHandlers.discarding())
                                .statusCode() == 200;
                    } catch (Exception error) {
                        return false;
                    }
                });
        return server;
    }

    String issuer() {
        return url + "/realms/commerceflow";
    }

    @Override
    public void close() throws Exception {
        if (process != null) {
            // Stop only this fixture-owned process, then force termination if its graceful shutdown stalls.
            process.destroy();
            if (!process.waitFor(10, TimeUnit.SECONDS))
                process.destroyForcibly().waitFor();
        }
        if (container != null)
            container.close();
    }
}
