import java.net.URI;
import java.net.http.*;
import java.time.Duration;

public class HealthCheck {

    public static void main(String[] args) throws Exception {
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        var response = client.send(HttpRequest.newBuilder(URI.create(args[0])).timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() != 200) System.exit(1);
    }
}
