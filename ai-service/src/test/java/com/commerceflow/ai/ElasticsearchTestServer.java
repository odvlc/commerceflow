/*
 * Manages a real isolated Elasticsearch test process; it is test lifecycle infrastructure, not an
 * in-memory search substitute.
 */
package com.commerceflow.ai;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;

final class ElasticsearchTestServer implements AutoCloseable {

    private Process process;
    private GenericContainer<?> container;
    private String url;

    static ElasticsearchTestServer start() throws Exception {
        var server = new ElasticsearchTestServer();
        String configured = System.getenv("ELASTICSEARCH_HOME");
        Path home = Path.of(configured == null ? System.getProperty("elasticsearch.home.path", "") : configured);
        if (Files.isExecutable(home.resolve("bin/elasticsearch"))) {
            int port;
            try (var socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
                port = socket.getLocalPort();
            }
            var directory = Files.createTempDirectory("commerceflow-search-");
            var builder = new ProcessBuilder(
                    home.resolve("bin/elasticsearch").toString(),
                    "-Ediscovery.type=single-node",
                    "-Expack.security.enabled=false",
                    "-Expack.ml.enabled=false",
                    "-Enetwork.host=127.0.0.1",
                    "-Ehttp.port=" + port,
                    "-Etransport.port=0",
                    "-Epath.data=" + directory.resolve("data"),
                    "-Epath.logs=" + directory.resolve("logs"));
            builder.environment().put("ES_JAVA_OPTS", "-Xms512m -Xmx512m");
            server.process = builder.redirectErrorStream(true)
                    .redirectOutput(directory.resolve("console.log").toFile())
                    .start();
            server.url = "http://127.0.0.1:" + port;
        } else {
            server.container = new GenericContainer<>(
                    DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:9.4.5"))
                    .withExposedPorts(9200)
                    .withEnv("discovery.type", "single-node")
                    .withEnv("xpack.security.enabled", "false")
                    .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
                    .waitingFor(Wait.forHttp("/_cluster/health").forPort(9200).forStatusCode(200));
            server.container.start();
            server.url = "http://" + server.container.getHost() + ":" + server.container.getMappedPort(9200);
        }
        var http = HttpClient.newHttpClient();
        await().atMost(Duration.ofSeconds(60))
                .pollInterval(Duration.ofMillis(500))
                .until(() -> {
                    try {
                        return http.send(HttpRequest.newBuilder(
                                                        URI.create(server.url + "/_cluster/health?wait_for_status=yellow&timeout=1s"))
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

    String url() {
        return url;
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
