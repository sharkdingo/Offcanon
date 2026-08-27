package com.offcanon.identity.application;

public interface PasswordHasher {
    String hash(String password);
    boolean matches(String password, String encoded);
}
