package com.offcanon.port;

import com.offcanon.identity.domain.UserSettings;

import java.util.Optional;
import java.util.UUID;

public interface UserSettingsRepository {
    UserSettings save(UserSettings settings);
    Optional<UserSettings> findByUserId(UUID userId);
}
