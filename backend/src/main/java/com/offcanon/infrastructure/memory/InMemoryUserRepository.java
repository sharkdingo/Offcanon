package com.offcanon.infrastructure.memory;

import com.offcanon.identity.domain.User;
import com.offcanon.port.UserRepository;
import com.offcanon.shared.domain.DomainException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Repository
@Profile("!mysql")
public class InMemoryUserRepository implements UserRepository {
    private final ConcurrentHashMap<UUID, User> users = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, UUID> usernames = new ConcurrentHashMap<>();

    @Override
    public synchronized User save(User user) {
        String username = user.username().toLowerCase(Locale.ROOT);
        UUID existing = usernames.get(username);
        if (existing != null && !existing.equals(user.id())) {
            throw new DomainException("USERNAME_TAKEN", "Username is already registered");
        }
        User previous = users.get(user.id());
        if (previous != null && user.version() != previous.version() + 1) {
            throw new DomainException("USER_VERSION_CONFLICT", "User changed concurrently: " + user.id());
        }
        if (previous == null && user.version() != 0) {
            throw new DomainException("USER_VERSION_CONFLICT", "User does not exist: " + user.id());
        }
        users.put(user.id(), user);
        usernames.put(username, user.id());
        return user;
    }

    @Override
    public Optional<User> findById(UUID id) {
        return Optional.ofNullable(users.get(id));
    }

    @Override
    public Optional<User> findByUsername(String username) {
        UUID id = usernames.get(User.normalizeUsername(username));
        return id == null ? Optional.empty() : findById(id);
    }

    @Override
    public long count() {
        return users.size();
    }
}
