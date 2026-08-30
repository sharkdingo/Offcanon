package com.offcanon.infrastructure.memory;

import com.offcanon.identity.domain.UserSettings;
import com.offcanon.port.UserSettingsRepository;
import com.offcanon.shared.domain.DomainException;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
public class InMemoryUserSettingsRepository implements UserSettingsRepository {
    private final ConcurrentHashMap<UUID, UserSettings> settings = new ConcurrentHashMap<>();

    @Override
    public synchronized UserSettings save(UserSettings value) {
        UserSettings previous = settings.get(value.userId());
        if (previous == null && value.version() != 0) {
            throw new DomainException("SETTINGS_VERSION_CONFLICT", "Settings do not exist for user: " + value.userId());
        }
        if (previous != null && value.version() != previous.version() + 1) {
            throw new DomainException("SETTINGS_VERSION_CONFLICT", "Settings changed concurrently for user: " + value.userId());
        }
        settings.put(value.userId(), value);
        return value;
    }

    @Override
    public Optional<UserSettings> findByUserId(UUID userId) {
        return Optional.ofNullable(settings.get(userId));
    }
}
