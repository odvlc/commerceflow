package com.commerceflow.workflow;

import static org.awaitility.Awaitility.await;

import java.net.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real native Redis where supplied, otherwise an explicit Testcontainers runtime requirement.
 */
final class RedisTestServer implements AutoCloseable {

    private Process process;
    private ProcessBuilder launch;
    private GenericContainer<?> container;
    private String host = "127.0.0.1";
    private int port;

    static RedisTestServer start() throws Exception {
        var redis = new RedisTestServer();
        String configured = System.getenv("REDIS_SERVER");
        Path binary =
                Path.of(configured == null ? System.getProperty("redis.server.path", "") : configured);
        if (Files.isExecutable(binary) && !Files.isDirectory(binary)) {
            try (var socket = new java.net.ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
                redis.port = socket.getLocalPort();
            }
            var directory = Files.createTempDirectory("commerceflow-redis-");
            redis.launch =
                    new ProcessBuilder(
                            binary.toString(),
                            "--bind",
                            "127.0.0.1",
                            "--port",
                            Integer.toString(redis.port),
                            "--save",
                            "",
                            "--appendonly",
                            "yes",
                            "--dir",
                            directory.toString())
                            .redirectErrorStream(true)
                            .redirectOutput(directory.resolve("redis.log").toFile());
            redis.process=redis.launch.start();
            await()
                    .atMost(Duration.ofSeconds(10))
                    .until(
                            () -> {
                                try (var client = new Socket()) {
                                    client.connect(new InetSocketAddress(redis.host, redis.port), 100);
                                    return true;
                                } catch (java.io.IOException error) {
                                    return false;
                                }
                            });
        } else {
            redis.container =
                    new GenericContainer<>(DockerImageName.parse("redis:8.10.2-alpine"))
                            .withExposedPorts(6379);
            redis.container.start();
            redis.host = redis.container.getHost();
            redis.port = redis.container.getMappedPort(6379);
        }
        return redis;
    }

    String host() {
        return host;
    }

    int port() {
        return port;
    }

    void pause() throws Exception {
        if(process==null)throw new IllegalStateException("Fault evaluation requires the documented native Redis fixture");
        process.destroy();if(!process.waitFor(5,TimeUnit.SECONDS))process.destroyForcibly().waitFor();
    }
    void resume() throws Exception {
        if(launch==null)throw new IllegalStateException("Native Redis launch configuration is absent");
        process=launch.start();await().atMost(Duration.ofSeconds(10)).until(()->{try(var client=new Socket()){client.connect(new InetSocketAddress(host,port),100);return true;}catch(java.io.IOException e){return false;}});
    }

    @Override
    public void close() throws Exception {
        if (process != null) {
            process.destroy();
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly().waitFor();
        }
        if (container != null) container.close();
    }
}
