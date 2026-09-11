package com.csw.urlshortener;

import jakarta.persistence.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.*;

@SpringBootApplication
public class UrlShortenerApplication {
    public static void main(String[] args) {
        SpringApplication.run(UrlShortenerApplication.class, args);
    }
}

@Entity
@Table(name = "urls")
class Url {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(nullable = false, length = 2048)
    String originalUrl;

    @Column(nullable = false, unique = true, length = 16)
    String code;
}

interface UrlRepository extends JpaRepository<Url, Long> {
    Optional<Url> findByCode(String code);
}

@RestController
class UrlController {

    private static final String CHARS =
        "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private final UrlRepository repo;
    private final Random random = new Random();

    UrlController(UrlRepository repo) {
        this.repo = repo;
    }

    @PostMapping("/shorten")
    Map<String, String> shorten(@RequestBody Map<String, String> body) {
        String original = body.get("url");
        if (original == null || original.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "url is required");
        }
        Url url = new Url();
        url.originalUrl = original;
        url.code = generateCode();
        repo.save(url);
        return Map.of(
            "code", url.code,
            "shortUrl", "http://localhost:8080/" + url.code
        );
    }

    @GetMapping("/{code}")
    ResponseEntity<Void> redirect(@PathVariable String code) {
        return repo.findByCode(code)
            .map(u -> ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(u.originalUrl))
                .<Void>build())
            .orElse(ResponseEntity.notFound().build());
    }

    private String generateCode() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            sb.append(CHARS.charAt(random.nextInt(CHARS.length())));
        }
        return sb.toString();
    }
}