package edu.sjsu.cmpe172.scheduler.model;

/**
 * Row of the users table, plus the provider profile id when the user is a PROVIDER
 * (null for customers). Loaded at login and again for every request carrying a JWT.
 */
public record AppUser(long id, String username, String passwordHash, String fullName, Role role, Long providerId) {
}
